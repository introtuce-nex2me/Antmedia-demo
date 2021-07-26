package org.webrtc.voiceengine;

import android.graphics.SurfaceTexture;

import org.webrtc.VideoFrame;

public interface NewFrameListioner {

    public void onNewFrame(VideoFrame frame);

    public  void onNewTexture(SurfaceTexture texture);
}
