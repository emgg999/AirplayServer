package com.fang.myapplication.player;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.fang.myapplication.model.NALPacket;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class VideoPlayer {

    private static final String TAG = "VideoPlayer";

    private int mVideoWidth = 1280;
    private int mVideoHeight = 720;
    private static final long DECODE_TIMEOUT_US = 0; // 零超时，立即返回
    private static final int RING_BUFFER_SIZE = 64; // 2 的幂次，方便取模

    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private MediaCodec mDecoder = null;
    private Surface mSurface = null;
    private AtomicBoolean mIsEnd = new AtomicBoolean(false);
    private volatile boolean mDecoderReady = false;

    // 环形缓冲区（无锁实现）
    private final NALPacket[] mRingBuffer = new NALPacket[RING_BUFFER_SIZE];
    private final AtomicInteger mWriteIndex = new AtomicInteger(0);
    private final AtomicInteger mReadIndex = new AtomicInteger(0);

    // 解码线程
    private HandlerThread mDecodeThread;
    private Handler mDecodeHandler;

    // 预分配的 NALPacket 对象池
    private final NALPacket[] mPacketPool = new NALPacket[RING_BUFFER_SIZE * 2];
    private final AtomicInteger mPoolIndex = new AtomicInteger(0);


    public VideoPlayer(Surface surface) {
        mSurface = surface;
        initPacketPool();
        for (int i = 0; i < RING_BUFFER_SIZE; i++) {
            mRingBuffer[i] = getPacketFromPool();
        }
        startDecodeThread();
        initDecoder();
    }

    private void initPacketPool() {
        for (int i = 0; i < mPacketPool.length; i++) {
            mPacketPool[i] = new NALPacket();
        }
    }

    private NALPacket getPacketFromPool() {
        int index = mPoolIndex.getAndIncrement() % mPacketPool.length;
        return mPacketPool[index];
    }

    private void startDecodeThread() {
        mDecodeThread = new HandlerThread("VideoDecodeThread");
        mDecodeThread.start();
        mDecodeHandler = new Handler(mDecodeThread.getLooper());
    }

    private void stopDecodeThread() {
        if (mDecodeThread != null) {
            mDecodeThread.quitSafely();
            try {
                mDecodeThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping decode thread", e);
            }
            mDecodeThread = null;
            mDecodeHandler = null;
        }
    }


    private void initDecoder() {
        try {
            // 2. 初始化MediaCodec
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mVideoWidth, mVideoHeight);
            format.setInteger(MediaFormat.KEY_PRIORITY, 1); // 最高优先级
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_MAX_WIDTH, mVideoWidth);
            format.setInteger(MediaFormat.KEY_MAX_HEIGHT, mVideoHeight);
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
            // 选择硬件编解码器
            String codecName = getHardwareCodecName(MediaFormat.MIMETYPE_VIDEO_AVC, false);
            if (codecName != null) {
                Log.d(TAG, "Using hardware codec: " + codecName);
                mDecoder = MediaCodec.createByCodecName(codecName);
            } else {
                Log.w(TAG, "Hardware codec not found, using software codec");
                mDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            }
            mDecoder.configure(format, mSurface, null, 0);
            mDecoder.start();
            mDecoderReady = true;
            Log.d(TAG, "Decoder initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to init decoder", e);
            mDecoderReady = false;
        }
    }

    private String getHardwareCodecName(String mimeType, boolean isEncoder) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (codecInfo.isEncoder() != isEncoder) {
                continue;
            }
            if (codecInfo.getName().startsWith("OMX.")) {
                continue; // 跳过软件编解码器
            }
            try {
                MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
                if (capabilities != null) {
                    return codecInfo.getName();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting codec capabilities", e);
            }
        }
        return null;
    }

    public void doDecode(NALPacket nalPacket) {
        if (mDecoder == null || !mDecoderReady) {
            return;
        }
        // 快速路径：直接写入环形缓冲区
        int writeIdx = mWriteIndex.getAndIncrement() & (RING_BUFFER_SIZE - 1);
        NALPacket packet = mRingBuffer[writeIdx];
        // 复用对象，避免内存分配
        packet.nalData = nalPacket.nalData;
        packet.nalType = nalPacket.nalType;
        packet.pts = nalPacket.pts;
        // 检查是否丢帧
        int readIdx = mReadIndex.get();
        if (((mWriteIndex.get() - readIdx) > RING_BUFFER_SIZE - 2)) {
            // 缓冲区快满了，跳过一些帧
            mReadIndex.incrementAndGet();
        }
        // 触发解码
        if (mDecodeHandler != null) {
            mDecodeHandler.post(this::processDecodeQueue);
        }
//        try {
//            ByteBuffer[] inputBuffers = mDecoder.getInputBuffers();
//            int inputBufIndex = mDecoder.dequeueInputBuffer(0); // 0超时，立即返回
//            if (inputBufIndex >= 0) {
//                ByteBuffer inputBuf = inputBuffers[inputBufIndex];
//                inputBuf.put(nalPacket.nalData);
//                mDecoder.queueInputBuffer(inputBufIndex, 0, nalPacket.nalData.length, nalPacket.pts, 0);
//            }
//            int outputBufferIndex = mDecoder.dequeueOutputBuffer(mBufferInfo, 0);
//            if (outputBufferIndex >= 0) {
//                mDecoder.releaseOutputBuffer(outputBufferIndex, true);
//            }
//        } catch (Exception e) {
//            Log.e(TAG, "Decode error", e);
//        }
    }

    private void processDecodeQueue() {
        if (mDecoder == null || !mDecoderReady) {
            return;
        }
        int readIdx = mReadIndex.get() & (RING_BUFFER_SIZE - 1);
        int writeIdx = mWriteIndex.get() & (RING_BUFFER_SIZE - 1);

        // 如果有数据可读
        if (mReadIndex.get() != mWriteIndex.get()) {
            NALPacket packet = mRingBuffer[readIdx];

            try {
                // 输入数据到解码器
                int inputBufIndex = mDecoder.dequeueInputBuffer(DECODE_TIMEOUT_US);
                if (inputBufIndex >= 0) {
                    ByteBuffer inputBuf = mDecoder.getInputBuffer(inputBufIndex);
                    if (inputBuf != null) {
                        inputBuf.clear();
                        inputBuf.put(packet.nalData);
                        int flags = (packet.nalType == 5) ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        mDecoder.queueInputBuffer(inputBufIndex, 0, packet.nalData.length, packet.pts, flags);
                    }
                }

                // 处理输出数据
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outputBufferIndex = mDecoder.dequeueOutputBuffer(info, DECODE_TIMEOUT_US);
                while (outputBufferIndex >= 0) {
                    if (info.size != 0) {
                        mDecoder.releaseOutputBuffer(outputBufferIndex, true);
                    } else {
                        mDecoder.releaseOutputBuffer(outputBufferIndex, false);
                    }
                    outputBufferIndex = mDecoder.dequeueOutputBuffer(info, DECODE_TIMEOUT_US);
                }

                // 读取完成后更新读索引
                mReadIndex.incrementAndGet();

            } catch (Exception e) {
                Log.e(TAG, "Decode error", e);
            }
        }
    }

    private void releaseDecoder() {
        if (mDecoder != null) {
            try {
                mDecoder.stop();
                mDecoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing decoder", e);
            }
            mDecoder = null;
        }
        mDecoderReady = false;
    }

    public void stopPlayback() {
        mIsEnd.set(true);
        stopDecodeThread();
        releaseDecoder();
        mWriteIndex.set(0);
        mReadIndex.set(0);
    }
}