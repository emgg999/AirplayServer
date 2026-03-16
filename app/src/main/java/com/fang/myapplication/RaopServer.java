package com.fang.myapplication;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import com.fang.myapplication.model.NALPacket;
import com.fang.myapplication.player.VideoPlayer;

public class RaopServer implements SurfaceHolder.Callback {

    static {
        System.loadLibrary("raop_server");
        System.loadLibrary("play-lib");
    }

    private static final String TAG = "RaopServer";
    private VideoPlayer mVideoPlayer;
    private SurfaceView mSurfaceView;
    private Surface mSurface;
    private boolean mSurfaceAvailable = false;
    private long mServerId = 0;

    // SPS/PPS 数据缓存，用于快速恢复
    private byte[] mSPSData = null;
    private byte[] mPPSData = null;

    public RaopServer(SurfaceView surfaceView) {
        mSurfaceView = surfaceView;
        mSurfaceView.getHolder().addCallback(this);
    }

    public void onRecvVideoData(byte[] nal, int nalType, long dts, long pts) {
        // 缓存 SPS/PPS 数据
        if (nalType == 7) { // SPS
            mSPSData = nal.clone();
        } else if (nalType == 8) { // PPS
            mPPSData = nal.clone();
        }

        NALPacket nalPacket = new NALPacket();
        nalPacket.nalData = nal;
        nalPacket.nalType = nalType;
        nalPacket.pts = pts;

        if (mVideoPlayer != null) {
            mVideoPlayer.doDecode(nalPacket);
        }
    }


    public void onRecvAudioData(short[] pcm, long pts) {
        // Log.d(TAG, "onRecvAudioData pcm length = " + pcm.length + ", pts = " + pts);
    }

    public void startServer() {
        if (mServerId == 0) {
            mServerId = start();
        }
    }

    public void stopServer() {
        if (mServerId != 0) {
            stop(mServerId);
        }
        mServerId = 0;

    }

    public int getPort() {
        if (mServerId != 0) {
            return getPort(mServerId);
        }
        return 0;
    }

    private native long start();

    private native void stop(long serverId);

    private native int getPort(long serverId);

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        mSurface = surfaceHolder.getSurface();
        mSurfaceAvailable = true;
        if (mVideoPlayer == null) {
            mVideoPlayer = new VideoPlayer(mSurface);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        mSurfaceAvailable = false;
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        if (mVideoPlayer != null) {
            mVideoPlayer.stopPlayback();
            mVideoPlayer = null;
        }
    }
}
