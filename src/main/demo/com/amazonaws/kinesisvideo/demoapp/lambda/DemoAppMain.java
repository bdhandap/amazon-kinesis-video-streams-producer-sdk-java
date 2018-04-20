package com.amazonaws.kinesisvideo.demoapp.lambda;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.kinesisvideo.client.mediasource.MediaSource;
import com.amazonaws.kinesisvideo.java.mediasource.file.ImageFileMediaSource;
import com.amazonaws.kinesisvideo.java.mediasource.file.ImageFileMediaSourceConfiguration;
import com.amazonaws.kinesisvideo.parser.rekognition.pojo.DetectedFace;
import com.amazonaws.kinesisvideo.parser.rekognition.pojo.FaceSearchResponse;
import com.amazonaws.kinesisvideo.parser.rekognition.pojo.MatchedFace;
import com.amazonaws.kinesisvideo.parser.rekognition.pojo.RekognitionOutput;
import com.amazonaws.kinesisvideo.parser.rekognition.pojo.RekognizedFragmentsIndex;
import com.amazonaws.kinesisvideo.parser.rekognition.pojo.RekognizedOutput;
import com.amazonaws.kinesisvideo.parser.utilities.FrameVisitor;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesis.model.Record;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;

/**
 * Demo Java Producer.
 */
public final class DemoAppMain implements RequestHandler<KinesisEvent, Context> {
    private static final String STREAM_NAME = "josvijay-lambda-stream";
    private static final int FPS_25 = 25;
    private static final String IMAGE_DIR = "src/main/resources/data/h264/";
    private static final String IMAGE_FILENAME_FORMAT = "frame-%03d.h264";
    private static final int START_FILE_INDEX = 1;
    private static final int END_FILE_INDEX = 444;
    // Backoff and retry settings
    private static final long BACKOFF_TIME_IN_MILLIS = 3000L;
    private static final int NUM_RETRIES = 10;
    private final ExecutorService executorService = Executors.newFixedThreadPool(100);
    private final AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();
    private final RekognizedFragmentsIndex rekognizedFragmentsIndex = new RekognizedFragmentsIndex();

    public DemoAppMain() {
    }

    public static void main(final String[] args) throws Exception {
        InputStream is = ClassLoader.getSystemClassLoader().getResourceAsStream
            ("libKinesisVideoProducerJNI.so");
        File file = File.createTempFile("lib", ".so");
        FileUtils.copyInputStreamToFile(is, file);
        System.load(file.getAbsolutePath());
        System.out.println("Loaded details from " + file.getAbsolutePath());
    }

    /**
     * Create a MediaSource based on local sample H.264 frames.
     *
     * @return a MediaSource backed by local H264 frame files
     */
    private static MediaSource createImageFileMediaSource() {
        final ImageFileMediaSourceConfiguration configuration =
                new ImageFileMediaSourceConfiguration.Builder()
                        .fps(FPS_25)
                        .dir(IMAGE_DIR)
                        .filenameFormat(IMAGE_FILENAME_FORMAT)
                        .startFileIndex(START_FILE_INDEX)
                        .endFileIndex(END_FILE_INDEX)
                        .build();
        final ImageFileMediaSource mediaSource = new ImageFileMediaSource();
        mediaSource.configure(configuration);

        return mediaSource;
    }

    @Override
    public Context handleRequest(final KinesisEvent kinesisEvent, final Context context) {
        try {
            // Loading Producer JNI
            System.out.println("Working Directory = " + System.getProperty("user.dir"));
            final ClassLoader classLoader = getClass().getClassLoader();
            final File cityFile = new File(classLoader.getResource("libKinesisVideoProducerJNI.so").getFile());
            System.out.println("Reading " + cityFile.getAbsolutePath());
            System.load(cityFile.getAbsolutePath());
            System.out.println("Loaded JNI from /lib/lambda/libKinesisVideoProducerJNI.so");
            List<Record> records = kinesisEvent.getRecords()
                    .stream()
                    .map(KinesisEvent.KinesisEventRecord::getKinesis)
                    .collect(Collectors.toList());
            processRecordsWithRetries(records);
            for (Map.Entry<String, List<RekognizedOutput>> entry : rekognizedFragmentsIndex.getEntrySet()) {
                // For each kinesis event record i.e for each fragment number create a call getMediaForFragmentList,
                // parse fragments, decode frame, draw bounding box, encode frame, call KVS PutFrame.
                processRekognitionOutput(entry.getKey(), entry.getValue());
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    private void processRekognitionOutput(String fragmentNumber, List<RekognizedOutput> rekognizedOutputList) {
        try {
            final String originalKvsStream = "bdhandap-stream";
            final KVSClient kvsClient = new KVSClient(Regions.US_WEST_2, credentialsProvider, originalKvsStream);
            final CompositeFrameViewer compositeFrameViewer =
                    new CompositeFrameViewer(640, 480);
            final H264FrameProcessor h264FrameProcessor = H264FrameProcessor.create(
                    compositeFrameViewer, rekognizedOutputList);
            h264FrameProcessor.setMaxTimeout(100);
            final FrameVisitor frameVisitor = FrameVisitor.create(h264FrameProcessor);
            final GetMediaForFragmentListWorker getMediaWorker = GetMediaForFragmentListWorker.create(
                    kvsClient.getStreamName(),
                    fragmentNumber,
                    kvsClient.getCredentialsProvider(),
                    kvsClient.getRegion(),
                    kvsClient.getAmazonKinesisVideo(),
                    frameVisitor);
            executorService.submit(getMediaWorker);


        } catch (final Exception e) {
            System.out.println("Error during GetMediaForFragmentList : " + e);
        }
    }


    /**
     * Process records performing retries as needed. Skip "poison pill" records.
     *
     * @param records Data records to be processed.
     */
    private void processRecordsWithRetries(List<Record> records) {
        for (Record record : records) {
            boolean processedSuccessfully = false;
            for (int i = 0; i < NUM_RETRIES; i++) {
                try {
                    processSingleRecord(record);
                    processedSuccessfully = true;
                    break;
                } catch (Throwable t) {
                    System.out.println("Caught throwable while processing record " + record + t.getMessage());
                }

                // backoff if we encounter an exception.
                try {
                    Thread.sleep(BACKOFF_TIME_IN_MILLIS);
                } catch (InterruptedException e) {
                    System.out.println("Interrupted sleep" + e);
                }
            }

            if (!processedSuccessfully) {
                System.out.println("Couldn't process record " + record + ". Skipping the record.");
            }
        }
    }

    /**
     * Process a single record.
     *
     * @param record The record to be processed.
     */
    private void processSingleRecord(Record record) {

        String data = null;
        ObjectMapper mapper = new ObjectMapper();
        try {
            ByteBuffer buffer = record.getData();
            data = new String(buffer.array(), "UTF-8");
            RekognitionOutput output = mapper.readValue(data, RekognitionOutput.class);

            // Get the fragment number from Rekognition Output
            final String fragmentNumber = output
                    .getInputInformation()
                    .getKinesisVideo()
                    .getFragmentNumber();
            final Double frameOffsetInSeconds = output
                    .getInputInformation()
                    .getKinesisVideo()
                    .getFrameOffsetInSeconds();
            final Double serverTimestamp = output
                    .getInputInformation()
                    .getKinesisVideo()
                    .getServerTimestamp();
            final Double producerTimestamp = output
                    .getInputInformation()
                    .getKinesisVideo()
                    .getProducerTimestamp();
            final double detectedTime = output.getInputInformation().getKinesisVideo().getServerTimestamp()
                    + output.getInputInformation().getKinesisVideo().getFrameOffsetInSeconds() * 1000L;
            final RekognizedOutput rekognizedOutput = RekognizedOutput.builder()
                    .fragmentNumber(fragmentNumber)
                    .serverTimestamp(serverTimestamp)
                    .producerTimestamp(producerTimestamp)
                    .frameOffsetInSeconds(frameOffsetInSeconds)
                    .detectedTime(detectedTime)
                    .build();

            // Add face search response
            List<FaceSearchResponse> responses = output.getFaceSearchResponse();

            responses.forEach(response -> {
                DetectedFace detectedFace = response.getDetectedFace();
                List<MatchedFace> matchedFaces = response.getMatchedFaces();
                RekognizedOutput.FaceSearchOutput faceSearchOutput = RekognizedOutput.FaceSearchOutput.builder()
                        .detectedFace(detectedFace)
                        .matchedFaceList(matchedFaces)
                        .build();
                rekognizedOutput.addFaceSearchOutput(faceSearchOutput);
            });

            // Add it to the index
            System.out.println("Found Rekognized results for fragment number : " + fragmentNumber);
            rekognizedFragmentsIndex.addToMap(fragmentNumber, rekognizedOutput);

        } catch (NumberFormatException e) {
            System.out.println("Record does not match sample record format. Ignoring record with data; " + data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void executeCommand(String command) throws IOException {
        Process child = Runtime.getRuntime().exec(command);

        InputStream in = child.getInputStream();
        int c;
        while ((c = in.read()) != -1) {
            System.out.print((char) c);
        }
        in.close();
    }
}
