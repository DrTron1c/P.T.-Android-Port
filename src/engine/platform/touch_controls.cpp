#include "engine/platform/touch_controls.h"

#include <SDL3/SDL.h>
#include <imgui.h>

#include <algorithm>
#include <cfloat>

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

}  // namespace

float TouchControls::Unit() const { return std::min(width_, height_); }

float TouchControls::StickRadius() const { return 0.13f * Unit(); }

TouchControls::Circle TouchControls::ButtonCircle(int button) const {
    const float u = Unit();
    const float margin = 0.05f * u;
    const float act_radius = 0.095f * u;
    const glm::vec2 act(width_ - margin - act_radius - 0.02f * u, height_ - margin - act_radius);
    switch (button) {
    case kAct: return {act, act_radius};
    case kZoom: return {act + glm::vec2(-0.17f * u, -0.10f * u), 0.075f * u};
    case kGouge: return {act + glm::vec2(0.0f, -0.21f * u), 0.06f * u};
    case kPause: return {glm::vec2(width_ - margin - 0.05f * u, margin + 0.05f * u), 0.05f * u};
    case kSettings: return {glm::vec2(width_ - margin - 0.05f * u - 0.12f * u, margin + 0.05f * u), 0.045f * u};
    default: return {glm::vec2(margin + 0.05f * u, margin + 0.05f * u), 0.05f * u};
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

void TouchControls::Draw(ImDrawList* list, float width, float height) {
    if (width > 0.0f && height > 0.0f) {
        width_ = width;
        height_ = height;
    }
    if (!list || !visible_ || hidden_) return;
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
