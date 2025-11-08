#include <SDL2/SDL.h>
#include <SDL2/SDL_opengl.h>
#include <iostream>
#include <memory>
#include <thread>
#include <chrono>
#include <atomic>

#include "network/ConnectionManager.h"
#include "video/VideoDecoder.h"
#include "input/InputHandler.h"
#include "config/PairingManager.h"
#include "common/Types.h"

namespace ScreenFlow {

class ScreenFlowApp {
public:
    ScreenFlowApp() :
        window_(nullptr),
        renderer_(nullptr),
        texture_(nullptr),
        isRunning_(false),
        isFullscreen_(false),
        showStats_(false),
        lastFrameTime_(0),
        frameCount_(0),
        fps_(0) {

        // Initialize component managers
        connectionManager_ = std::make_unique<ConnectionManager>();
        videoDecoder_ = std::make_unique<VideoDecoder>();
        inputHandler_ = std::make_unique<InputHandler>();
        pairingManager_ = std::make_unique<PairingManager>();
    }

    ~ScreenFlowApp() {
        cleanup();
    }

    bool initialize() {
        // Initialize SDL
        if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_TIMER | SDL_INIT_EVENTS) != 0) {
            std::cerr << "SDL initialization failed: " << SDL_GetError() << std::endl;
            return false;
        }

        // Create window
        window_ = SDL_CreateWindow(
            "ScreenFlow - Remote Phone Control",
            SDL_WINDOWPOS_CENTERED,
            SDL_WINDOWPOS_CENTERED,
            1280, 720,
            SDL_WINDOW_RESIZABLE | SDL_WINDOW_OPENGL
        );

        if (!window_) {
            std::cerr << "Window creation failed: " << SDL_GetError() << std::endl;
            return false;
        }

        // Create renderer
        renderer_ = SDL_CreateRenderer(
            window_,
            -1,
            SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC
        );

        if (!renderer_) {
            std::cerr << "Renderer creation failed: " << SDL_GetError() << std::endl;
            return false;
        }

        // Set renderer blend mode for transparency
        SDL_SetRenderDrawBlendMode(renderer_, SDL_BLENDMODE_BLEND);

        // Initialize component managers
        if (!connectionManager_->initialize()) {
            std::cerr << "Failed to initialize connection manager" << std::endl;
            return false;
        }

        if (!videoDecoder_->initialize()) {
            std::cerr << "Failed to initialize video decoder" << std::endl;
            return false;
        }

        if (!inputHandler_->initialize()) {
            std::cerr << "Failed to initialize input handler" << std::endl;
            return false;
        }

        // Start background threads
        std::thread networkThread(&ConnectionManager::run, connectionManager_.get());
        networkThread.detach();

        std::thread videoThread(&VideoDecoder::run, videoDecoder_.get());
        videoThread.detach();

        // Start pairing process
        pairingManager_->startPairing();

        isRunning_ = true;
        return true;
    }

    void run() {
        const int targetFPS = 60;
        const int frameDelay = 1000 / targetFPS;
        Uint32 frameStart;
        int frameTime;

        while (isRunning_) {
            frameStart = SDL_GetTicks();

            handleEvents();
            update();
            render();

            frameTime = SDL_GetTicks() - frameStart;
            if (frameDelay > frameTime) {
                SDL_Delay(frameDelay - frameTime);
            }
        }
    }

private:
    void handleEvents() {
        SDL_Event event;
        while (SDL_PollEvent(&event)) {
            switch (event.type) {
                case SDL_QUIT:
                    isRunning_ = false;
                    break;

                case SDL_KEYDOWN:
                    handleKeyDown(event.key);
                    break;

                case SDL_KEYUP:
                    handleKeyUp(event.key);
                    break;

                case SDL_MOUSEBUTTONDOWN:
                    handleMouseDown(event.button);
                    break;

                case SDL_MOUSEBUTTONUP:
                    handleMouseUp(event.button);
                    break;

                case SDL_MOUSEMOTION:
                    handleMouseMove(event.motion);
                    break;

                case SDL_MOUSEWHEEL:
                    handleMouseWheel(event.wheel);
                    break;

                case SDL_WINDOWEVENT:
                    handleWindowEvent(event.window);
                    break;
            }
        }
    }

    void handleKeyDown(const SDL_KeyboardEvent& event) {
        switch (event.keysym.sym) {
            case SDLK_ESCAPE:
                if (connectionManager_->isConnected()) {
                    connectionManager_->disconnect();
                } else {
                    isRunning_ = false;
                }
                break;

            case SDLK_F11:
                toggleFullscreen();
                break;

            case SDLK_F1:
                showStats_ = !showStats_;
                break;

            default:
                if (connectionManager_->isConnected()) {
                    inputHandler_->handleKeyEvent(event);
                }
                break;
        }
    }

    void handleKeyUp(const SDL_KeyboardEvent& event) {
        if (connectionManager_->isConnected()) {
            inputHandler_->handleKeyEvent(event);
        }
    }

    void handleMouseDown(const SDL_MouseButtonEvent& event) {
        if (connectionManager_->isConnected()) {
            inputHandler_->handleMouseEvent(event);
        }
    }

    void handleMouseUp(const SDL_MouseButtonEvent& event) {
        if (connectionManager_->isConnected()) {
            inputHandler_->handleMouseEvent(event);
        }
    }

    void handleMouseMove(const SDL_MouseMotionEvent& event) {
        if (connectionManager_->isConnected()) {
            inputHandler_->handleMouseEvent(event);
        }
    }

    void handleMouseWheel(const SDL_MouseWheelEvent& event) {
        if (connectionManager_->isConnected()) {
            inputHandler_->handleMouseWheelEvent(event);
        }
    }

    void handleWindowEvent(const SDL_WindowEvent& event) {
        switch (event.event) {
            case SDL_WINDOWEVENT_RESIZED:
                handleWindowResize(event.data1, event.data2);
                break;

            case SDL_WINDOWEVENT_FOCUS_GAINED:
                inputHandler_->setWindowFocused(true);
                break;

            case SDL_WINDOWEVENT_FOCUS_LOST:
                inputHandler_->setWindowFocused(false);
                break;
        }
    }

    void handleWindowResize(int width, int height) {
        // Update video decoder with new dimensions
        videoDecoder_->setOutputDimensions(width, height);

        // Recreate texture if needed
        if (texture_) {
            SDL_DestroyTexture(texture_);
            texture_ = nullptr;
        }
    }

    void toggleFullscreen() {
        isFullscreen_ = !isFullscreen_;
        SDL_SetWindowFullscreen(window_, isFullscreen_ ? SDL_WINDOW_FULLSCREEN_DESKTOP : 0);

        if (!isFullscreen_) {
            SDL_SetWindowPosition(window_, SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED);
        }
    }

    void update() {
        // Update FPS counter
        Uint32 currentTime = SDL_GetTicks();
        frameCount_++;

        if (currentTime - lastFrameTime_ >= 1000) {
            fps_ = frameCount_;
            frameCount_ = 0;
            lastFrameTime_ = currentTime;
        }

        // Process video frames
        auto frame = videoDecoder_->getNextFrame();
        if (frame && !frame->data.empty()) {
            updateTexture(frame);
        }

        // Send input events to connected device
        if (connectionManager_->isConnected()) {
            auto inputEvents = inputHandler_->getPendingEvents();
            for (const auto& event : inputEvents) {
                connectionManager_->sendInputEvent(event);
            }
        }
    }

    void updateTexture(const std::shared_ptr<VideoFrame>& frame) {
        if (!texture_) {
            texture_ = SDL_CreateTexture(
                renderer_,
                SDL_PIXELFORMAT_IYUV,
                SDL_TEXTUREACCESS_STREAMING,
                frame->width,
                frame->height
            );
        }

        if (texture_) {
            SDL_UpdateYUVTexture(
                texture_,
                nullptr,
                frame->data.data(),
                frame->width,
                frame->data.data() + frame->width * frame->height,
                frame->width / 2,
                frame->data.data() + frame->width * frame->height * 5 / 4,
                frame->width / 2
            );
        }
    }

    void render() {
        // Clear screen
        SDL_SetRenderDrawColor(renderer_, 20, 20, 30, 255);
        SDL_RenderClear(renderer_);

        // Get window dimensions
        int windowWidth, windowHeight;
        SDL_GetWindowSize(window_, &windowWidth, &windowHeight);

        if (texture_) {
            // Calculate aspect ratio and position
            int textureWidth, textureHeight;
            SDL_QueryTexture(texture_, nullptr, nullptr, &textureWidth, &textureHeight);

            float aspectRatio = (float)textureWidth / textureHeight;
            float windowAspectRatio = (float)windowWidth / windowHeight;

            int renderWidth, renderHeight;
            int renderX, renderY;

            if (aspectRatio > windowAspectRatio) {
                renderWidth = windowWidth;
                renderHeight = (int)(windowWidth / aspectRatio);
                renderX = 0;
                renderY = (windowHeight - renderHeight) / 2;
            } else {
                renderHeight = windowHeight;
                renderWidth = (int)(windowHeight * aspectRatio);
                renderX = (windowWidth - renderWidth) / 2;
                renderY = 0;
            }

            SDL_Rect renderRect = {renderX, renderY, renderWidth, renderHeight};
            SDL_RenderCopy(renderer_, texture_, nullptr, &renderRect);
        } else {
            // Show pairing information when not connected
            renderPairingInfo(windowWidth, windowHeight);
        }

        // Render stats overlay
        if (showStats_) {
            renderStats(windowWidth, windowHeight);
        }

        SDL_RenderPresent(renderer_);
    }

    void renderPairingInfo(int windowWidth, int windowHeight) {
        auto pairingInfo = pairingManager_->getPairingInfo();

        // Render QR code placeholder (would need actual QR code generation)
        SDL_SetRenderDrawColor(renderer_, 255, 255, 255, 255);
        SDL_Rect qrRect = {windowWidth/2 - 150, windowHeight/2 - 200, 300, 300};
        SDL_RenderDrawRect(renderer_, &qrRect);

        // Render pairing instructions
        // Note: In a real implementation, you would use a text rendering library
        // like SDL_ttf to render actual text

        // Render manual code placeholder
        SDL_SetRenderDrawColor(renderer_, 200, 200, 200, 255);
        SDL_Rect codeRect = {windowWidth/2 - 100, windowHeight/2 + 120, 200, 40};
        SDL_RenderDrawRect(renderer_, &codeRect);
    }

    void renderStats(int windowWidth, int windowHeight) {
        // Render FPS and connection stats
        // Note: In a real implementation, you would use a text rendering library

        SDL_SetRenderDrawColor(renderer_, 0, 0, 0, 180);
        SDL_Rect statsRect = {10, 10, 200, 80};
        SDL_RenderFillRect(renderer_, &statsRect);

        SDL_SetRenderDrawColor(renderer_, 255, 255, 255, 255);
        SDL_RenderDrawRect(renderer_, &statsRect);
    }

    void cleanup() {
        if (texture_) {
            SDL_DestroyTexture(texture_);
            texture_ = nullptr;
        }

        if (renderer_) {
            SDL_DestroyRenderer(renderer_);
            renderer_ = nullptr;
        }

        if (window_) {
            SDL_DestroyWindow(window_);
            window_ = nullptr;
        }

        SDL_Quit();
    }

    // SDL components
    SDL_Window* window_;
    SDL_Renderer* renderer_;
    SDL_Texture* texture_;

    // Application state
    std::atomic<bool> isRunning_;
    bool isFullscreen_;
    bool showStats_;

    // Component managers
    std::unique_ptr<ConnectionManager> connectionManager_;
    std::unique_ptr<VideoDecoder> videoDecoder_;
    std::unique_ptr<InputHandler> inputHandler_;
    std::unique_ptr<PairingManager> pairingManager_;

    // Performance tracking
    Uint32 lastFrameTime_;
    int frameCount_;
    int fps_;
};

} // namespace ScreenFlow

int main(int argc, char* argv[]) {
    try {
        ScreenFlow::ScreenFlowApp app;

        if (!app.initialize()) {
            std::cerr << "Failed to initialize ScreenFlow application" << std::endl;
            return -1;
        }

        app.run();

        return 0;
    } catch (const std::exception& e) {
        std::cerr << "Application error: " << e.what() << std::endl;
        return -1;
    }
}