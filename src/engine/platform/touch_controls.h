#pragma once

#include <glm/glm.hpp>

#include <array>
#include <cstdint>

union SDL_Event;
struct ImDrawList;
struct ImTextureData;

namespace pt {

// What the touch overlay did since the last Poll (docs/android.md, Touch controls)
struct TouchSample {
    glm::vec2 stick{0.0f};  // the walk stick, y up, length at most 1
    glm::vec2 look{0.0f};   // how far the look finger moved, in the units of the mouse's counts (scaled by the mouse sensitivity)
    uint32_t raw = 0;       // PadRaw bits the buttons hold: the action (cross) and the zoom (R3)
    bool pause = false;     // edges: the menu button, the PC settings button, the XMark's X, the spoken word's stand-in
    bool settings = false;
    bool gouge = false;
    bool voice = false;
    bool touched = false;   // a finger went down in play
};

// The on-screen controls: a walk stick that appears under the left thumb, the rest of the screen looks around, and buttons for the
// action, zoom, X, the menu, the PC settings and the spoken word. Only on Android; elsewhere every call does nothing, and in the
// menus the touch is the mouse pointer SDL makes of it, so the overlay lives in play only (the mouse captured, MouseUse::Look).
// They are drawn from one picture (touch/hud.png beside the executable: rings with the button names, the stick's base and thumb);
// without it plain circles and words are drawn instead.
class TouchControls {
public:
    static constexpr bool Supported() {
#ifdef __ANDROID__
        return true;
#else
        return false;
#endif
    }

    // A finger event. True when the overlay took it. Fingers are only taken in play; a finger already down is released either way.
    bool ProcessEvent(const SDL_Event& event, bool in_play);
    // The state for this Poll; outside play every finger is released and the sample is empty
    TouchSample Sample(bool in_play);
    // The overlay, in the display size ImGui draws in (also what the finger positions are measured against)
    void Draw(ImDrawList* list, float width, float height);
    // A pad was the last thing used: the overlay is hidden until the next touch
    void SetHidden(bool hidden) { hidden_ = hidden; }

private:
    enum Button { kAct, kZoom, kGouge, kPause, kSettings, kVoice, kButtonCount };
    enum class Role : uint8_t { Stick, Look, Button };

    struct Finger {
        bool active = false;
        uint64_t id = 0;
        Role role = Role::Look;
        int button = -1;
        glm::vec2 start{0.0f};
        glm::vec2 pos{0.0f};
    };

    struct Circle {
        glm::vec2 center{0.0f};
        float radius = 0.0f;
    };

    float Unit() const;
    void LoadSkin();
    void DrawSkinned(ImDrawList* list);
    void DrawPlain(ImDrawList* list);
    Circle ButtonCircle(int button) const;
    float StickRadius() const;
    Finger* Find(uint64_t id);
    void Down(uint64_t id, glm::vec2 pos);
    void ReleaseAll();
    bool ButtonHeld(int button) const;

    std::array<Finger, 10> fingers_{};
    float width_ = 1280.0f;
    float height_ = 720.0f;
    glm::vec2 look_{0.0f};
    bool pending_pause_ = false;
    bool pending_settings_ = false;
    bool pending_gouge_ = false;
    bool pending_voice_ = false;
    bool pending_touched_ = false;
    bool visible_ = false;
    bool hidden_ = false;
    ImTextureData* skin_ = nullptr;  // the picture, owned by ImGui's texture list for the rest of the run
    bool skin_tried_ = false;
};

}
