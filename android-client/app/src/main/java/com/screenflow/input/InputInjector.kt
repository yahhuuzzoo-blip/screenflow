package com.screenflow.input

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Injects input events received from PC server into Android system
 */
class InputInjector(private val context: Context) {

    companion object {
        // Touch event types
        const val TOUCH_DOWN = 0
        const val TOUCH_UP = 1
        const val TOUCH_MOVE = 2
        const val TOUCH_CANCEL = 3

        // Mouse event types
        const val MOUSE_DOWN = 10
        const val MOUSE_UP = 11
        const val MOUSE_MOVE = 12
        const val MOUSE_WHEEL = 13

        // Keyboard event types
        const val KEY_DOWN = 20
        const val KEY_UP = 21

        // Android key codes mapping
        private val ANDROID_KEY_MAP = mapOf(
            // Special keys
            "back" to 4,
            "home" to 3,
            "menu" to 82,
            "search" to 84,
            "volume_up" to 24,
            "volume_down" to 25,
            "power" to 26,

            // Navigation
            "dpad_up" to 19,
            "dpad_down" to 20,
            "dpad_left" to 21,
            "dpad_right" to 22,
            "dpad_center" to 23,

            // Function keys
            "f1" to 131,
            "f2" to 132,
            "f3" to 133,
            "f4" to 134,
            "f5" to 135,
            "f6" to 136,
            "f7" to 137,
            "f8" to 138,
            "f9" to 139,
            "f10" to 140,
            "f11" to 141,
            "f12" to 142,

            // Modifier keys
            "shift_left" to 59,
            "shift_right" to 60,
            "ctrl_left" to 113,
            "ctrl_right" to 114,
            "alt_left" to 57,
            "alt_right" to 58,

            // Common keys
            "enter" to 66,
            "del" to 67,
            "escape" to 111,
            "space" to 62,
            "tab" to 61,

            // Arrow keys
            "arrow_up" to 19,
            "arrow_down" to 20,
            "arrow_left" to 21,
            "arrow_right" to 22
        )

        private const val MAX_QUEUE_SIZE = 100
    }

    // Input injection state
    private var isInitialized = AtomicBoolean(false)
    private var hasAccessibilityPermission = false
    private var useInputMethod = false

    // Display metrics
    private var displayWidth = 0
    private var displayHeight = 0

    // Event queue
    private val inputEventQueue = ConcurrentLinkedQueue<InputEventData>()
    private val isProcessingEvents = AtomicBoolean(false)

    // Handler for main thread operations
    private val mainHandler = Handler(Looper.getMainLooper())

    // Statistics
    private var eventsInjected = 0L
    private var eventsDropped = 0L

    data class InputEventData(
        val type: Int,
        val x: Int = 0,
        val y: Int = 0,
        val button: Int = 0,
        val keyCode: Int = 0,
        val modifiers: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    init {
        initialize()
    }

    private fun initialize() {
        try {
            getDisplayMetrics()
            checkPermissions()

            if (hasAccessibilityPermission) {
                initializeAccessibilityInjection()
            } else {
                initializeInputMethodInjection()
            }

            isInitialized.set(true)
            Timber.d("InputInjector initialized with method: ${if (hasAccessibilityPermission) "Accessibility" else "InputMethod"}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize InputInjector")
        }
    }

    private fun getDisplayMetrics() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.WINDOW_SERVICE).javaClass
                .getMethod("getDefaultDisplay").invoke(null) as android.view.Display
        }

        val point = Point()
        display.getSize(point)
        displayWidth = point.x
        displayHeight = point.y

        Timber.d("Display dimensions: ${displayWidth}x$displayHeight")
    }

    private fun checkPermissions() {
        // Check for accessibility service permission
        hasAccessibilityPermission = isAccessibilityServiceEnabled()

        if (!hasAccessibilityPermission) {
            // Fall back to input method if accessibility is not available
            useInputMethod = true
            Timber.w("Accessibility service not enabled, using fallback method")
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val serviceComponent = "${context.packageName}/.input.ScreenFlowAccessibilityService"
        return enabledServicesSetting.contains(serviceComponent)
    }

    private fun initializeAccessibilityInjection() {
        // Initialize accessibility service communication
        Timber.d("Accessibility injection initialized")
    }

    private fun initializeInputMethodInjection() {
        // Initialize input method service communication
        useInputMethod = true
        Timber.d("Input method injection initialized")
    }

    fun injectInputEvent(event: NetworkManager.InputEventData) {
        if (!isInitialized.get()) {
            return
        }

        try {
            val inputEvent = convertNetworkEventToInputEvent(event)
            if (inputEvent != null) {
                queueInputEvent(inputEvent)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error injecting input event")
        }
    }

    private fun convertNetworkEventToInputEvent(networkEvent: NetworkManager.InputEventData): InputEventData? {
        return try {
            when (networkEvent.type) {
                "touch_down" -> InputEventData(
                    type = TOUCH_DOWN,
                    x = convertCoordinate(networkEvent.x, displayWidth),
                    y = convertCoordinate(networkEvent.y, displayHeight),
                    button = networkEvent.button,
                    timestamp = networkEvent.timestamp
                )

                "touch_up" -> InputEventData(
                    type = TOUCH_UP,
                    x = convertCoordinate(networkEvent.x, displayWidth),
                    y = convertCoordinate(networkEvent.y, displayHeight),
                    button = networkEvent.button,
                    timestamp = networkEvent.timestamp
                )

                "touch_move" -> InputEventData(
                    type = TOUCH_MOVE,
                    x = convertCoordinate(networkEvent.x, displayWidth),
                    y = convertCoordinate(networkEvent.y, displayHeight),
                    button = networkEvent.button,
                    timestamp = networkEvent.timestamp
                )

                "mouse_down" -> InputEventData(
                    type = MOUSE_DOWN,
                    x = convertCoordinate(networkEvent.x, displayWidth),
                    y = convertCoordinate(networkEvent.y, displayHeight),
                    button = networkEvent.button,
                    timestamp = networkEvent.timestamp
                )

                "mouse_up" -> InputEventData(
                    type = MOUSE_UP,
                    x = convertCoordinate(networkEvent.x, displayWidth),
                    y = convertCoordinate(networkEvent.y, displayHeight),
                    button = networkEvent.button,
                    timestamp = networkEvent.timestamp
                )

                "mouse_move" -> InputEventData(
                    type = MOUSE_MOVE,
                    x = convertCoordinate(networkEvent.x, displayWidth),
                    y = convertCoordinate(networkEvent.y, displayHeight),
                    timestamp = networkEvent.timestamp
                )

                "mouse_wheel" -> InputEventData(
                    type = MOUSE_WHEEL,
                    x = networkEvent.x,
                    y = networkEvent.y,
                    button = networkEvent.button, // Scroll delta
                    timestamp = networkEvent.timestamp
                )

                "key_down" -> {
                    val keyCode = mapKeyToAndroid(networkEvent.button.toString())
                    InputEventData(
                        type = KEY_DOWN,
                        keyCode = keyCode,
                        modifiers = networkEvent.modifiers,
                        timestamp = networkEvent.timestamp
                    )
                }

                "key_up" -> {
                    val keyCode = mapKeyToAndroid(networkEvent.button.toString())
                    InputEventData(
                        type = KEY_UP,
                        keyCode = keyCode,
                        modifiers = networkEvent.modifiers,
                        timestamp = networkEvent.timestamp
                    )
                }

                else -> {
                    Timber.w("Unknown input event type: ${networkEvent.type}")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error converting network event")
            null
        }
    }

    private fun convertCoordinate(coord: Int, maxCoord: Int): Int {
        // Convert from 0-1000 range to actual screen coordinates
        return (coord * maxCoord) / 1000
    }

    private fun mapKeyToAndroid(key: String): Int {
        // Try exact match first
        ANDROID_KEY_MAP[key.lowercase()]?.let { return it }

        // Try to parse as character
        if (key.length == 1) {
            val char = key[0]
            when {
                char.isLetter() -> {
                    // A-Z mapping
                    return char.uppercaseChar() - 'A' + 29
                }
                char.isDigit() -> {
                    // 0-9 mapping
                    return char - '0' + 7
                }
                char == ' ' -> return 62 // Space
                char == '\n' -> return 66 // Enter
                char == '\t' -> return 61 // Tab
                char == '\b' -> return 67 // Backspace
            }
        }

        // Try numeric code
        return key.toIntOrNull() ?: 0
    }

    private fun queueInputEvent(event: InputEventData) {
        // Limit queue size to prevent memory buildup
        if (inputEventQueue.size >= MAX_QUEUE_SIZE) {
            inputEventQueue.poll()
            eventsDropped++
        }

        inputEventQueue.offer(event)

        // Start processing if not already running
        if (isProcessingEvents.compareAndSet(false, true)) {
            mainHandler.post {
                processEventQueue()
            }
        }
    }

    private fun processEventQueue() {
        while (!inputEventQueue.isEmpty()) {
            val event = inputEventQueue.poll() ?: break

            try {
                when (event.type) {
                    TOUCH_DOWN, TOUCH_UP, TOUCH_MOVE -> {
                        injectTouchEvent(event)
                    }
                    MOUSE_DOWN, MOUSE_UP, MOUSE_MOVE -> {
                        injectMouseEvent(event)
                    }
                    MOUSE_WHEEL -> {
                        injectMouseWheelEvent(event)
                    }
                    KEY_DOWN, KEY_UP -> {
                        injectKeyEvent(event)
                    }
                }

                eventsInjected++
            } catch (e: Exception) {
                Timber.e(e, "Error processing input event")
            }
        }

        isProcessingEvents.set(false)
    }

    private fun injectTouchEvent(event: InputEventData) {
        if (hasAccessibilityPermission) {
            injectTouchEventWithAccessibility(event)
        } else {
            injectTouchEventWithInputMethod(event)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun injectTouchEventWithAccessibility(event: InputEventData) {
        try {
            val motionEvent = MotionEvent.obtain(
                System.currentTimeMillis() - event.timestamp,
                event.timestamp,
                when (event.type) {
                    TOUCH_DOWN -> MotionEvent.ACTION_DOWN
                    TOUCH_UP -> MotionEvent.ACTION_UP
                    TOUCH_MOVE -> MotionEvent.ACTION_MOVE
                    else -> MotionEvent.ACTION_CANCEL
                },
                event.x.toFloat(),
                event.y.toFloat(),
                0.0f
            )

            motionEvent.source = InputDevice.SOURCE_TOUCHSCREEN
            motionEvent.displayId = android.view.Display.DEFAULT_DISPLAY

            // Send to accessibility service
            sendToAccessibilityService(motionEvent)

            motionEvent.recycle()
        } catch (e: Exception) {
            Timber.e(e, "Error injecting touch event with accessibility")
        }
    }

    private fun injectTouchEventWithInputMethod(event: InputEventData) {
        try {
            // Fallback method using Instrumentation
            val downTime = System.currentTimeMillis()
            val eventTime = event.timestamp

            val motionEvent = MotionEvent.obtain(
                downTime,
                eventTime,
                when (event.type) {
                    TOUCH_DOWN -> MotionEvent.ACTION_DOWN
                    TOUCH_UP -> MotionEvent.ACTION_UP
                    TOUCH_MOVE -> MotionEvent.ACTION_MOVE
                    else -> MotionEvent.ACTION_CANCEL
                },
                event.x.toFloat(),
                event.y.toFloat(),
                0.0f
            )

            // Use InputManager to inject the event
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager
            inputManager.injectInputEvent(motionEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)

            motionEvent.recycle()
        } catch (e: Exception) {
            Timber.e(e, "Error injecting touch event with input method")
        }
    }

    private fun injectMouseEvent(event: InputEventData) {
        // Similar to touch events but with different source
        if (hasAccessibilityPermission) {
            injectMouseEventWithAccessibility(event)
        } else {
            injectMouseEventWithInputMethod(event)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun injectMouseEventWithAccessibility(event: InputEventData) {
        try {
            val motionEvent = MotionEvent.obtain(
                System.currentTimeMillis() - event.timestamp,
                event.timestamp,
                when (event.type) {
                    MOUSE_DOWN -> MotionEvent.ACTION_DOWN
                    MOUSE_UP -> MotionEvent.ACTION_UP
                    MOUSE_MOVE -> MotionEvent.ACTION_MOVE
                    else -> MotionEvent.ACTION_CANCEL
                },
                event.x.toFloat(),
                event.y.toFloat(),
                0.0f
            )

            motionEvent.source = InputDevice.SOURCE_MOUSE
            motionEvent.buttonState = when (event.button) {
                1 -> MotionEvent.BUTTON_PRIMARY
                2 -> MotionEvent.BUTTON_SECONDARY
                3 -> MotionEvent.BUTTON_TERTIARY
                else -> 0
            }

            sendToAccessibilityService(motionEvent)
            motionEvent.recycle()
        } catch (e: Exception) {
            Timber.e(e, "Error injecting mouse event with accessibility")
        }
    }

    private fun injectMouseEventWithInputMethod(event: InputEventData) {
        try {
            // Convert touch to mouse event for older Android versions
            injectTouchEventWithInputMethod(event)
        } catch (e: Exception) {
            Timber.e(e, "Error injecting mouse event with input method")
        }
    }

    private fun injectMouseWheelEvent(event: InputEventData) {
        try {
            // Handle scroll events
            val scrollDelta = event.button // X scroll in button, Y scroll in modifiers

            if (hasAccessibilityPermission) {
                sendScrollEventToAccessibility(event.x, event.y, scrollDelta, event.modifiers)
            } else {
                // Fallback: send volume keys as scroll
                if (event.modifiers > 0) {
                    injectKeyCode(KeyEvent.KEYCODE_VOLUME_UP)
                } else {
                    injectKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error injecting mouse wheel event")
        }
    }

    private fun injectKeyEvent(event: InputEventData) {
        try {
            if (hasAccessibilityPermission) {
                injectKeyEventWithAccessibility(event)
            } else {
                injectKeyCode(event.keyCode, event.type == KEY_DOWN)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error injecting key event")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun injectKeyEventWithAccessibility(event: InputEventData) {
        try {
            val keyEvent = android.view.KeyEvent(
                event.timestamp,
                when (event.type) {
                    KEY_DOWN -> android.view.KeyEvent.ACTION_DOWN
                    KEY_UP -> android.view.KeyEvent.ACTION_UP
                    else -> android.view.KeyEvent.ACTION_UP
                },
                event.keyCode,
                0,
                event.modifiers
            )

            sendToAccessibilityService(keyEvent)
        } catch (e: Exception) {
            Timber.e(e, "Error injecting key event with accessibility")
        }
    }

    private fun injectKeyCode(keyCode: Int, isDown: Boolean = true) {
        try {
            val keyEvent = android.view.KeyEvent(
                System.currentTimeMillis(),
                if (isDown) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP,
                keyCode,
                0
            )

            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager
            inputManager.injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        } catch (e: Exception) {
            Timber.e(e, "Error injecting key code")
        }
    }

    private fun sendToAccessibilityService(inputEvent: InputEvent) {
        // Send event to accessibility service
        // This would be implemented via IPC or local binding to the accessibility service
        Timber.d("Sending input event to accessibility service: ${inputEvent.javaClass.simpleName}")
    }

    private fun sendScrollEventToAccessibility(x: Int, y: Int, deltaX: Int, deltaY: Int) {
        // Send scroll event to accessibility service
        Timber.d("Sending scroll event: ($x, $y) delta: ($deltaX, $deltaY)")
    }

    // Public API
    fun getStats(): Pair<Long, Long> {
        return Pair(eventsInjected, eventsDropped)
    }

    fun clearQueue() {
        inputEventQueue.clear()
        eventsDropped += inputEventQueue.size
    }

    fun refreshPermissions() {
        checkPermissions()
    }

    fun requiresAccessibilityPermission(): Boolean {
        return !hasAccessibilityPermission && !useInputMethod
    }

    fun requestAccessibilityPermission() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)

            Toast.makeText(context, "Please enable ScreenFlow Accessibility Service", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Timber.e(e, "Error opening accessibility settings")
        }
    }

    fun cleanup() {
        isInitialized.set(false)
        clearQueue()
        Timber.d("InputInjector cleaned up")
    }
}