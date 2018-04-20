package com.amazonaws.kinesisvideo.demoapp.lambda;

import java.awt.image.BufferedImage;

import com.amazonaws.kinesisvideo.parser.examples.BoundingBoxImagePanel;
import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoFrameViewer;
import com.amazonaws.kinesisvideo.parser.rekognition.pojo.RekognizedOutput;

/**
 *
 */
public class CompositeFrameViewer extends KinesisVideoFrameViewer {

    public CompositeFrameViewer(int width, int height) {
        super(width, height, "KinesisVideo Embedded Frame Viewer");
        panel = new BoundingBoxImagePanel();
        addImagePanel(panel);
    }

    public void renderBoundingBox(BufferedImage bufferedImage, RekognizedOutput output) {

        // This is a hack. Refactor and expose methods from Image panel to common utility class which can draw bounding box.
        ((BoundingBoxImagePanel) panel).processRekognitionOutput(bufferedImage.createGraphics(), output);
    }
}
