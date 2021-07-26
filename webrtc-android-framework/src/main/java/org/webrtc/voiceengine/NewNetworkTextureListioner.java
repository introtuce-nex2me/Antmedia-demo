package org.webrtc.voiceengine;

import android.graphics.SurfaceTexture;

/*
@Bhushan (Introtuce)
14-072-2021
the interface cats as a listioner for Network frame

 */
public interface NewNetworkTextureListioner {

    public void onNewNetworkTexture(SurfaceTexture texture);

}
