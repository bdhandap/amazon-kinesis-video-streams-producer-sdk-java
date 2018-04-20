package com.amazonaws.kinesisvideo.demoapp.lambda;

import java.nio.ByteBuffer;
import java.time.Duration;

import com.amazonaws.kinesisvideo.mediasource.OnFrameDataAvailable;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class CameraFrameSource {

    private int frameIndex;
    private final int fps = 30;
    private OnFrameDataAvailable onFrameDataAvailable;
    private boolean isRunning = false;
    private final Log log = LogFactory.getLog(CameraFrameSource.class);


    public void onBytesAvailable(final OnFrameDataAvailable onFrameDataAvailable) {
        this.onFrameDataAvailable = onFrameDataAvailable;
    }

	public void start() {
        if (isRunning) {
            throw new IllegalStateException("Frame source is already running");
        }

        isRunning = true;
    }

    public void stop() {
        isRunning = false;
    }

    public void putFrameData(ByteBuffer frameData, boolean isKeyFrame) {
        if (onFrameDataAvailable != null) {
            if (frameData != null) {
                onFrameDataAvailable.onFrameDataAvailable(frameData, isKeyFrame);
            }
        }
        try {
            Thread.sleep(Duration.ofSeconds(1L).toMillis() / fps);
        } catch (final InterruptedException e) {
            log.error("Frame interval wait interrupted by Exception ", e);
        }
    }

}
