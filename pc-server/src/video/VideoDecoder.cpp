#include "VideoDecoder.h"
#include <iostream>
#include <chrono>
#include <algorithm>

namespace ScreenFlow {

// Quality presets for adaptive streaming
const VideoDecoder::QualitySettings VideoDecoder::QUALITY_PRESETS[4] = {
    {480,  360,  1000000, 15},   // LOW
    {1280, 720,  2500000, 30},   // MEDIUM
    {1920, 1080, 5000000, 60},   // HIGH
    {1280, 720,  2000000, 30}    // AUTO (adaptive)
};

VideoDecoder::VideoDecoder() :
    codecContext_(nullptr),
    parserContext_(nullptr),
    decodedFrame_(nullptr),
    convertedFrame_(nullptr),
    swsContext_(nullptr),
    packet_(nullptr),
    isRunning_(false),
    outputWidth_(1280),
    outputHeight_(720),
    currentQuality_(VideoQuality::MEDIUM),
    hardwareAcceleration_(true),
    frameCount_(0),
    droppedFrames_(0),
    currentBitrate_(0),
    currentFPS_(0),
    lastStatsTime_(0),
    framesSinceLastStats_(0),
    codecInitialized_(false),
    parserInitialized_(false),
    scalerInitialized_(false),
    hwDeviceCtx_(nullptr),
    hwPixelFormat_(AV_PIX_FMT_NONE),
    hwInitialized_(false) {

    // Initialize FFmpeg network components
    avformat_network_init();
}

VideoDecoder::~VideoDecoder() {
    stop();
    cleanupFFmpeg();
}

bool VideoDecoder::initialize() {
    try {
        std::cout << "Initializing video decoder..." << std::endl;

        if (!initializeFFmpeg()) {
            std::cerr << "Failed to initialize FFmpeg" << std::endl;
            return false;
        }

        if (!initializeCodec(AV_CODEC_ID_H264)) {
            std::cerr << "Failed to initialize H.264 codec" << std::endl;
            return false;
        }

        if (!initializeScaler()) {
            std::cerr << "Failed to initialize frame scaler" << std::endl;
            return false;
        }

        if (!allocateFrameBuffers()) {
            std::cerr << "Failed to allocate frame buffers" << std::endl;
            return false;
        }

        setupDecoderParameters();
        isRunning_ = true;

        std::cout << "Video decoder initialized successfully" << std::endl;
        return true;
    } catch (const std::exception& e) {
        std::cerr << "Video decoder initialization failed: " << e.what() << std::endl;
        return false;
    }
}

void VideoDecoder::run() {
    if (!isRunning_) {
        return;
    }

    // Start decoder thread
    decoderThread_ = std::thread(&VideoDecoder::decoderLoop, this);

    // Start statistics thread
    statsThread_ = std::thread(&VideoDecoder::statsLoop, this);

    std::cout << "Video decoder threads started" << std::endl;
}

void VideoDecoder::stop() {
    isRunning_ = false;

    // Wake up any waiting threads
    packetCondition_.notify_all();
    frameCondition_.notify_all();

    if (decoderThread_.joinable()) {
        decoderThread_.join();
    }

    if (statsThread_.joinable()) {
        statsThread_.join();
    }
}

bool VideoDecoder::processH264Packet(const std::vector<uint8_t>& packet) {
    if (!isRunning_ || packet.empty()) {
        return false;
    }

    H264Packet h264Packet;
    h264Packet.data = packet;
    h264Packet.timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();

    {
        std::lock_guard<std::mutex> lock(packetMutex_);
        packetQueue_.push(h264Packet);
    }

    packetCondition_.notify_one();
    return true;
}

std::shared_ptr<VideoFrame> VideoDecoder::getNextFrame() {
    std::lock_guard<std::mutex> lock(frameMutex_);

    if (frameQueue_.empty()) {
        return nullptr;
    }

    auto frame = frameQueue_.front();
    frameQueue_.pop();
    return frame;
}

bool VideoDecoder::hasFrames() const {
    std::lock_guard<std::mutex> lock(frameMutex_);
    return !frameQueue_.empty();
}

void VideoDecoder::setOutputDimensions(int width, int height) {
    outputWidth_ = width;
    outputHeight_ = height;

    // Reinitialize scaler with new dimensions
    if (swsContext_) {
        sws_freeContext(swsContext_);
        swsContext_ = nullptr;
    }
    initializeScaler();
}

void VideoDecoder::setQuality(VideoQuality quality) {
    currentQuality_ = quality;
    setupDecoderParameters();
}

void VideoDecoder::enableHardwareAcceleration(bool enable) {
    hardwareAcceleration_ = enable;

    if (enable && !hwInitialized_) {
        // Initialize hardware acceleration
        initializeHardwareAcceleration();
    } else if (!enable && hwInitialized_) {
        // Disable hardware acceleration
        cleanupHardwareAcceleration();
    }
}

int VideoDecoder::getFPS() const {
    return currentFPS_.load();
}

int VideoDecoder::getBitrate() const {
    return currentBitrate_.load();
}

int VideoDecoder::getDroppedFrames() const {
    return droppedFrames_.load();
}

bool VideoDecoder::initializeFFmpeg() {
    // Register all codecs and formats
    avcodec_register_all();
    av_register_all();

    // Create H.264 parser
    parserContext_ = av_parser_init(AV_CODEC_ID_H264);
    if (!parserContext_) {
        logFFmpegError(0, "Failed to create H.264 parser");
        return false;
    }
    parserInitialized_ = true;

    // Create packet
    packet_ = av_packet_alloc();
    if (!packet_) {
        logFFmpegError(0, "Failed to allocate packet");
        return false;
    }

    std::cout << "FFmpeg components initialized" << std::endl;
    return true;
}

bool VideoDecoder::initializeCodec(AVCodecID codecId) {
    // Find H.264 decoder
    const AVCodec* codec = avcodec_find_decoder(codecId);
    if (!codec) {
        logFFmpegError(0, "H.264 decoder not found");
        return false;
    }

    // Allocate codec context
    codecContext_ = avcodec_alloc_context3(codec);
    if (!codecContext_) {
        logFFmpegError(0, "Failed to allocate codec context");
        return false;
    }

    // Set codec parameters
    codecContext_->codec_id = codecId;
    codecContext_->codec_type = AVMEDIA_TYPE_VIDEO;
    codecContext_->thread_count = std::max(1, std::thread::hardware_concurrency());
    codecContext_->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;

    // Enable hardware acceleration if requested
    if (hardwareAcceleration_) {
        initializeHardwareAcceleration();
    }

    // Open codec
    int ret = avcodec_open2(codecContext_, codec, nullptr);
    if (ret < 0) {
        logFFmpegError(ret, "Failed to open codec");
        return false;
    }

    codecInitialized_ = true;
    std::cout << "H.264 codec initialized" << std::endl;
    return true;
}

bool VideoDecoder::initializeScaler() {
    if (!codecContext_) {
        return false;
    }

    // Get source format
    enum AVPixelFormat srcFormat = codecContext_->pix_fmt;
    if (srcFormat == AV_PIX_FMT_NONE) {
        srcFormat = AV_PIX_FMT_YUV420P; // Default assumption
    }

    // Create scaling context
    swsContext_ = sws_getContext(
        codecContext_->width, codecContext_->height, srcFormat,
        outputWidth_, outputHeight_, AV_PIX_FMT_YUV420P,
        SWS_BILINEAR, nullptr, nullptr, nullptr
    );

    if (!swsContext_) {
        logFFmpegError(0, "Failed to create scaling context");
        return false;
    }

    scalerInitialized_ = true;
    std::cout << "Frame scaler initialized for " << outputWidth_ << "x" << outputHeight_ << std::endl;
    return true;
}

bool VideoDecoder::allocateFrameBuffers() {
    // Allocate decoded frame
    decodedFrame_ = av_frame_alloc();
    if (!decodedFrame_) {
        logFFmpegError(0, "Failed to allocate decoded frame");
        return false;
    }

    // Allocate converted frame
    convertedFrame_ = av_frame_alloc();
    if (!convertedFrame_) {
        logFFmpegError(0, "Failed to allocate converted frame");
        return false;
    }

    // Set converted frame parameters
    convertedFrame_->format = AV_PIX_FMT_YUV420P;
    convertedFrame_->width = outputWidth_;
    convertedFrame_->height = outputHeight_;

    int ret = av_frame_get_buffer(convertedFrame_, 0);
    if (ret < 0) {
        logFFmpegError(ret, "Failed to allocate converted frame buffer");
        return false;
    }

    std::cout << "Frame buffers allocated" << std::endl;
    return true;
}

void VideoDecoder::cleanupFFmpeg() {
    deallocateFrameBuffers();

    if (swsContext_) {
        sws_freeContext(swsContext_);
        swsContext_ = nullptr;
    }

    if (codecContext_) {
        avcodec_free_context(&codecContext_);
        codecContext_ = nullptr;
    }

    if (parserContext_) {
        av_parser_close(parserContext_);
        parserContext_ = nullptr;
    }

    if (packet_) {
        av_packet_free(&packet_);
        packet_ = nullptr;
    }

    cleanupHardwareAcceleration();
}

void VideoDecoder::deallocateFrameBuffers() {
    if (decodedFrame_) {
        av_frame_free(&decodedFrame_);
        decodedFrame_ = nullptr;
    }

    if (convertedFrame_) {
        av_frame_free(&convertedFrame_);
        convertedFrame_ = nullptr;
    }
}

void VideoDecoder::setupDecoderParameters() {
    if (!codecContext_) {
        return;
    }

    const QualitySettings& settings = QUALITY_PRESETS[static_cast<int>(currentQuality_)];

    codecContext_->width = settings.maxWidth;
    codecContext_->height = settings.maxHeight;
    codecContext_->bit_rate = settings.targetBitrate;
    codecContext_->framerate = {settings.targetFPS, 1};
    codecContext_->time_base = {1, settings.targetFPS};

    std::cout << "Decoder parameters set: "
              << settings.maxWidth << "x" << settings.maxHeight << " @ "
              << settings.targetFPS << "fps, "
              << (settings.targetBitrate / 1000000.0) << "Mbps" << std::endl;
}

void VideoDecoder::decoderLoop() {
    std::cout << "Decoder thread started" << std::endl;

    while (isRunning_) {
        std::unique_lock<std::mutex> lock(packetMutex_);
        packetCondition_.wait(lock, [this] { return !packetQueue_.empty() || !isRunning_; });

        if (!isRunning_) {
            break;
        }

        if (packetQueue_.empty()) {
            continue;
        }

        H264Packet h264Packet = packetQueue_.front();
        packetQueue_.pop();
        lock.unlock();

        // Decode the packet
        if (!decodePacket(h264Packet.data)) {
            droppedFrames_++;
            std::cerr << "Failed to decode packet, dropped frames: " << droppedFrames_.load() << std::endl;
        }
    }

    std::cout << "Decoder thread stopped" << std::endl;
}

bool VideoDecoder::decodePacket(const std::vector<uint8_t>& packetData) {
    if (!codecContext_ || !parserContext_) {
        return false;
    }

    uint8_t* data = const_cast<uint8_t*>(packetData.data());
    int dataSize = packetData.size();
    int ret = 0;

    while (dataSize > 0) {
        ret = av_parser_parse2(
            parserContext_, codecContext_,
            &packet_->data, &packet_->size,
            data, dataSize,
            AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0
        );

        if (ret < 0) {
            logFFmpegError(ret, "Error while parsing H.264 packet");
            return false;
        }

        data += ret;
        dataSize -= ret;

        if (packet_->size > 0) {
            ret = avcodec_send_packet(codecContext_, packet_);
            if (ret < 0) {
                logFFmpegError(ret, "Error sending packet to decoder");
                continue;
            }

            while (ret >= 0) {
                ret = avcodec_receive_frame(codecContext_, decodedFrame_);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                    break;
                } else if (ret < 0) {
                    logFFmpegError(ret, "Error receiving frame from decoder");
                    return false;
                }

                // Successfully decoded a frame
                frameCount_++;
                framesSinceLastStats_++;

                // Convert frame to output format
                VideoFrame outputFrame;
                if (convertFrame(decodedFrame_, outputFrame)) {
                    // Add to frame queue
                    {
                        std::lock_guard<std::mutex> lock(frameMutex_);
                        frameQueue_.push(std::make_shared<VideoFrame>(outputFrame));

                        // Limit queue size to prevent memory buildup
                        while (frameQueue_.size() > 30) {
                            frameQueue_.pop();
                            droppedFrames_++;
                        }
                    }
                    frameCondition_.notify_one();
                }
            }
        }
    }

    return true;
}

bool VideoDecoder::convertFrame(AVFrame* decodedFrame, VideoFrame& outputFrame) {
    if (!decodedFrame || !convertedFrame_ || !swsContext_) {
        return false;
    }

    // Scale the frame
    int ret = sws_scale(
        swsContext_,
        decodedFrame->data, decodedFrame->linesize,
        0, decodedFrame->height,
        convertedFrame_->data, convertedFrame_->linesize
    );

    if (ret <= 0) {
        logFFmpegError(ret, "Error scaling frame");
        return false;
    }

    // Copy frame data to output structure
    outputFrame.width = outputWidth_;
    outputFrame.height = outputHeight_;
    outputFrame.timestamp = decodedFrame->pts;
    outputFrame.isKeyFrame = (decodedFrame->key_frame == 1);

    // Calculate YUV420P data size
    int ySize = outputWidth_ * outputHeight_;
    int uvSize = ySize / 4;
    int totalSize = ySize + uvSize * 2;

    outputFrame.data.resize(totalSize);

    // Copy Y, U, V planes
    memcpy(outputFrame.data.data(), convertedFrame_->data[0], ySize);
    memcpy(outputFrame.data.data() + ySize, convertedFrame_->data[1], uvSize);
    memcpy(outputFrame.data.data() + ySize + uvSize, convertedFrame_->data[2], uvSize);

    return true;
}

void VideoDecoder::statsLoop() {
    std::cout << "Statistics thread started" << std::endl;

    lastStatsTime_ = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();

    while (isRunning_) {
        std::this_thread::sleep_for(std::chrono::seconds(1));

        if (!isRunning_) {
            break;
        }

        // Calculate statistics
        int64_t currentTime = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

        double timeDiff = (currentTime - lastStatsTime_) / 1000.0;
        if (timeDiff > 0) {
            currentFPS_.store(static_cast<int>(framesSinceLastStats_ / timeDiff));
        }

        currentBitrate_.store(calculateAverageBitrate());

        // Reset counters
        framesSinceLastStats_ = 0;
        lastStatsTime_ = currentTime;

        // Adjust quality if needed
        if (currentQuality_ == VideoQuality::AUTO) {
            adjustQualityBasedOnStats();
        }
    }

    std::cout << "Statistics thread stopped" << std::endl;
}

int VideoDecoder::calculateAverageBitrate() {
    // Simplified bitrate calculation
    // In a real implementation, this would track actual packet sizes
    return QUALITY_PRESETS[static_cast<int>(currentQuality_)].targetBitrate;
}

void VideoDecoder::adjustQualityBasedOnStats() {
    // Adaptive quality adjustment based on performance metrics
    int fps = currentFPS_.load();
    int dropped = droppedFrames_.load();

    if (fps < 15 || dropped > 10) {
        // Poor performance, reduce quality
        if (currentQuality_ == VideoQuality::HIGH) {
            setQuality(VideoQuality::MEDIUM);
            std::cout << "Reduced quality due to poor performance" << std::endl;
        } else if (currentQuality_ == VideoQuality::MEDIUM) {
            setQuality(VideoQuality::LOW);
            std::cout << "Reduced quality to low due to poor performance" << std::endl;
        }
    } else if (fps > 50 && dropped < 2) {
        // Good performance, can increase quality
        if (currentQuality_ == VideoQuality::LOW) {
            setQuality(VideoQuality::MEDIUM);
            std::cout << "Increased quality due to good performance" << std::endl;
        } else if (currentQuality_ == VideoQuality::MEDIUM) {
            setQuality(VideoQuality::HIGH);
            std::cout << "Increased quality to high due to good performance" << std::endl;
        }
    }
}

bool VideoDecoder::initializeHardwareAcceleration() {
    // Initialize hardware acceleration (simplified)
    // In a real implementation, this would detect and initialize available hardware decoders
    hwInitialized_ = true;
    hwPixelFormat_ = AV_PIX_FMT_CUDA; // or VAAPI, VDPAU, etc.
    return true;
}

void VideoDecoder::cleanupHardwareAcceleration() {
    if (hwDeviceCtx_) {
        av_buffer_unref(&hwDeviceCtx_);
        hwDeviceCtx_ = nullptr;
    }
    hwInitialized_ = false;
}

void VideoDecoder::logFFmpegError(int errorCode, const std::string& operation) {
    char errorBuffer[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(errorCode, errorBuffer, AV_ERROR_MAX_STRING_SIZE);
    std::cerr << "FFmpeg error in " << operation << ": " << errorBuffer << std::endl;
}

bool VideoDecoder::checkFFmpegError(int ret, const std::string& operation) {
    if (ret < 0) {
        logFFmpegError(ret, operation);
        return false;
    }
    return true;
}

} // namespace ScreenFlow