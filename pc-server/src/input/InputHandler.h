#pragma once

#include <SDL2/SDL.h>
#include <queue>
#include <mutex>
#include <memory>
#include <unordered_map>

#include "../common/Types.h"

namespace ScreenFlow {

class InputHandler {
public:
    InputHandler();
    ~InputHandler();

    bool initialize();
    void cleanup();

    // SDL event handling
    void handleKeyEvent(const SDL_KeyboardEvent& event);
    void handleMouseEvent(const SDL_MouseButtonEvent& event);
    void handleMouseMotionEvent(const SDL_MouseMotionEvent& event);
    void handleMouseWheelEvent(const SDL_MouseWheelEvent& event);

    // Event queue management
    std::vector<InputEvent> getPendingEvents();
    void clearPendingEvents();

    // Configuration
    void setScreenDimensions(int width, int height);
    void setWindowFocused(bool focused);
    void setMouseSensitivity(float sensitivity);
    void setKeyMapping(const std::unordered_map<int, int>& mapping);

    // Touch gesture support
    void enableGestures(bool enable);
    void setGestureThresholds(int dragThreshold, int pinchThreshold);

    // Statistics
    int getEventsProcessed() const;
    int getEventsDropped() const;

private:
    // Event queue management
    void addEventToQueue(const InputEvent& event);

    // Event creation
    InputEvent createTouchEvent(InputEvent::Type type, int x, int y, int pointer = 0);
    InputEvent createKeyEvent(InputEvent::Type type, int keyCode, int modifiers);
    InputEvent createScrollEvent(int x, int y, int deltaX, int deltaY);

    // Coordinate conversion
    void convertCoordinates(int windowX, int windowY, int& phoneX, int& phoneY);

    // Gesture detection
    void updateGestureState(const InputEvent& event);
    bool detectDragGesture();
    bool detectPinchGesture();
    bool detectLongPress();

    // Key mapping
    int mapSDLKeyToAndroid(int sdlKey);
    int mapSDLModifiersToAndroid(int sdlModifiers);

    // Mouse button mapping
    int mapSDLButtonToTouch(int sdlButton);

    // Touch state management
    struct TouchState {
        bool isPressed = false;
        int startX = 0;
        int startY = 0;
        int currentX = 0;
        int currentY = 0;
        int64_t startTime = 0;
        int pointerId = 0;
    };

    // Gesture state
    struct GestureState {
        bool isActive = false;
        enum Type { DRAG, PINCH, LONG_PRESS, NONE } currentType = NONE;
        int64_t startTime = 0;
        float startDistance = 0;
        float currentDistance = 0;
    };

    // Event queue
    std::queue<InputEvent> eventQueue_;
    std::mutex eventMutex_;

    // Screen dimensions for coordinate conversion
    int windowWidth_;
    int windowHeight_;
    int phoneWidth_;
    int phoneHeight_;

    // Input state
    bool windowFocused_;
    bool gesturesEnabled_;
    float mouseSensitivity_;

    // Touch and gesture state
    std::unordered_map<int, TouchState> touchStates_; // key: pointer ID
    GestureState gestureState_;

    // Key mapping
    std::unordered_map<int, int> keyMapping_;

    // Configuration
    int dragThreshold_;
    int pinchThreshold_;
    int longPressThreshold_;
    int multiTapThreshold_;
    int maxPointerCount_;

    // Statistics
    int eventsProcessed_;
    int eventsDropped_;

    // Time utilities
    int64_t getCurrentTime() const;

    // Special keys
    static const std::unordered_map<int, int> DEFAULT_KEY_MAPPING;
    static const int ANDROID_KEY_BACK = 4;
    static const int ANDROID_KEY_HOME = 3;
    static const int ANDROID_KEY_MENU = 82;
    static const int ANDROID_KEY_VOLUME_UP = 24;
    static const int ANDROID_KEY_VOLUME_DOWN = 25;

    // Touch constants
    static const int PRIMARY_POINTER = 0;
    static const int MAX_POINTER_ID = 255;
    static const int COORDINATE_SCALE = 1000; // Android uses 0-1000 range

    // Gesture timing constants (in milliseconds)
    static const int LONG_PRESS_TIMEOUT = 500;
    static const int MULTI_TAP_TIMEOUT = 300;
    static const int GESTURE_DEBOUNCE = 50;
};

} // namespace ScreenFlow