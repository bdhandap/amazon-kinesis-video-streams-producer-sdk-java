package com.amazonaws.kinesisvideo.demoapp.lambda;


import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.kinesisvideo.parser.ebml.InputStreamParserByteSource;
import com.amazonaws.kinesisvideo.parser.examples.KinesisVideoCommon;
import com.amazonaws.kinesisvideo.parser.mkv.MkvElementVisitException;
import com.amazonaws.kinesisvideo.parser.mkv.MkvElementVisitor;
import com.amazonaws.kinesisvideo.parser.mkv.StreamingMkvReader;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideo;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMedia;
import com.amazonaws.services.kinesisvideo.AmazonKinesisVideoArchivedMediaClient;
import com.amazonaws.services.kinesisvideo.model.APIName;
import com.amazonaws.services.kinesisvideo.model.GetDataEndpointRequest;
import com.amazonaws.services.kinesisvideo.model.GetMediaForFragmentListRequest;
import com.amazonaws.services.kinesisvideo.model.GetMediaForFragmentListResult;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GetMediaForFragmentListWorker extends KinesisVideoCommon implements Runnable {
    private final AmazonKinesisVideoArchivedMedia amazonKinesisVideoArchivedMedia;
    private final MkvElementVisitor elementVisitor;
    private final String fragmentNumber;

    public GetMediaForFragmentListWorker(final String streamName, final String fragmentNumber,
                                         final AWSCredentialsProvider awsCredentialsProvider, final String endPoint,
                                         final Regions region, final MkvElementVisitor elementVisitor) {
        super(region, awsCredentialsProvider, streamName);
        this.fragmentNumber = fragmentNumber;
        this.elementVisitor = elementVisitor;
        amazonKinesisVideoArchivedMedia = AmazonKinesisVideoArchivedMediaClient
                .builder()
                .withCredentials(awsCredentialsProvider)
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endPoint, region.getName()))
                .build();
    }


    public static GetMediaForFragmentListWorker create(final String streamName, final String fragmentNumber,
                                                       final AWSCredentialsProvider awsCredentialsProvider,
                                                       final Regions region,
                                                       final AmazonKinesisVideo amazonKinesisVideo,
                                                       final MkvElementVisitor elementVisitor) {
        final GetDataEndpointRequest request = new GetDataEndpointRequest()
                .withAPIName(APIName.GET_MEDIA_FOR_FRAGMENT_LIST).withStreamName(streamName);
        String endpoint = amazonKinesisVideo.getDataEndpoint(request).getDataEndpoint();
        return new GetMediaForFragmentListWorker(
                streamName, fragmentNumber, awsCredentialsProvider, endpoint, region, elementVisitor);
    }

    @Override
    public void run() {
        try {
            System.out.println("Start GetMediaForFragmentList worker on stream " + streamName);
            GetMediaForFragmentListResult result = amazonKinesisVideoArchivedMedia.getMediaForFragmentList(
                    new GetMediaForFragmentListRequest()
                            .withFragments(fragmentNumber)
                            .withStreamName(streamName));

            System.out.println(String.format("GetMediaForFragmentList called on stream %s response %s requestId %s",
                    streamName,
                    result.getSdkHttpMetadata().getHttpStatusCode(),
                    result.getSdkResponseMetadata().getRequestId()));
            StreamingMkvReader mkvStreamReader = StreamingMkvReader.createDefault(
                    new InputStreamParserByteSource(result.getPayload()));
            log.info("StreamingMkvReader created for stream {} ", streamName);
            try {
                mkvStreamReader.apply(this.elementVisitor);
            } catch (MkvElementVisitException e) {
                log.error("Exception while accepting visitor {}", e);
            }
        } catch (Throwable t) {
            log.error("Failure in GetMediaWorker for streamName {} {}", streamName, t.toString());
            throw t;
        } finally {
            log.info("Exiting GetMediaWorker for stream {}", streamName);
        }
    }
}
