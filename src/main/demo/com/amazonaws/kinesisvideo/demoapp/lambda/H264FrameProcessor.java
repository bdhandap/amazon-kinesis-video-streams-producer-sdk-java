/*
Copyright 2017-2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License").
You may not use this file except in compliance with the License.
A copy of the License is located at

   http://aws.amazon.com/apache2.0/

or in the "license" file accompanying this file.
This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and limitations under the License.
*/
package com.amazonaws.kinesisvideo.demoapp.lambda;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.kinesisvideo.client.KinesisVideoClient;
import com.amazonaws.kinesisvideo.client.mediasource.CameraMediaSourceConfiguration;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.java.client.KinesisVideoJavaClientFactory;
import com.amazonaws.kinesisvideo.parser.mkv.Frame;
import com.amazonaws.kinesisvideo.parser.rekognition.pojo.RekognizedOutput;
import com.amazonaws.kinesisvideo.parser.utilities.FragmentMetadata;
import com.amazonaws.kinesisvideo.parser.utilities.H264FrameRenderer;
import com.amazonaws.kinesisvideo.parser.utilities.MkvTrackMetadata;
import com.amazonaws.kinesisvideo.producer.StreamInfo;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jcodec.common.VideoEncoder;

@Slf4j
public class H264FrameProcessor extends H264FrameRenderer {

    private static final int DEFAULT_MAX_TIMEOUT = 100;
    private static final int WAIT_TIMEOUT = 3;
    private static final int MILLIS_IN_SEC = 1000;
    private static final int OFFSET_DELTA_THRESHOLD = 10;

    private final CompositeFrameViewer compositeFrameViewer;
    private final List<RekognizedOutput> rekognizedOutputs;
    private RekognizedOutput currentRekognizedOutput = null;
    private H264StreamEncoderJcodec h264Encoder;
    private CameraMediaSource cameraMediaSource;
    private boolean isKVSProducerInitialized = false;

    @Setter
    private int maxTimeout = DEFAULT_MAX_TIMEOUT;

    private long keyFrameTimecode;

    private H264FrameProcessor(final CompositeFrameViewer compositeFrameViewer,
                               final List<RekognizedOutput> rekognizedOutputs) {
        super(compositeFrameViewer);
        this.compositeFrameViewer = compositeFrameViewer;
        this.rekognizedOutputs = rekognizedOutputs;
    }

    private void initializeKinesisVideoProducer(final byte[] cpd) {
        try {
            final KinesisVideoClient kinesisVideoClient = KinesisVideoJavaClientFactory
                    .createKinesisVideoClient(new ProfileCredentialsProvider("default"));
            final CameraMediaSourceConfiguration configuration =
                    new CameraMediaSourceConfiguration.Builder()
                            .withFrameRate(30)
                            .withRetentionPeriodInHours(1)
                            .withCameraId("/dev/video0")
                            .withIsEncoderHardwareAccelerated(false)
                            .withEncodingMimeType("video/avc")
                            .withNalAdaptationFlags(StreamInfo.NalAdaptationFlags.NAL_ADAPTATION_ANNEXB_NALS)
                            .withIsAbsoluteTimecode(false)
                            .withEncodingBitRate(200000)
                            .withHorizontalResolution(640)
                            .withVerticalResolution(480)
                            .withCodecPrivateData(cpd)
                            .build();
            this.cameraMediaSource = new CameraMediaSource();
            this.cameraMediaSource.configure(configuration);

            // register media source with Kinesis Video Client
            kinesisVideoClient.registerMediaSource("bdhandap-stream-rekognized", cameraMediaSource);
        } catch (KinesisVideoException e) {
            e.printStackTrace();
        }
    }

    public static H264FrameProcessor create(CompositeFrameViewer compositeFrameViewer,
                                            List<RekognizedOutput> rekognizedOutputs) {
        return new H264FrameProcessor(compositeFrameViewer, rekognizedOutputs);
    }

    @Override
    public void process(Frame frame, MkvTrackMetadata trackMetadata, Optional<FragmentMetadata> fragmentMetadata) {
        // Decode H264 frame
        final BufferedImage decodedFrame = decodeH264Frame(frame, trackMetadata);

        // Get Rekognition results for this fragment number
        Optional<RekognizedOutput> rekognizedOutput = findRekognizedOutputForFrame(frame, fragmentMetadata, rekognizedOutputs);

        // Render frame with bounding box
        BufferedImage compositeFrame = renderFrame(decodedFrame, rekognizedOutput);

        // Encode to H264 frame
        EncodedFrame encodedH264Frame = encodeH264Frame(compositeFrame);

        // Call PutFrame
        putFrame(encodedH264Frame);

    }

    private void putFrame(final EncodedFrame encodedH264Frame) {
        if (!isKVSProducerInitialized) {
            initializeKinesisVideoProducer(encodedH264Frame.getCpd());
        }
        cameraMediaSource.putFrameData(encodedH264Frame.getByteBuffer(), encodedH264Frame.isKeyFrame());
    }

    private EncodedFrame encodeH264Frame(final BufferedImage bufferedImage) {
        try {
            this.h264Encoder = new H264StreamEncoderJcodec(bufferedImage.getWidth(), bufferedImage.getHeight());
            VideoEncoder.EncodedFrame result = h264Encoder.encodeImage(bufferedImage);
            ByteBuffer h264frame = result.getData();
            ByteBuffer clone = ByteBuffer.allocate(h264frame.remaining());
            h264frame.rewind();//copy from the beginning
            clone.put(h264frame);
            h264frame.rewind();
            clone.flip();
            System.out.println("Encoded h264 frame successfully");
            return EncodedFrame.builder()
                    .byteBuffer(clone)
                    .isKeyFrame(result.isKeyFrame())
                    .cpd(h264Encoder.getCodecPrivateData())
                    .build();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException();
        }
    }

    private Optional<RekognizedOutput> findRekognizedOutputForFrame(final Frame frame,
                                                                    final Optional<FragmentMetadata> fragmentMetadata,
                                                                    final List<RekognizedOutput> rekognizedOutputs) {

        Optional<RekognizedOutput> rekognizedOutput = Optional.empty();
        if (fragmentMetadata.isPresent()) {
            final String fragmentNumber = fragmentMetadata.get().getFragmentNumberString();

            // Currently Rekognition samples frames and calculates the frame offset from the fragment start time.
            // So, in order to match with rekognition results, we have to compute the same frame offset from the
            // beginning of the fragments.
            if (frame.isKeyFrame()) {
                keyFrameTimecode = frame.getTimeCode();
                log.debug("Key frame timecode : {}", keyFrameTimecode);
            }
            final long frameOffset = (frame.getTimeCode() > keyFrameTimecode)
                    ? frame.getTimeCode() - keyFrameTimecode : 0;
            log.debug("Current Fragment Number : {} Computed Frame offset : {}", fragmentNumber, frameOffset);
            if (log.isDebugEnabled()) {
                this.rekognizedOutputs
                        .forEach(p -> log.debug("frameOffsetInSeconds from Rekognition : {}",
                                p.getFrameOffsetInSeconds()));
            }

            // Check whether the computed offset matches the rekognized output frame offset. Rekognition
            // output is in seconds whereas the frame offset is calculated in milliseconds.
            // NOTE: Rekognition frame offset doesn't exactly match with the computed offset below. So
            // take the closest one possible within 10ms delta.
            rekognizedOutput = this.rekognizedOutputs.stream()
                    .filter(p -> isOffsetDeltaWithinThreshold(frameOffset, p))
                    .findFirst();

            // Remove from the index once the RekognizedOutput is processed. Else it would increase the memory
            // footprint and blow up the JVM.
            if (rekognizedOutput.isPresent()) {
                log.debug("Computed offset matched with retrieved offset. Delta : {}",
                        Math.abs(frameOffset - (rekognizedOutput.get().getFrameOffsetInSeconds() * MILLIS_IN_SEC)));

                if (this.rekognizedOutputs.isEmpty()) {
                    log.debug("All frames processed for this fragment number : {}", fragmentNumber);
                }
            }
        }
        return rekognizedOutput;
    }



    private boolean isOffsetDeltaWithinThreshold(final long frameOffset, final RekognizedOutput output) {
        return Math.abs(frameOffset - (output.getFrameOffsetInSeconds() * MILLIS_IN_SEC)) <= OFFSET_DELTA_THRESHOLD;
    }

    @SuppressWarnings("Duplicates")
    private BufferedImage renderFrame(BufferedImage bufferedImage, Optional<RekognizedOutput> rekognizedOutput) {
        if (rekognizedOutput.isPresent()) {
            System.out.println("Rendering Rekognized sampled frame...");
            compositeFrameViewer.renderBoundingBox(bufferedImage, rekognizedOutput.get());
            currentRekognizedOutput = rekognizedOutput.get();
        } else if (compositeFrameViewer != null) {
            System.out.println("Rendering non-sampled frame with previous rekognized results...");
            compositeFrameViewer.renderBoundingBox(bufferedImage, currentRekognizedOutput);
        } else {
            System.out.println("Rendering frame without any rekognized results...");
        }
        return bufferedImage;
    }


    private long waitForResults(long timeout) {
        final long startTime = System.currentTimeMillis();
        try {
            log.info("No rekognized results for this fragment number. Waiting ....");
            Thread.sleep(timeout);
        } catch (InterruptedException e) {
            log.warn("Error while waiting for rekognized output !", e);
        }
        return System.currentTimeMillis() - startTime;
    }

}
