package com.amazonaws.kinesisvideo.mediasource;

import java.nio.ByteBuffer;

public interface OnFrameDataAvailable {
    default void onFrameDataAvailable(final ByteBuffer data) {

    }
    default void onFrameDataAvailable(final ByteBuffer data, boolean isKeyFrame) {

    }
}
