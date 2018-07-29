package com.app.cerist.realtimeobjectdetectionapi;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.app.cerist.realtimeobjectdetectionapi.models.AutoFitTextureView;
import com.app.cerist.realtimeobjectdetectionapi.models.Classifier;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "TfLiteCamera";
    private static final String LOG_TAG = "Error";


    private static final int INPUT_SIZE = 300;
    private static final float GOOD_PROB_THRESHOLD = 0.3f;
    private static final int SMALL_COLOR = 0xffddaa88;

    private Classifier classifier;
    private Bitmap croppedBitmap = null;
    private Classifier.Recognition BestResult;


    private Object lockObj = new Object();
    private boolean runClassifier = false;

    private AutoFitTextureView mTextureView;
    private TextView mTextView;

    //background Thread
    private HandlerThread mHandlerThread;
    private Handler mHandler;

    //Camera preview settings
    private String mCameraid;
    private Size mPreviewSise;

    private CaptureRequest.Builder mCR_Builder;

    //Orientation Settings
    private static SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private static int sensorToDeviceRotation(CameraCharacteristics cameraCharacteristics, int deviceRotation) {
        int sensorRotation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        deviceRotation = ORIENTATIONS.get(deviceRotation);
        return (sensorRotation + deviceRotation + 360) % 360;
    }

    //Preview  Size settings
    private static class CompareSizeByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() /
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private static Size optimalSize(Size[] choices, int width, int height) {
        List<Size> requiredSizes = new ArrayList<>();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * height / width &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                requiredSizes.add(option);
            }
        }
        if (requiredSizes.size() > 0)
            return Collections.min(requiredSizes, new CompareSizeByArea());
        else
            return choices[0];
    }



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTextureView = findViewById(R.id.textureView);
        mTextView = findViewById(R.id.text_result);


        try {
            // create either a new ImageClassifierQuantizedMobileNet or an ImageClassifierFloatInception
            classifier = ImageClassifierSSD.create(this.getAssets());
            //croppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);

            //new ImageClassifierQuantizedMobileNet(this);
        } catch (IOException e) {
            Log.e(TAG, "Failed to initialize an image classifier.", e);
        }

        //classifier.setNumThreads(1);
        //classifier.setUseNNAPI(false);

        setupBackgroundThread();
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupBackgroundThread();
        if (mTextureView.isAvailable()) {
            setupCamera(mTextureView.getWidth(), mTextureView.getHeight());
            transformImg(mTextureView.getWidth(),mTextureView.getHeight());
            connectCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        classifier.close();
        super.onDestroy();
    }

    /*@Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Bitmap bitmap = (Bitmap) data.getExtras().get("data");
        //iv_camera.setImageBitmap(bitmap);

    }*/

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();
        if (hasFocus) {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

    }

    // Transform the image in rotation mode
    private void transformImg(int width, int height){
        if(mPreviewSise == null ||mTextureView == null)
            return;

        Matrix matrix = new Matrix();
        int rotatation = getWindowManager().getDefaultDisplay().getRotation();
        RectF textureRectF = new RectF(0,0,width,height);
        RectF previewRectF = new RectF(0,0,mPreviewSise.getHeight(),mPreviewSise.getWidth());
        float centerX =  textureRectF.centerX();
        float centerY =  textureRectF.centerY();

        if(rotatation == Surface.ROTATION_90 || rotatation == Surface.ROTATION_270 ){
            previewRectF.offset(centerX - previewRectF.centerX(), centerY - previewRectF.centerY());
            matrix.setRectToRect(textureRectF,previewRectF,Matrix.ScaleToFit.FILL);
            float scale = Math.max((float)width /mPreviewSise.getWidth(),(float)height / mPreviewSise.getHeight());
            matrix.setScale(scale,scale,centerX,centerY);
            matrix.setRotate(90 * (rotatation - 2), centerX, centerY);
        }

        mTextureView.setTransform(matrix );

    }
    private void load_model() {
        String text = "";
        try {
            InputStream stream = getAssets().open("test_assets.txt");
            int stream_size = stream.available();
            byte[] buffer = new byte[stream_size];
            stream.read(buffer);
            stream.close();

            text = new String(buffer);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

  /*  private void open_camera(){
        camera = Camera.open();
        mCamera = new MyCamera(getApplicationContext(),camera);
        //cameraFrame.addView(mCamera);
    }*/


    private void setupBackgroundThread() {
        mHandlerThread = new HandlerThread("Camera");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        synchronized (lockObj) {
            runClassifier = true;
        }
        mHandler.post(periodicClassify);
    }

    private void stopBackgroundThread() {
        mHandlerThread.quitSafely();
        try {
            mHandlerThread.join();
            mHandlerThread = null;
            mHandler = null;
            synchronized (lockObj) {
                runClassifier = false;
            }

        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted when stopping background thread", e);
            e.printStackTrace();
        }
    }

    //Cameara Settings
    private void setupCamera(int width, int height) {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraid : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraid);
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                int deviceRotation = getWindowManager().getDefaultDisplay().getRotation();
                int totalRotation = sensorToDeviceRotation(characteristics, deviceRotation);
                boolean swapRotation = totalRotation == 90 || totalRotation == 270;
                int rotatedWidth = width;
                int rotatedHeight = height;
                if (swapRotation) {
                    rotatedWidth = height;
                    rotatedHeight = width;
                }

                mPreviewSise = optimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedWidth, rotatedHeight);
                mCameraid = cameraid;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //connecting Camera
    private static final int REQUEST_CAMERA_PERMISSION_RESULT = 0;
    private void connectCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
                if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED){
                    manager.openCamera(mCameraid, mStateCallback, mHandler);
                }else {
                    if(shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
                        Toast.makeText(this, "App required access to Camera", Toast.LENGTH_SHORT).show();
                    requestPermissions(new String[]{ Manifest.permission.CAMERA},REQUEST_CAMERA_PERMISSION_RESULT);
                }

            }else {
                manager.openCamera(mCameraid, mStateCallback, mHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //Start Recording
    private void startPreview(){
        SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSise.getWidth(),mPreviewSise.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        try {
            mCR_Builder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCR_Builder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            try {
                                session.setRepeatingRequest(mCR_Builder.build(),null,mHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }

                        }
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            Toast.makeText(MainActivity.this, "Unable to setup  camera preview", Toast.LENGTH_SHORT).show();
                        }
                    },null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    private void closeCamera(){
        if(mCameraDevice != null){
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    private CameraDevice mCameraDevice;
    private CameraDevice.StateCallback  mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            startPreview();

        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
            mCameraDevice = null;

        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            cameraDevice.close();
            mCameraDevice = null;
        }
    };

    private TextureView.SurfaceTextureListener mSurfaceListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int heitgh) {
            Toast.makeText(MainActivity.this, "suraceTexture avable", Toast.LENGTH_SHORT).show();
            setupCamera(width,heitgh);
            transformImg(width,heitgh);
            connectCamera();

        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    };


    /*
     *  Classifies a frame from the preview stream.
     *  */
    private void classifyFrame(){
        if(classifier == null || this == null || mCameraDevice ==null){
            showToast("Uninitialized Classifier or invalid context.");
            return;
        }

        SpannableStringBuilder textToShow = new SpannableStringBuilder();

        Bitmap bitmap = mTextureView.getBitmap(INPUT_SIZE,INPUT_SIZE);
        final List<Classifier.Recognition> results = classifier.recognizeImage(bitmap);

        //Collections.sort(results); // No need, already sorted in ImageClassifierSSD class
        if (!results.isEmpty()) {
            Log.d("TAG_RESULT",""+results.size());
            Log.d("TAG_RESULT_Confidence1",""+results.get(0).toString());

            BestResult = results.get(0);
            //String resultBuilder = BestResult.getTitle()+" "+String.valueOf(BestResult.getConfidence());
            SpannableString span = new SpannableString(String.format("%s: %4.2f\n", BestResult.getTitle(), BestResult.getConfidence()));

            int color;
            // Make it white when probability larger than threshold.
            if (BestResult.getConfidence() > GOOD_PROB_THRESHOLD) {
                color = android.graphics.Color.WHITE;
            } else {
                color = SMALL_COLOR;
            }
            span.setSpan(new ForegroundColorSpan(color), 0, span.length(), 0);
            textToShow.insert(0, span);

        }
        else {
            textToShow.append(new SpannableString("No results From Classifier."));
        }

        //classifier.classifyFrame(bitmap,textToShow);
        bitmap.recycle();
        showToast(textToShow);
    }

    /** Takes & classify the pics periodically. */
    private Runnable periodicClassify =
            new Runnable() {
                @Override
                public void run() {
                    synchronized (lockObj) {
                        if (runClassifier) {
                            classifyFrame();
                        }
                    }
                    mHandler.post(periodicClassify);
                }
            };




    /**
     * Shows a {@link Toast} on the UI thread for the classification results.
     *
     * @param s The message to show
     */
    private void showToast(String s) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        SpannableString str1 = new SpannableString(s);
        builder.append(str1);
        showToast(builder);
    }

    private void showToast(SpannableStringBuilder builder) {
        final SpannableStringBuilder mBuilder =builder;
            this.runOnUiThread(
                    new Runnable() {
                        @Override
                        public void run() {
                            mTextView.setText(mBuilder, TextView.BufferType.SPANNABLE);
                        }
                    });
    }


}
