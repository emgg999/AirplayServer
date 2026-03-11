package com.fang.myapplication.player;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import com.fang.myapplication.model.NALPacket;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VideoPlayer extends Thread {

    private static final String TAG = "VideoPlayer";

    private String mMimeType = "video/avc";
    private int mVideoWidth  = 540;
    private int mVideoHeight = 960;
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private MediaCodec mDecoder = null;
    private Surface mSurface = null;
    private boolean mIsEnd = false;
    private List<NALPacket> mListBuffer = Collections.synchronizedList(new ArrayList<NALPacket>());

    private static final int MAX_BUFFER_SIZE = 5; // 减少缓冲队列大小
    private static final int DECODE_TIMEOUT = 5; // 减少等待超时时间
    

    public VideoPlayer(Surface surface) {
        mSurface = surface;
    }

    public void initDecoder() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(mMimeType, mVideoWidth, mVideoHeight);
            // 优化解码器配置
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            format.setInteger(MediaFormat.KEY_LATENCY, 0);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
            // 添加关键帧间隔设置
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            // 启用自适应播放
            format.setInteger(MediaFormat.KEY_MAX_WIDTH, mVideoWidth);
            format.setInteger(MediaFormat.KEY_MAX_HEIGHT, mVideoHeight);
            mDecoder = MediaCodec.createDecoderByType(mMimeType);
            mDecoder.configure(format, mSurface, null, 0);
            mDecoder.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void addPacker(NALPacket nalPacket) {
        mListBuffer.add(nalPacket);
    }
    
    @Override
    public void run() {
        super.run();
        initDecoder();
        while (!mIsEnd) {
            if (mListBuffer.size() == 0) {
                try {
                    sleep(DECODE_TIMEOUT); // 减少等待时间
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                continue;
            }
            // 改进缓冲策略：只在队列过大时丢弃旧帧，并优先保留关键帧
            if (mListBuffer.size() > MAX_BUFFER_SIZE) {
                // 从队列中查找并保留最近的关键帧
                for (int i = 0; i < mListBuffer.size(); i++) {
                    if (mListBuffer.get(i).nalType == 5) { // I帧
                        mListBuffer.remove(0);
                        break;
                    }
                }
                // 如果没有关键帧，则丢弃最旧的帧
                if (mListBuffer.size() > MAX_BUFFER_SIZE) {
                    mListBuffer.remove(0);
                }
            }
            doDecode(mListBuffer.remove(0));
        }
    }

    private void doDecode(NALPacket nalPacket) {
        final int TIMEOUT_USEC = 5000; // 减少超时时间
        ByteBuffer[] decoderInputBuffers = mDecoder.getInputBuffers();
        int inputBufIndex = -10000;
        
        try {
            inputBufIndex = mDecoder.dequeueInputBuffer(TIMEOUT_USEC);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        if (inputBufIndex >= 0) {
            ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
            inputBuf.clear();
            inputBuf.put(nalPacket.nalData);
            mDecoder.queueInputBuffer(inputBufIndex, 0, nalPacket.nalData.length, nalPacket.pts, 0);
        }

        int outputBufferIndex = -10000;
        try {
            outputBufferIndex = mDecoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        if (outputBufferIndex >= 0) {
            // 根据帧类型调整渲染策略
            boolean render = true;
            if (nalPacket.nalType == 5) { // I帧
                render = true;
            } else if (mListBuffer.size() > MAX_BUFFER_SIZE - 1) { // 队列压力大时跳过部分P帧
                render = false;
            }
            mDecoder.releaseOutputBuffer(outputBufferIndex, render);
            
            // 动态调整等待时间
            try {
                if (render) {
                    Thread.sleep(16); // 约60fps
                } else {
                    Thread.sleep(5); // 跳过帧时减少等待
                }
            } catch (InterruptedException ie) {
                ie.printStackTrace();
            }
        } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException ie) {
                ie.printStackTrace();
            }
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            decoderInputBuffers = mDecoder.getInputBuffers();
        } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // 处理格式变化
        }
    }

}
