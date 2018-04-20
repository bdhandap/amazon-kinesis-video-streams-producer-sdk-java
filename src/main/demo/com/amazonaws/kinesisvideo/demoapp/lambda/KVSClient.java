package com.amazonaws.kinesisvideo.demoapp.lambda;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoCommon;
import com.amazonaws.kinesisvideo.parser.examples.StreamOps;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;

/**
 * TODO: Change the text
 */
public class KVSClient extends KinesisVideoCommon {
    StreamOps streamOps;

    public KVSClient(final Regions region, final AWSCredentialsProvider credentialsProvider, final String streamName) {
        super(region, credentialsProvider, streamName);
        streamOps = new StreamOps(region, streamName, credentialsProvider);
    }

    public AmazonKinesisVideo getAmazonKinesisVideo() {
        return this.streamOps.getAmazonKinesisVideo();
    }
}
