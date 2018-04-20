package com.amazonaws.kinesisvideo.demoapp.lambda;

import java.nio.ByteBuffer;

import lombok.Builder;
import lombok.Getter;

/**
 * TODO: Change the text
 */
@Getter
@Builder
public class EncodedFrame {

    private ByteBuffer byteBuffer;
    private byte[] cpd;
    private boolean isKeyFrame;
}
