#pragma once

#include <memory>
#include <queue>
#include <mutex>
#include <thread>
#include <atomic>
#include <condition_variable>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libswscale/swscale.h>
#include <libavformat/avformat.h>
}

#include "../common/Types.h"

namespace ScreenFlow {

class VideoDecoder {
public:
    VideoDecoder();
    ~VideoDecoder();

    bool initialize();
    void run();
    void stop();

    // Frame processing
    bool processH264Packet(const std::vector<uint8_t>& packet);
    std::shared_ptr<VideoFrame> getNextFrame();
    bool hasFrames() const;

    // Configuration
    void setOutputDimensions(int width, int height);
    void setQuality(VideoQuality quality);
    void enableHardwareAcceleration(bool enable);

    // Statistics
    int getFPS() const;
    int getBitrate() const;
    int getDroppedFrames() const;

private:
    // FFmpeg initialization
    bool initializeFFmpeg();
    bool initializeCodec(AVCodecID codecId);
    bool initializeScaler();
    void cleanupFFmpeg();

    // Decoding
    bool decodePacket(const std::vector<uint8_t>& packet);
    bool convertFrame(AVFrame* decodedFrame, VideoFrame& outputFrame);
    void setupDecoderParameters();

    // Quality management
    void adjustQualityBasedOnStats();
    void resetDecoder();

    // Thread management
    void decoderLoop();
    void statsLoop();

    // FFmpeg components
    AVCodecContext* codecContext_;
    AVCodecParserContext* parserContext_;
    AVFrame* decodedFrame_;
    AVFrame* convertedFrame_;
    SwsContext* swsContext_;
    AVPacket* packet_;

    // Frame buffer
    std::queue<std::shared_ptr<VideoFrame>> frameQueue_;
    std::mutex frameMutex_;
    std::condition_variable frameCondition_;

    // Input packet queue
    struct H264Packet {
        std::vector<uint8_t> data;
        int64_t timestamp;
    };
    std::queue<H264Packet> packetQueue_;
    std::mutex packetMutex_;
    std::condition_variable packetCondition_;

    // Threading
    std::thread decoderThread_;
    std::thread statsThread_;
    std::atomic<bool> isRunning_;

    // Configuration
    int outputWidth_;
    int outputHeight_;
    VideoQuality currentQuality_;
    bool hardwareAcceleration_;

    // Statistics
    std::atomic<int> frameCount_;
    std::atomic<int> droppedFrames_;
    std::atomic<int> currentBitrate_;
    std::atomic<int> currentFPS_;
    int64_t lastStatsTime_;
    int64_t framesSinceLastStats_;

    // Decoder state
    bool codecInitialized_;
    bool parserInitialized_;
    bool scalerInitialized_;

    // Hardware acceleration
    AVBufferRef* hwDeviceCtx_;
    enum AVPixelFormat hwPixelFormat_;
    bool hwInitialized_;

    // Quality presets
    struct QualitySettings {
        int maxWidth;
        int maxHeight;
        int targetBitrate;
        int targetFPS;
    };

    static const QualitySettings QUALITY_PRESETS[4];

    // Error handling
    void logFFmpegError(int errorCode, const std::string& operation);
    bool checkFFmpegError(int ret, const std::string& operation);

    // Buffer management
    bool allocateFrameBuffers();
    void deallocateFrameBuffers();
    uint8_t* getFrameBuffer(int width, int height, enum AVPixelFormat format);
};

} // namespace ScreenFlow