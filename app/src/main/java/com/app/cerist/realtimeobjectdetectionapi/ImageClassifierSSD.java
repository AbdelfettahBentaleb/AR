package com.app.cerist.realtimeobjectdetectionapi;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Trace;

import com.app.cerist.realtimeobjectdetectionapi.models.Classifier;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.StringTokenizer;
import java.util.Vector;

public class ImageClassifierSSD implements Classifier {

    private static final String MODEL_FILE = "mobilenet_ssd_v1_home_appliances.tflite";
    private static final String LABELS_FILE = "home_appliances_labels_ssd_mobilenet_v1.txt";
    private static final String BOX_PRIORS_FILE = "box_priors.txt";

    private static final int INPUT_SIZE = 300;
    private static final int DIM_BATCH_SIZE = 1;
    private static final int DIM_PIXEL_SIZE = 3;
    private static int NUM_BYTES_PER_CHANNEL = 4;


    // Only return this many results.
    private static final int MAX_RESULTS = 1917; // Maximum number of results the classifier can detect
    private static final int NUM_RESULTS = 10; // Number of results shown on the screen

    private static final int NUM_CLASSES = 4; // Number of labels + 1

    private static final float Y_SCALE = 10.0f;
    private static final float X_SCALE = 10.0f;
    private static final float H_SCALE = 5.0f;
    private static final float W_SCALE = 5.0f;

    // Float model
    private static final float IMAGE_MEAN = 128.0f;
    private static final float IMAGE_STD = 128.0f;

    // Number of threads in the java app
    private static final int NUM_THREADS = 4;

    // Config values.
    //private int inputSize;
    private final float[][] boxPriors = new float[4][MAX_RESULTS];

    // Preallocated buffers.
    private Vector<String> labels = new Vector<String>();
    private int[] intValues;

    private float[][][][] outputLocations;
    private float[][][] outputClasses;

    private ByteBuffer imgData = null;

    private Interpreter tfLite;
    private AssetManager assetManager;

    private float expit(final float x) {
        return (float) (1. / (1. + Math.exp(-x)));
    }
    private float linear_func(final float x){ return (float) x;}

    /**
     * Memory map the model file in Assets.
     */
    private static MappedByteBuffer loadModelFile(AssetManager assets, String modelFilename)
            throws IOException {
        AssetFileDescriptor fileDescriptor = assets.openFd(modelFilename);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    /**
     * Initializes a native TensorFlow session for classifying images.
     *
     * @param assetManager  The asset manager to be used to load assets.
     */

    public static Classifier create(
            final AssetManager assetManager) throws IOException {

        final ImageClassifierSSD d = new ImageClassifierSSD();
        d.assetManager = assetManager;
        d.loadCoderOptions(assetManager, "file:///android_asset/"+BOX_PRIORS_FILE, d.boxPriors);

        InputStream labelsInput = null;
        String actualFilename = LABELS_FILE; //labelFilename.split("file:///android_asset/")[1];
        labelsInput = assetManager.open(actualFilename);

        BufferedReader br = null;
        br = new BufferedReader(new InputStreamReader(labelsInput));
        String line;
        while ((line = br.readLine()) != null) {
            d.labels.add(line);
        }
        br.close();

        //d.inputSize = inputSize;

        try {
            d.tfLite = new Interpreter(loadModelFile(assetManager, MODEL_FILE));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


        d.imgData = ByteBuffer.allocateDirect(DIM_BATCH_SIZE * INPUT_SIZE * INPUT_SIZE * DIM_PIXEL_SIZE * NUM_BYTES_PER_CHANNEL);
        d.imgData.order(ByteOrder.nativeOrder());
        d.intValues = new int[INPUT_SIZE * INPUT_SIZE];

        d.tfLite.setNumThreads(NUM_THREADS);

        d.outputLocations = new float[1][MAX_RESULTS][1][4];
        d.outputClasses = new float[1][MAX_RESULTS][NUM_CLASSES];
        return d;
    }

    private ImageClassifierSSD() {
    }

    @Override
    public List<Recognition> recognizeImage(final Bitmap bitmap) {
        // Log this method so that it can be analyzed with systrace.
        Trace.beginSection("recognizeImage");

        Trace.beginSection("preprocessBitmap");
        // Preprocess the image data from 0-255 int to normalized float based
        // on the provided parameters.

        imgData.rewind();
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        for (int i = 0; i < INPUT_SIZE; ++i) {
            for (int j = 0; j < INPUT_SIZE; ++j) {
                int pixelValue = intValues[i * INPUT_SIZE + j];

                // Float model
                imgData.putFloat((((pixelValue >> 16) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                imgData.putFloat((((pixelValue >> 8) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                imgData.putFloat(((pixelValue & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
            }
        }
        Trace.endSection(); // preprocessBitmap

        // Copy the input data into TensorFlow.
        Trace.beginSection("feed");

        outputLocations = new float[1][MAX_RESULTS][1][4];
        outputClasses = new float[1][MAX_RESULTS][NUM_CLASSES];

        Object[] inputArray = {imgData};
        Map<Integer, Object> outputMap = new HashMap<>();
        outputMap.put(0, outputLocations);
        outputMap.put(1, outputClasses);

        Trace.endSection();

        // Run the inference call.
        Trace.beginSection("run");
        tfLite.runForMultipleInputsOutputs(inputArray, outputMap);
        Trace.endSection();

        decodeCenterSizeBoxes(outputLocations);

        // Find the best detections.
        final PriorityQueue<Recognition> pq =
                new PriorityQueue<Recognition>(
                        1,
                        new Comparator<Recognition>() {
                            @Override
                            public int compare(final Recognition lhs, final Recognition rhs) {
                                // Intentionally reversed to put high confidence at the head of the queue.
                                return Float.compare(rhs.getConfidence(), lhs.getConfidence());
                            }
                        });

        // Scale them back to the input size.
        for (int i = 0; i < MAX_RESULTS; ++i) {
            float topClassScore = -1000f;
            int topClassScoreIndex = -1;

            // Skip the first catchall class.
            for (int j = 1; j < NUM_CLASSES; ++j) {
                float score = expit(outputClasses[0][i][j]);

                if (score > topClassScore) {
                    topClassScoreIndex = j;
                    topClassScore = score;
                }
            }

            if (topClassScore > 0.001f) {
                final RectF detection =
                        new RectF(
                                outputLocations[0][i][0][1] * INPUT_SIZE,
                                outputLocations[0][i][0][0] * INPUT_SIZE,
                                outputLocations[0][i][0][3] * INPUT_SIZE,
                                outputLocations[0][i][0][2] * INPUT_SIZE);

                pq.add(
                        new Recognition(
                                "" + i,
                                labels.get(topClassScoreIndex),
                                expit(outputClasses[0][i][topClassScoreIndex]),
                                detection));
            }
        }

        final ArrayList<Recognition> recognitions = new ArrayList<Recognition>();
        for (int i = 0; i < Math.min(pq.size(), NUM_RESULTS); ++i) {
            Recognition recog = pq.poll();
            recognitions.add(recog);

        }

        Trace.endSection(); // "recognizeImage"
        return recognitions;

    }


        @Override
        public void enableStatLogging ( boolean debug){

        }

        @Override
        public String getStatString () {
            return "";
        }

        @Override
        public void close () {

        }


        private void loadCoderOptions (
        final AssetManager assetManager, final String locationFilename, final float[][] boxPriors)
            throws IOException {
            // Try to be intelligent about opening from assets or sdcard depending on prefix.
            final String assetPrefix = "file:///android_asset/";
            InputStream is;
            if (locationFilename.startsWith(assetPrefix)) {
                is = assetManager.open(locationFilename.split(assetPrefix, -1)[1]);
            } else {
                is = new FileInputStream(locationFilename);
            }

            final BufferedReader reader = new BufferedReader(new InputStreamReader(is));

            for (int lineNum = 0; lineNum < 4; ++lineNum) {
                String line = reader.readLine();
                final StringTokenizer st = new StringTokenizer(line, ", ");
                int priorIndex = 0;
                while (st.hasMoreTokens()) {
                    final String token = st.nextToken();
                    try {
                        final float number = Float.parseFloat(token);
                        boxPriors[lineNum][priorIndex++] = number;
                    } catch (final NumberFormatException e) {
                        // Silently ignore.
                    }
                }
                if (priorIndex != MAX_RESULTS) {
                    throw new RuntimeException(
                            "BoxPrior length mismatch: " + priorIndex + " vs " + MAX_RESULTS);
                }
            }

        }

        private void decodeCenterSizeBoxes ( float[][][][] predictions){
            for (int i = 0; i < MAX_RESULTS; ++i) {
                float ycenter = predictions[0][i][0][0] / Y_SCALE * boxPriors[2][i] + boxPriors[0][i];
                float xcenter = predictions[0][i][0][1] / X_SCALE * boxPriors[3][i] + boxPriors[1][i];
                float h = (float) Math.exp(predictions[0][i][0][2] / H_SCALE) * boxPriors[2][i];
                float w = (float) Math.exp(predictions[0][i][0][3] / W_SCALE) * boxPriors[3][i];

                float ymin = ycenter - h / 2.f;
                float xmin = xcenter - w / 2.f;
                float ymax = ycenter + h / 2.f;
                float xmax = xcenter + w / 2.f;

                predictions[0][i][0][0] = ymin;
                predictions[0][i][0][1] = xmin;
                predictions[0][i][0][2] = ymax;
                predictions[0][i][0][3] = xmax;
            }
        }


    }


