package com.amazonaws.kinesisvideo.demoapp.lambda;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.jcodec.codecs.h264.H264Encoder;
import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.encode.H264FixedRateControl;
import org.jcodec.codecs.h264.io.model.PictureParameterSet;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.mp4.AvcCBox;
import org.jcodec.common.VideoEncoder;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Size;
import org.jcodec.scale.AWTUtil;

import static java.util.Arrays.asList;

public class H264StreamEncoderJcodec {
    private Picture toEncode;
    private H264Encoder encoder;
    private SeqParameterSet sps;
    private PictureParameterSet pps;
    private ByteBuffer out;
    private byte[] cpd;

    public H264StreamEncoderJcodec(int width, int height) {

        out = ByteBuffer.allocate(width * height * 6);
        H264Encoder encoder = new H264Encoder(new H264FixedRateControl(512));
        final Size size = new Size(width, height);

        ByteBuffer spsBuffer = ByteBuffer.allocate(512);
        this.sps.write(spsBuffer);
        spsBuffer.flip();

        ByteBuffer serialSps = ByteBuffer.allocate(512);
        this.getSps().write(serialSps);
        serialSps.flip();
        H264Utils.escapeNALinplace(serialSps);

        ByteBuffer serialPps = ByteBuffer.allocate(512);
        this.getPps().write(serialPps);
        serialPps.flip();
        H264Utils.escapeNALinplace(serialPps);

        ByteBuffer serialAvcc = ByteBuffer.allocate(512);
        AvcCBox avcC = AvcCBox.createAvcCBox(this.getSps().profileIdc, 0, this.getSps().levelIdc, 4,
                asList(serialSps), asList(serialPps));
        avcC.doWrite(serialAvcc);
        serialAvcc.flip();
        cpd = new byte[serialAvcc.remaining()];
        serialAvcc.get(cpd);
        this.encoder = encoder;
        sps = this.encoder.initSPS(size);
        pps = this.encoder.initPPS();
    }

    public VideoEncoder.EncodedFrame encodeImage(BufferedImage bi) throws IOException {
        if (toEncode == null) {
            toEncode = Picture.create(bi.getWidth(), bi.getHeight(), ColorSpace.RGB);
        }

        // Perform conversion
        toEncode = AWTUtil.fromBufferedImage(bi, ColorSpace.YUV420J);

        // Encode image into H.264 frame, the result is stored in 'out' buffer
        out.clear();
        return encoder.encodeFrame(toEncode, out);
    }

    public SeqParameterSet getSps() {
        return sps;
    }

    public PictureParameterSet getPps() {
        return pps;
    }

    public byte[] getCodecPrivateData() { return cpd; }

    public int getKeyInterval() {
        return encoder.getKeyInterval();
    }
}
