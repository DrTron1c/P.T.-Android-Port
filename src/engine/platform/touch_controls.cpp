#include "engine/platform/touch_controls.h"

#include <SDL3/SDL.h>
#include <imgui.h>
#include <imgui_internal.h>
#include <stb_image.h>

#include <algorithm>
#include <cfloat>
#include <cstring>

#include "engine/core/log.h"
#include "engine/core/resource_path.h"
#include "engine/platform/input.h"

namespace pt {
namespace {

constexpr float kStickDeadZone = 0.12f;
// how much farther than the drawn circle a finger still counts as on a button
constexpr float kButtonSlop = 1.2f;
// the walk stick takes a finger that lands in the left part of the screen below the top strip
constexpr float kStickZoneWidth = 0.42f;
constexpr float kStickZoneTop = 0.18f;

constexpr const char* kLabels[] = {"ACT", "ZOOM", "X", "II", "SET", "JACK"};

// The pieces of touch/hud.png (assets/touch, cut from the HUD picture by the project): the position and size in the picture, the ring's
// centre inside that piece, and the ring's radius, all in the picture's pixels. The six buttons come first, in the order of the Button
// enum, then the stick's base and the thumb.
struct Sprite {
    int x, y, w, h;
    float cx, cy, ring;
};
constexpr Sprite kSprites[] = {
    {357, 1, 263, 304, 132.0f, 132.0f, 106.0f},  // ACT
    {621, 1, 197, 259, 99.0f, 99.0f, 79.0f},     // ZOOM
    {688, 415, 157, 200, 79.0f, 79.0f, 63.0f},   // X
    {376, 415, 157, 207, 79.0f, 79.0f, 63.0f},   // II
    {534, 415, 153, 201, 77.0f, 77.0f, 61.0f},   // SET
    {214, 415, 161, 211, 81.0f, 81.0f, 64.0f},   // JACK
    {1, 1, 355, 413, 178.0f, 178.0f, 144.0f},    // the stick's base
    {1, 415, 212, 212, 106.0f, 106.0f, 80.0f},   // the stick's thumb
};
constexpr int kSpriteStick = 6;
constexpr int kSpriteThumb = 7;

// one piece of the picture with its ring at `center` and `ring` pixels in radius
void DrawSprite(ImDrawList* list, ImTextureData* picture, int index, ImVec2 center, float ring, ImU32 tint) {
    const Sprite& sprite = kSprites[index];
    const float k = ring / sprite.ring;
    const ImVec2 from(center.x - sprite.cx * k, center.y - sprite.cy * k);
    const ImVec2 to(from.x + sprite.w * k, from.y + sprite.h * k);
    const float width = static_cast<float>(picture->Width);
    const float height = static_cast<float>(picture->Height);
    list->AddImage(picture->GetTexRef(), from, to, ImVec2(sprite.x / width, sprite.y / height),
                   ImVec2((sprite.x + sprite.w) / width, (sprite.y + sprite.h) / height), tint);
}

}  // namespace

float TouchControls::Unit() const { return std::min(width_, height_); }

float TouchControls::StickRadius() const { return 0.14f * Unit(); }

// Where the buttons are, from the HUD picture (measured there against the shorter side): the action button in the lower right corner with
// zoom to its upper left and X above it; the menu and the PC settings buttons in the upper right corner; the spoken word's in the
// upper left.
TouchControls::Circle TouchControls::ButtonCircle(int button) const {
    const float u = Unit();
    const glm::vec2 act(width_ - 0.1396f * u, height_ - 0.168f * u);
    switch (button) {
    case kAct: return {act, 0.1035f * u};
    case kZoom: return {act + glm::vec2(-0.169f * u, -0.1143f * u), 0.0771f * u};
    case kGouge: return {act + glm::vec2(0.0098f * u, -0.2158f * u), 0.0615f * u};
    case kPause: return {glm::vec2(width_ - 0.1094f * u, 0.1465f * u), 0.0615f * u};
    case kSettings: return {glm::vec2(width_ - 0.2637f * u, 0.1465f * u), 0.0596f * u};
    default: return {glm::vec2(0.1348f * u, 0.1367f * u), 0.0625f * u};
    }
}

TouchControls::Finger* TouchControls::Find(uint64_t id) {
    for (Finger& finger : fingers_) {
        if (finger.active && finger.id == id) return &finger;
    }
    return nullptr;
}

bool TouchControls::ButtonHeld(int button) const {
    for (const Finger& finger : fingers_) {
        if (finger.active && finger.role == Role::Button && finger.button == button) return true;
    }
    return false;
}

void TouchControls::ReleaseAll() {
    for (Finger& finger : fingers_) finger = Finger{};
}

void TouchControls::Down(uint64_t id, glm::vec2 pos) {
    Finger* slot = nullptr;
    for (Finger& finger : fingers_) {
        if (!finger.active) {
            slot = &finger;
            break;
        }
    }
    if (!slot) return;
    *slot = Finger{};
    slot->active = true;
    slot->id = id;
    slot->start = pos;
    slot->pos = pos;
    pending_touched_ = true;

    for (int button = 0; button < kButtonCount; ++button) {
        const Circle circle = ButtonCircle(button);
        if (glm::distance(pos, circle.center) > circle.radius * kButtonSlop) continue;
        // a button is held by one finger: a second finger on it looks around instead
        if ((button == kAct || button == kZoom) && ButtonHeld(button)) break;
        slot->role = Role::Button;
        slot->button = button;
        switch (button) {
        case kGouge: pending_gouge_ = true; break;
        case kPause: pending_pause_ = true; break;
        case kSettings: pending_settings_ = true; break;
        case kVoice: pending_voice_ = true; break;
        default: break;
        }
        return;
    }
    bool stick_taken = false;
    for (const Finger& finger : fingers_) {
        if (finger.active && finger.role == Role::Stick) stick_taken = true;
    }
    if (!stick_taken && pos.x < kStickZoneWidth * width_ && pos.y > kStickZoneTop * height_) {
        slot->role = Role::Stick;
        return;
    }
    slot->role = Role::Look;
}

bool TouchControls::ProcessEvent(const SDL_Event& event, bool in_play) {
    if (!Supported()) return false;
    switch (event.type) {
    case SDL_EVENT_FINGER_DOWN: {
        if (!in_play) return false;
        Down(event.tfinger.fingerID, glm::vec2(event.tfinger.x * width_, event.tfinger.y * height_));
        return true;
    }
    case SDL_EVENT_FINGER_MOTION: {
        Finger* finger = Find(event.tfinger.fingerID);
        if (!finger) return false;
        finger->pos = glm::vec2(event.tfinger.x * width_, event.tfinger.y * height_);
        if (finger->role == Role::Look) {
            look_ += glm::vec2(event.tfinger.dx * width_, event.tfinger.dy * height_);
        }
        return true;
    }
    case SDL_EVENT_FINGER_UP:
    case SDL_EVENT_FINGER_CANCELED: {
        Finger* finger = Find(event.tfinger.fingerID);
        if (!finger) return false;
        *finger = Finger{};
        return true;
    }
    default:
        return false;
    }
}

TouchSample TouchControls::Sample(bool in_play) {
    TouchSample sample;
    visible_ = Supported() && in_play;
    if (!Supported()) return sample;
    if (!in_play) {
        ReleaseAll();
        look_ = glm::vec2(0.0f);
        pending_pause_ = pending_settings_ = pending_gouge_ = pending_voice_ = pending_touched_ = false;
        return sample;
    }
    for (const Finger& finger : fingers_) {
        if (!finger.active || finger.role != Role::Stick) continue;
        glm::vec2 offset = (finger.pos - finger.start) / StickRadius();
        const float length = glm::length(offset);
        if (length > 1.0f) offset /= length;
        const float strength = std::min(length, 1.0f);
        if (strength > kStickDeadZone && length > 0.0f) {
            const float scaled = (strength - kStickDeadZone) / (1.0f - kStickDeadZone);
            offset = offset / length * scaled;
            sample.stick = glm::vec2(offset.x, -offset.y);
        }
    }
    if (ButtonHeld(kAct)) sample.raw |= kRawCross;
    if (ButtonHeld(kZoom)) sample.raw |= kRawR3;
    sample.look = look_;
    look_ = glm::vec2(0.0f);
    sample.pause = pending_pause_;
    sample.settings = pending_settings_;
    sample.gouge = pending_gouge_;
    sample.voice = pending_voice_;
    sample.touched = pending_touched_;
    pending_pause_ = pending_settings_ = pending_gouge_ = pending_voice_ = pending_touched_ = false;
    return sample;
}

void TouchControls::LoadSkin() {
    skin_tried_ = true;
    const std::filesystem::path path = ExecutableDir() / "touch" / "hud.png";
    int width = 0;
    int height = 0;
    int channels = 0;
    unsigned char* pixels = stbi_load(path.string().c_str(), &width, &height, &channels, 4);
    if (!pixels) {
        LogWarn("touch: {} is missing or unreadable; plain circles are drawn", path.string());
        return;
    }
    ImTextureData* picture = new ImTextureData();
    picture->Create(ImTextureFormat_RGBA32, width, height);
    std::memcpy(picture->GetPixels(), pixels, static_cast<size_t>(width) * height * 4);
    stbi_image_free(pixels);
    ImGui::RegisterUserTexture(picture);
    skin_ = picture;
    LogInfo("touch: HUD picture {}x{} loaded", width, height);
}

void TouchControls::Draw(ImDrawList* list, float width, float height) {
    if (width > 0.0f && height > 0.0f) {
        width_ = width;
        height_ = height;
    }
    if (!list || !visible_ || hidden_) return;
    if (!skin_tried_) LoadSkin();
    if (skin_) {
        DrawSkinned(list);
    } else {
        DrawPlain(list);
    }
}

void TouchControls::DrawSkinned(ImDrawList* list) {
    const ImU32 idle = IM_COL32(255, 255, 255, 215);
    const ImU32 held = IM_COL32(255, 255, 255, 255);
    const ImU32 hint = IM_COL32(255, 255, 255, 120);
    const float base_ring = StickRadius();
    const float thumb_ring = base_ring * kSprites[kSpriteThumb].ring / kSprites[kSpriteStick].ring;

    bool stick_active = false;
    for (const Finger& finger : fingers_) {
        if (!finger.active || finger.role != Role::Stick) continue;
        stick_active = true;
        glm::vec2 offset = finger.pos - finger.start;
        const float length = glm::length(offset);
        if (length > base_ring) offset *= base_ring / length;
        const ImVec2 center(finger.start.x, finger.start.y);
        DrawSprite(list, skin_, kSpriteStick, center, base_ring, held);
        // the thumb follows the finger, but less far than the finger goes: it stays mostly inside the base
        DrawSprite(list, skin_, kSpriteThumb, ImVec2(center.x + offset.x * 0.6f, center.y + offset.y * 0.6f), thumb_ring, held);
    }
    if (!stick_active) {
        // where the stick appears: anywhere in the left part of the screen; shown here, faintly
        const float u = Unit();
        const ImVec2 at(0.2178f * u, height_ - 0.2529f * u);
        DrawSprite(list, skin_, kSpriteStick, at, base_ring, hint);
        DrawSprite(list, skin_, kSpriteThumb, at, thumb_ring, hint);
    }
    for (int button = 0; button < kButtonCount; ++button) {
        const Circle circle = ButtonCircle(button);
        const ImVec2 center(circle.center.x, circle.center.y);
        if (ButtonHeld(button)) {
            // lit while a finger is on it: a warm glow behind, and the ring a little larger
            list->AddCircleFilled(center, circle.radius * 1.02f, IM_COL32(255, 232, 190, 48), 48);
            DrawSprite(list, skin_, button, center, circle.radius * 1.05f, held);
        } else {
            DrawSprite(list, skin_, button, center, circle.radius, idle);
        }
    }
}

void TouchControls::DrawPlain(ImDrawList* list) {
    const float u = Unit();
    ImFont* font = ImGui::GetFont();
    const float text_size = 0.032f * u;
    const ImU32 outline = IM_COL32(255, 255, 255, 130);
    const ImU32 fill_idle = IM_COL32(255, 255, 255, 28);
    const ImU32 fill_held = IM_COL32(255, 255, 255, 90);
    const ImU32 text_color = IM_COL32(255, 255, 255, 190);

    bool stick_active = false;
    for (const Finger& finger : fingers_) {
        if (!finger.active || finger.role != Role::Stick) continue;
        stick_active = true;
        const float radius = StickRadius();
        glm::vec2 offset = finger.pos - finger.start;
        const float length = glm::length(offset);
        if (length > radius) offset *= radius / length;
        list->AddCircleFilled(ImVec2(finger.start.x, finger.start.y), radius, fill_idle, 48);
        list->AddCircle(ImVec2(finger.start.x, finger.start.y), radius, outline, 48, 2.0f);
        list->AddCircleFilled(ImVec2(finger.start.x + offset.x, finger.start.y + offset.y), radius * 0.42f, fill_held, 32);
    }
    if (!stick_active) {
        // where the stick appears: anywhere in the left part of the screen
        const ImVec2 hint(0.2f * u, height_ - 0.24f * u);
        list->AddCircle(hint, StickRadius() * 0.8f, IM_COL32(255, 255, 255, 50), 40, 2.0f);
    }
    for (int button = 0; button < kButtonCount; ++button) {
        const Circle circle = ButtonCircle(button);
        const ImVec2 center(circle.center.x, circle.center.y);
        list->AddCircleFilled(center, circle.radius, ButtonHeld(button) ? fill_held : fill_idle, 40);
        list->AddCircle(center, circle.radius, outline, 40, 2.0f);
        const char* label = kLabels[button];
        const ImVec2 size = font->CalcTextSizeA(text_size, FLT_MAX, 0.0f, label);
        list->AddText(font, text_size, ImVec2(center.x - size.x * 0.5f, center.y - size.y * 0.5f), text_color, label);
    }
}

}
