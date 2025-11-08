#include "InputHandler.h"
#include <iostream>
#include <algorithm>
#include <cmath>

namespace ScreenFlow {

// Default SDL to Android key mapping
const std::unordered_map<int, int> InputHandler::DEFAULT_KEY_MAPPING = {
    {SDLK_BACKSPACE, 67},        // Android KEYCODE_DEL
    {SDLK_RETURN, 66},           // Android KEYCODE_ENTER
    {SDLK_ESCAPE, 111},          // Android KEYCODE_ESCAPE
    {SDLK_SPACE, 62},            // Android KEYCODE_SPACE
    {SDLK_TAB, 61},              // Android KEYCODE_TAB
    {SDLK_DELETE, 112},          // Android KEYCODE_FORWARD_DEL

    // Arrow keys
    {SDLK_LEFT, 21},             // Android KEYCODE_DPAD_LEFT
    {SDLK_RIGHT, 22},            // Android KEYCODE_DPAD_RIGHT
    {SDLK_UP, 19},               // Android KEYCODE_DPAD_UP
    {SDLK_DOWN, 20},             // Android KEYCODE_DPAD_DOWN

    // Function keys
    {SDLK_F1, 131},              // Android KEYCODE_F1
    {SDLK_F2, 132},              // Android KEYCODE_F2
    {SDLK_F3, 133},              // Android KEYCODE_F3
    {SDLK_F4, 134},              // Android KEYCODE_F4
    {SDLK_F5, 135},              // Android KEYCODE_F5
    {SDLK_F6, 136},              // Android KEYCODE_F6
    {SDLK_F7, 137},              // Android KEYCODE_F7
    {SDLK_F8, 138},              // Android KEYCODE_F8
    {SDLK_F9, 139},              // Android KEYCODE_F9
    {SDLK_F10, 140},             // Android KEYCODE_F10
    {SDLK_F11, 141},             // Android KEYCODE_F11
    {SDLK_F12, 142},             // Android KEYCODE_F12

    // Special Android keys
    {SDLK_AC_BACK, ANDROID_KEY_BACK},  // Android Back button
    {SDLK_MENU, ANDROID_KEY_MENU},     // Android Menu button

    // Volume keys (mapped to keyboard)
    {SDLK_AUDIOUP, ANDROID_KEY_VOLUME_UP},
    {SDLK_AUDIODOWN, ANDROID_KEY_VOLUME_DOWN},

    // Home key (mapped to a special key)
    {SDLK_HOME, ANDROID_KEY_HOME},

    // Alphanumeric keys (direct mapping for A-Z, 0-9)
    {SDLK_a, 29}, {SDLK_b, 30}, {SDLK_c, 31}, {SDLK_d, 32}, {SDLK_e, 33},
    {SDLK_f, 34}, {SDLK_g, 35}, {SDLK_h, 36}, {SDLK_i, 37}, {SDLK_j, 38},
    {SDLK_k, 39}, {SDLK_l, 40}, {SDLK_m, 41}, {SDLK_n, 42}, {SDLK_o, 43},
    {SDLK_p, 44}, {SDLK_q, 45}, {SDLK_r, 46}, {SDLK_s, 47}, {SDLK_t, 48},
    {SDLK_u, 49}, {SDLK_v, 50}, {SDLK_w, 51}, {SDLK_x, 52}, {SDLK_y, 53},
    {SDLK_z, 54},
    {SDLK_0, 7}, {SDLK_1, 8}, {SDLK_2, 9}, {SDLK_3, 10}, {SDLK_4, 11},
    {SDLK_5, 12}, {SDLK_6, 13}, {SDLK_7, 14}, {SDLK_8, 15}, {SDLK_9, 16},

    // Punctuation and symbols
    {SDLK_MINUS, 69},           // Android KEYCODE_MINUS
    {SDLK_EQUALS, 70},          // Android KEYCODE_EQUALS
    {SDLK_LEFTBRACKET, 71},     // Android KEYCODE_LEFT_BRACKET
    {SDLK_RIGHTBRACKET, 72},    // Android KEYCODE_RIGHT_BRACKET
    {SDLK_BACKSLASH, 73},       // Android KEYCODE_BACKSLASH
    {SDLK_SEMICOLON, 74},       // Android KEYCODE_SEMICOLON
    {SDLK_APOSTROPHE, 75},      // Android KEYCODE_APOSTROPHE
    {SDLK_COMMA, 55},           // Android KEYCODE_COMMA
    {SDLK_PERIOD, 56},          // Android KEYCODE_PERIOD
    {SDLK_SLASH, 76},           // Android KEYCODE_SLASH
    {SDLK_GRAVE, 68},           // Android KEYCODE_GRAVE

    // Modifier keys
    {SDLK_LSHIFT, 59},          // Android KEYCODE_SHIFT_LEFT
    {SDLK_RSHIFT, 60},          // Android KEYCODE_SHIFT_RIGHT
    {SDLK_LCTRL, 113},          // Android KEYCODE_CTRL_LEFT
    {SDLK_RCTRL, 114},          // Android KEYCODE_CTRL_RIGHT
    {SDLK_LALT, 57},            // Android KEYCODE_ALT_LEFT
    {SDLK_RALT, 58},            // Android KEYCODE_ALT_RIGHT
};

InputHandler::InputHandler() :
    windowWidth_(1280),
    windowHeight_(720),
    phoneWidth_(1080),  // Default phone resolution
    phoneHeight_(1920),
    windowFocused_(true),
    gesturesEnabled_(true),
    mouseSensitivity_(1.0f),
    dragThreshold_(10),
    pinchThreshold_(50),
    longPressThreshold_(LONG_PRESS_TIMEOUT),
    multiTapThreshold_(MULTI_TAP_TIMEOUT),
    maxPointerCount_(10),
    eventsProcessed_(0),
    eventsDropped_(0) {

    // Initialize key mapping with defaults
    keyMapping_ = DEFAULT_KEY_MAPPING;
}

InputHandler::~InputHandler() {
    cleanup();
}

bool InputHandler::initialize() {
    std::cout << "Initializing input handler..." << std::endl;

    // Reset state
    eventQueue_ = std::queue<InputEvent>();
    touchStates_.clear();
    gestureState_ = GestureState();

    std::cout << "Input handler initialized with gesture support" << std::endl;
    return true;
}

void InputHandler::cleanup() {
    std::lock_guard<std::mutex> lock(eventMutex_);
    while (!eventQueue_.empty()) {
        eventQueue_.pop();
    }
    touchStates_.clear();
}

void InputHandler::handleKeyEvent(const SDL_KeyboardEvent& event) {
    if (!windowFocused_) {
        return;
    }

    int androidKeyCode = mapSDLKeyToAndroid(event.keysym.sym);
    int androidModifiers = mapSDLModifiersToAndroid(event.keysym.mod);

    InputEvent::Type eventType = (event.type == SDL_KEYDOWN) ?
        InputEvent::KEY_DOWN : InputEvent::KEY_UP;

    InputEvent inputEvent = createKeyEvent(eventType, androidKeyCode, androidModifiers);
    inputEvent.x = 0; // Keyboard events don't have coordinates
    inputEvent.y = 0;

    addEventToQueue(inputEvent);
}

void InputHandler::handleMouseEvent(const SDL_MouseButtonEvent& event) {
    if (!windowFocused_) {
        return;
    }

    int touchButton = mapSDLButtonToTouch(event.button);
    if (touchButton == -1) {
        return; // Unsupported button
    }

    // Convert window coordinates to phone coordinates
    int phoneX, phoneY;
    convertCoordinates(event.x, event.y, phoneX, phoneY);

    InputEvent::Type eventType;
    if (event.type == SDL_MOUSEBUTTONDOWN) {
        eventType = (event.button == SDL_BUTTON_RIGHT) ?
            InputEvent::TOUCH_DOWN : InputEvent::TOUCH_DOWN; // Right click becomes long press

        // Update touch state
        TouchState& touchState = touchStates_[PRIMARY_POINTER];
        touchState.isPressed = true;
        touchState.startX = phoneX;
        touchState.startY = phoneY;
        touchState.currentX = phoneX;
        touchState.currentY = phoneY;
        touchState.startTime = getCurrentTime();
        touchState.pointerId = PRIMARY_POINTER;
    } else {
        eventType = InputEvent::TOUCH_UP;

        // Clear touch state
        touchStates_.erase(PRIMARY_POINTER);
    }

    InputEvent inputEvent = createTouchEvent(eventType, phoneX, phoneY, PRIMARY_POINTER);
    inputEvent.button = touchButton;
    inputEvent.modifiers = 0; // No modifiers for touch events

    // Check for long press (right click)
    if (event.button == SDL_BUTTON_RIGHT) {
        inputEvent.type = InputEvent::TOUCH_DOWN;
        // Will be handled as long press by gesture detection
    }

    updateGestureState(inputEvent);
    addEventToQueue(inputEvent);
}

void InputHandler::handleMouseMotionEvent(const SDL_MouseMotionEvent& event) {
    if (!windowFocused_) {
        return;
    }

    // Convert window coordinates to phone coordinates
    int phoneX, phoneY;
    convertCoordinates(event.x, event.y, phoneX, phoneY);

    // Update touch state if pressed
    auto it = touchStates_.find(PRIMARY_POINTER);
    if (it != touchStates_.end() && it->second.isPressed) {
        it->second.currentX = phoneX;
        it->second.currentY = phoneY;

        InputEvent inputEvent = createTouchEvent(InputEvent::TOUCH_MOVE, phoneX, phoneY, PRIMARY_POINTER);
        updateGestureState(inputEvent);
        addEventToQueue(inputEvent);
    }
}

void InputHandler::handleMouseWheelEvent(const SDL_MouseWheelEvent& event) {
    if (!windowFocused_) {
        return;
    }

    // Get current mouse position
    int mouseX, mouseY;
    SDL_GetMouseState(&mouseX, &mouseY);

    int phoneX, phoneY;
    convertCoordinates(mouseX, mouseY, phoneX, phoneY);

    InputEvent scrollEvent = createScrollEvent(phoneX, phoneY, event.x, event.y);
    addEventToQueue(scrollEvent);
}

std::vector<InputEvent> InputHandler::getPendingEvents() {
    std::lock_guard<std::mutex> lock(eventMutex_);
    std::vector<InputEvent> events;

    while (!eventQueue_.empty()) {
        events.push_back(eventQueue_.front());
        eventQueue_.pop();
    }

    return events;
}

void InputHandler::clearPendingEvents() {
    std::lock_guard<std::mutex> lock(eventMutex_);
    while (!eventQueue_.empty()) {
        eventQueue_.pop();
        eventsDropped_++;
    }
}

void InputHandler::setScreenDimensions(int width, int height) {
    windowWidth_ = width;
    windowHeight_ = height;
}

void InputHandler::setWindowFocused(bool focused) {
    windowFocused_ = focused;

    if (!focused) {
        // Clear all touch states when window loses focus
        touchStates_.clear();
        gestureState_ = GestureState();
    }
}

void InputHandler::setMouseSensitivity(float sensitivity) {
    mouseSensitivity_ = std::max(0.1f, std::min(3.0f, sensitivity));
}

void InputHandler::setKeyMapping(const std::unordered_map<int, int>& mapping) {
    keyMapping_ = mapping;
}

void InputHandler::enableGestures(bool enable) {
    gesturesEnabled_ = enable;
}

void InputHandler::setGestureThresholds(int dragThreshold, int pinchThreshold) {
    dragThreshold_ = std::max(1, dragThreshold);
    pinchThreshold_ = std::max(10, pinchThreshold);
}

int InputHandler::getEventsProcessed() const {
    return eventsProcessed_;
}

int InputHandler::getEventsDropped() const {
    return eventsDropped_;
}

InputEvent InputHandler::createTouchEvent(InputEvent::Type type, int x, int y, int pointer) {
    InputEvent event;
    event.type = type;
    event.x = x;
    event.y = y;
    event.button = pointer;
    event.modifiers = 0;
    event.timestamp = getCurrentTime();
    return event;
}

InputEvent InputHandler::createKeyEvent(InputEvent::Type type, int keyCode, int modifiers) {
    InputEvent event;
    event.type = type;
    event.x = 0;
    event.y = 0;
    event.button = keyCode;
    event.modifiers = modifiers;
    event.timestamp = getCurrentTime();
    return event;
}

InputEvent InputHandler::createScrollEvent(int x, int y, int deltaX, int deltaY) {
    InputEvent event;
    event.type = InputEvent::MOUSE_WHEEL;
    event.x = x;
    event.y = y;
    event.button = deltaX; // Use button field for X scroll
    event.modifiers = deltaY; // Use modifiers field for Y scroll
    event.timestamp = getCurrentTime();
    return event;
}

void InputHandler::convertCoordinates(int windowX, int windowY, int& phoneX, int& phoneY) {
    // Convert window coordinates to phone coordinates (0-1000 range for Android)
    float normalizedX = (float)windowX / windowWidth_;
    float normalizedY = (float)windowY / windowHeight_;

    // Apply mouse sensitivity
    normalizedX = (normalizedX - 0.5f) * mouseSensitivity_ + 0.5f;
    normalizedY = (normalizedY - 0.5f) * mouseSensitivity_ + 0.5f;

    // Clamp to valid range
    normalizedX = std::max(0.0f, std::min(1.0f, normalizedX));
    normalizedY = std::max(0.0f, std::min(1.0f, normalizedY));

    // Convert to Android coordinate system (0-1000)
    phoneX = static_cast<int>(normalizedX * COORDINATE_SCALE);
    phoneY = static_cast<int>(normalizedY * COORDINATE_SCALE);
}

void InputHandler::updateGestureState(const InputEvent& event) {
    if (!gesturesEnabled_) {
        return;
    }

    int64_t currentTime = getCurrentTime();

    switch (event.type) {
        case InputEvent::TOUCH_DOWN:
            gestureState_.startTime = currentTime;
            gestureState_.currentType = GestureState::NONE;
            break;

        case InputEvent::TOUCH_MOVE: {
            auto it = touchStates_.find(PRIMARY_POINTER);
            if (it != touchStates_.end()) {
                int deltaX = abs(it->second.currentX - it->second.startX);
                int deltaY = abs(it->second.currentY - it->second.startY);
                int distance = static_cast<int>(sqrt(deltaX * deltaX + deltaY * deltaY));

                if (distance > dragThreshold_ && !gestureState_.isActive) {
                    gestureState_.isActive = true;
                    gestureState_.currentType = GestureState::DRAG;
                    std::cout << "Drag gesture detected" << std::endl;
                }
            }
            break;
        }

        case InputEvent::TOUCH_UP: {
            // Check for long press
            int64_t pressDuration = currentTime - gestureState_.startTime;
            if (pressDuration >= longPressThreshold_ && !gestureState_.isActive) {
                // Convert to long press event
                InputEvent longPressEvent = createTouchEvent(InputEvent::TOUCH_DOWN, event.x, event.y, event.button);
                longPressEvent.timestamp = event.timestamp - 100; // Slight delay for long press
                addEventToQueue(longPressEvent);

                std::cout << "Long press gesture detected" << std::endl;
            }

            // Reset gesture state
            gestureState_ = GestureState();
            break;
        }

        default:
            break;
    }
}

bool InputHandler::detectDragGesture() {
    // Implementation for drag detection
    return false;
}

bool InputHandler::detectPinchGesture() {
    // Implementation for pinch detection (requires multi-touch)
    return false;
}

bool InputHandler::detectLongPress() {
    // Implementation for long press detection
    return false;
}

int InputHandler::mapSDLKeyToAndroid(int sdlKey) {
    auto it = keyMapping_.find(sdlKey);
    if (it != keyMapping_.end()) {
        return it->second;
    }

    // For unmapped keys, try to use direct mapping for alphanumeric
    if (sdlKey >= SDLK_a && sdlKey <= SDLK_z) {
        return 29 + (sdlKey - SDLK_a); // Android A-Z mapping
    }
    if (sdlKey >= SDLK_0 && sdlKey <= SDLK_9) {
        return 7 + (sdlKey - SDLK_0); // Android 0-9 mapping
    }

    return 0; // Unknown key
}

int InputHandler::mapSDLModifiersToAndroid(int sdlModifiers) {
    int androidModifiers = 0;

    if (sdlModifiers & KMOD_SHIFT) androidModifiers |= 0x01; // SHIFT
    if (sdlModifiers & KMOD_CTRL) androidModifiers |= 0x02;  // CTRL
    if (sdlModifiers & KMOD_ALT) androidModifiers |= 0x04;   // ALT
    if (sdlModifiers & KMOD_GUI) androidModifiers |= 0x08;   // META

    return androidModifiers;
}

int InputHandler::mapSDLButtonToTouch(int sdlButton) {
    switch (sdlButton) {
        case SDL_BUTTON_LEFT:
            return 1; // Primary touch
        case SDL_BUTTON_RIGHT:
            return 2; // Secondary touch (for long press)
        case SDL_BUTTON_MIDDLE:
            return 3; // Tertiary touch
        default:
            return -1; // Unsupported
    }
}

int64_t InputHandler::getCurrentTime() const {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}

void InputHandler::addEventToQueue(const InputEvent& event) {
    std::lock_guard<std::mutex> lock(eventMutex_);

    // Limit queue size to prevent memory buildup
    if (eventQueue_.size() >= 100) {
        eventQueue_.pop();
        eventsDropped_++;
    }

    eventQueue_.push(event);
    eventsProcessed_++;
}

} // namespace ScreenFlow