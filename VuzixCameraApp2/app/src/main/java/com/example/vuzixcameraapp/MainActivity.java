package com.example.vuzixcameraapp;

import static java.lang.Math.clamp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};
    private static int INFERENCE_INTERVAL = 5; // Run inference every 5 frames
    private PreviewView previewView;
    private Interpreter tflite;
    private int screenWidth;
    private int screenHeight;
    private OverlayView overlayView;
    private FrameLayout infoOverlay;
    private TextView infoText;
    private ImageView infoImage;
    private ScrollView infoScrollView;
    private TextView detectionStatus;
    private int previewWidth;
    private int previewHeight;
    private int frameCounter = 0;
    private volatile boolean isInferenceRunning = false;
    private HandlerThread analysisThread;
    private Handler analysisHandler;
    private RenderScript rs;
    private ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic;
    private Allocation inputAllocation;
    private Allocation outputAllocation;
    private ByteBuffer inputBuffer;
    private List<String> labels;
    private float rotation = 0;
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        gestureDetector = new GestureDetector(this, new SwipeGestureListener()); //gesture detector for touch controls

        //inputbuffer setup for camera bitmap processing
        int targetSize = 480;
        int numBytes = 4 * targetSize * targetSize * 3;
        inputBuffer = ByteBuffer.allocateDirect(numBytes).order(ByteOrder.nativeOrder());

        setContentView(R.layout.activity_main);

        //setting AI analysis to a seperate thread
        analysisThread = new HandlerThread("AnalysisThread");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());


        // UI bindings
        overlayView = findViewById(R.id.overlayView);
        View mainView = findViewById(android.R.id.content);
        infoOverlay = findViewById(R.id.info_overlay);
        infoText = findViewById(R.id.info_text);
        infoImage = findViewById(R.id.info_image);
        infoScrollView = findViewById(R.id.info_scroll_view);
        detectionStatus = findViewById(R.id.detection_status);
        previewView = findViewById(R.id.previewView);


        //moving info to OverlayView
        overlayView.setPreviewView(previewView);
        overlayView.setMap(loadInfoFromAssets(this));
        overlayView.setOverlay(infoOverlay, infoText, infoImage, detectionStatus);

        //setting touch screen listeners for main view and infoscrollview
        mainView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
        mainView.setClickable(true);
        mainView.setFocusable(true);
        infoScrollView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false;
        });

        loadLabels();

        // Get actual screen size of overlayView after layout pass
        overlayView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                // Remove listener to prevent repeated calls
                overlayView.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                screenWidth = overlayView.getWidth();
                screenHeight = overlayView.getHeight();
            }
        });
        // Get PreviewView size after layout
        previewView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                previewView.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                int previewWidth = previewView.getWidth();
                int previewHeight = previewView.getHeight();

                MainActivity.this.previewWidth = previewWidth;
                MainActivity.this.previewHeight = previewHeight;
            }
        });
        // Camera permission check
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }

        // get the TFLite model from assets
        AssetManager assetManager = getAssets();
        String fileName = "";
        try {
            String[] assetFiles = assetManager.list("");
            for (String filename : assetFiles) {
                if (filename.endsWith(".tflite")) {
                    fileName = filename;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }


        // Load TFLite model along with optimizations
        try {
            MappedByteBuffer modelBuffer = loadModelFile(fileName);
            if (modelBuffer == null) {
                return;
            }
            GpuDelegate gpuDelegate = null;
            Interpreter.Options options = new Interpreter.Options();
            // Try to use GPU delegate
            try {
                GpuDelegate.Options options1 = new GpuDelegate.Options();
                options1.setPrecisionLossAllowed(true);
                options1.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);

// Enable serialization
                File cacheDir = getCacheDir();
                options1.setSerializationParams(cacheDir.toString(), "yolov11s_fp16");

                gpuDelegate = new GpuDelegate(options1);
                options.addDelegate(gpuDelegate);
            } catch (Exception e) {
                Log.w("MyApp", "GPU delegate failed, falling back to CPU", e);
                options.setNumThreads(4);
            }

            tflite = new Interpreter(modelBuffer, options);
        } catch (IOException e) {
            Log.e("MyApp", "Error running interpreter", e);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                // Setup camera preview
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll(); // Unbind before rebinding

                // Setup image analysis
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                imageAnalysis.setAnalyzer(Runnable::run, image -> analysisHandler.post(() -> analyzeImage(image)));
                // Bind to lifecycle
                Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            } catch (ExecutionException | InterruptedException e) {
                Log.e("MyApp", "Couldnt add listener to camera", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    //converts camera feed into a bitmap to feed to the TFLite model
    private Bitmap toBitmap(ImageProxy image) {
        if (inputAllocation == null) {
            initRenderScript(image.getWidth(), image.getHeight());
        }
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] yuvBytes = new byte[ySize + uSize + vSize];
        yBuffer.get(yuvBytes, 0, ySize);
        vBuffer.get(yuvBytes, ySize, vSize);
        uBuffer.get(yuvBytes, ySize + vSize, uSize);

        inputAllocation.copyFrom(yuvBytes);
        yuvToRgbIntrinsic.setInput(inputAllocation);
        yuvToRgbIntrinsic.forEach(outputAllocation);
        Bitmap bitmap = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        outputAllocation.copyTo(bitmap);

        //pass rotation to overlayView, rotate the bitmap using a matrix to keep it accurate
        //with what the camera is showing
        overlayView.setRotation(rotation);
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);

        Bitmap rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true
        );

        return rotatedBitmap;
    }

    // Checks if all required permissions are granted
    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // Handle permission result
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    // Load TFLite model from assets
    private MappedByteBuffer loadModelFile(String modelName) throws IOException {
        AssetFileDescriptor fileDescriptor = getAssets().openFd(modelName);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    // Preprocess bitmap to 480x480 with padding, normalization
    private PreprocessingResult preprocessBitmap(Bitmap originalBitmap) {
        int targetSize = 480;
        int originalWidth = originalBitmap.getWidth();
        int originalHeight = originalBitmap.getHeight();

        //maintain aspect ratio
        float scaleX = (float) targetSize / originalWidth;
        float scaleY = (float) targetSize / originalHeight;
        float scale = Math.min(scaleX, scaleY);

        //padding to keep the image 480x480 to keep the TFLite model accurate
        int newWidth = Math.round(originalWidth * scale);
        int newHeight = Math.round(originalHeight * scale);
        float padX = (targetSize - newWidth) / 2f;
        float padY = (targetSize - newHeight) / 2f;

        Bitmap resized = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);

        
        inputBuffer.rewind();
        int[] pixels = new int[targetSize * targetSize];
        resized.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize);
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];

            inputBuffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f);
            inputBuffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);
            inputBuffer.putFloat((pixel & 0xFF) / 255.0f);
        }

        //set the results as a new PreprocessingResult instance
        PreprocessingResult result = new PreprocessingResult();
        result.inputBuffer = inputBuffer;
        result.scale = scale;
        result.padX = padX;
        result.padY = padY;
        result.paddedBitmap = resized;
        return result;
    }

    // Runs inference and postprocesses the results
    private List<OverlayView.Detection> runInference(Bitmap bitmap) {
        int originalWidth = bitmap.getWidth();
        int originalHeight = bitmap.getHeight();
        PreprocessingResult prep = preprocessBitmap(bitmap);
        ByteBuffer input = prep.inputBuffer;
        float scale = prep.scale;
        float padX = prep.padX;
        float padY = prep.padY;

        //this needs to match the output of the used TFLite model, it should always be this
        //with nms enabled during the model conversion
        float[][][] output = new float[1][300][6];

        if (tflite == null) {
            return new ArrayList<>();
        }

        //run the model, returns results into the output array
        tflite.run(input, output);

        List<OverlayView.Detection> results = new ArrayList<>();

        //loops through the output array. Doesn't need a nested loop because
        //the 1st and 3rd dimensions are always the same, with the 3rd dimension
        //being the bounding box coordinates, confidence and class id.
        for (int i = 0; i < 300; i++) {
            float x1 = output[0][i][0];
            float y1 = output[0][i][1];
            float x2 = output[0][i][2];
            float y2 = output[0][i][3];
            float confidence = output[0][i][4];
            int classId = (int) output[0][i][5];
            String label = "unknown";

            //skips the loop if confidence is below 0.7
            if (confidence < 0.7f) continue;

            //gets label based on class id
            if (classId >= 0 && classId < labels.size()) {
                label = labels.get(classId);
            }

            // Scale to model's 480×480 padded input
            x1 *= 480;
            x2 *= 480;
            y1 *= 480;
            y2 *= 480;
            // Undo the letterboxing pad
            x1 -= padX;
            x2 -= padX;
            y1 -= padY;
            y2 -= padY;

            // Scale back to original image
            x1 /= scale;
            x2 /= scale;
            y1 /= scale;
            y2 /= scale;

            //clamps to screen bounds
            x1 = clamp(x1, 0, originalWidth);
            x2 = clamp(x2, 0, originalWidth);
            y1 = clamp(y1, 0, originalHeight);
            y2 = clamp(y2, 0, originalHeight);

            //skips if bounding box is off screen
            if (x2 <= x1 || y2 <= y1) {
                continue;
            }
            RectF scaledBox = new RectF(x1, y1, x2, y2);
            results.add(new OverlayView.Detection(scaledBox, label, confidence, classId));
        }
        return results;
    }

    //image analysis is here so that it can be run on a separate thread
    private void analyzeImage(ImageProxy image) {

        //doesn't analyze if inference is happening, to improve performance
        if (isInferenceRunning) {
            image.close();
            return;
        }

        //skips image analysis every X frames, determined by INFERENCE_INTERVAL
        //to improve performance
        frameCounter++;
        if (frameCounter % INFERENCE_INTERVAL != 0) {
            image.close();
            return;
        }

        //gets rotation from camera to apply it correctly during bitmap conversion
        int rotationDegrees = image.getImageInfo().getRotationDegrees();
        if (rotation != rotationDegrees) {
            rotation = (float) rotationDegrees;
        }

        //tracks time taken to run inference, converts camera feed to bitmap,
        //runs inference, dynamically adjusts INFERENCE_INTERVAL to slow down
        //inference if needed for performance.
        isInferenceRunning = true;
        long startNs = System.nanoTime();
        Bitmap bitmap = toBitmap(image);

        List<OverlayView.Detection> detections = runInference(bitmap);

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        int targetMs = 200;
        INFERENCE_INTERVAL = Math.max(1, (int) (targetMs / Math.max(elapsedMs, 1)));
        runOnUiThread(() -> overlayView.setDetections(detections));
        image.close();
        isInferenceRunning = false;
    }

    //destroys analysisthread onDestroy
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tflite != null) tflite.close();
        if (analysisThread != null) {
            analysisThread.quitSafely();
            try {
                analysisThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    private void initRenderScript(int width, int height) {
        rs = RenderScript.create(this);
        yuvToRgbIntrinsic = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));

        Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs)).setX(width * height * 3 / 2);
        Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(width).setY(height);

        inputAllocation = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);
        outputAllocation = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);
    }

    //loads labels from a text file in assets and passes them and the amount of labels to overlayView
    private void loadLabels() {
        labels = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open("labels.txt")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                labels.add(line.trim());
            }
        } catch (IOException e) {
            Log.e("MyApp", "Failed to load labels", e);
        }
        int labelAmount = labels.size();
        overlayView.setIDamount(labelAmount);
        overlayView.setLabelsList(labels);
    }

    //catches the KEYCODE_DPAD_CENTER action and shows info overlay, needed for the Vuzix M4000 AR glasses
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER &&
                event.getAction() == KeyEvent.ACTION_DOWN) {
            overlayView.ShowInfo();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    //catches the Vuzix M4000 AR glasses' inputs and processes them accordingly

    //Left and Right changes the ID in overlayView by 1 or -1 to only show
    //the equivalent class ID from the TFLite models output

    //Up and Down scrolls the info overlay up or down by a certain amount
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int scrollamount = 100;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                overlayView.setID(1);
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                overlayView.setID(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                scrollInfoOverlay(-scrollamount);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                scrollInfoOverlay(scrollamount);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    //detects swipes and taps on mobile devices. swipes change the ID in overlayView by 1 or -1
    //to only show the equivalent class ID from the TFLite models output
    //taps show the info overlay
    private class SwipeGestureListener extends GestureDetector.SimpleOnGestureListener {
        private static final int SWIPE_THRESHOLD = 100;
        private static final int SWIPE_VELOCITY_THRESHOLD = 100;

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            float diffX = e2.getX() - e1.getX();
            float diffY = e2.getY() - e1.getY();
            if (Math.abs(diffX) > Math.abs(diffY)) {
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        overlayView.setID(1);
                    } else {
                        overlayView.setID(-1);
                    }
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
            overlayView.ShowInfo();
            return true;
        }
    }

    //loads the information to show in info overlay from assets into a HashMap
    private Map<Integer, Info> loadInfoFromAssets(Context context) {
        Map<Integer, Info> infoMap = new HashMap<>();
        try {
            InputStream is = context.getAssets().open("instructions.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String jsonString = new String(buffer, StandardCharsets.UTF_8);

            JSONObject json = new JSONObject(jsonString);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject obj = json.getJSONObject(key);
                String text = obj.optString("text", "");
                String imageName = obj.optString("image", "");
                int imageResId = imageName.isEmpty() ? -1 :
                        context.getResources().getIdentifier(imageName.replace(".png", ""), "drawable", context.getPackageName());
                infoMap.put(Integer.parseInt(key), new Info(text, imageResId));
            }
        } catch (IOException | JSONException e) {
            e.printStackTrace();
        }
        return infoMap;
    }

    //scrolls the scrollable info overlay
    private void scrollInfoOverlay(int amount) {
        if (infoScrollView != null) {
            infoScrollView.smoothScrollBy(0, amount);
        }
    }
}
