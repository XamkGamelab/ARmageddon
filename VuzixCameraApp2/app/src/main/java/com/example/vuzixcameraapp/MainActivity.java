package com.example.vuzixcameraapp;

import static java.lang.Math.clamp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.ViewTreeObserver;
import android.view.WindowMetrics;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

public class MainActivity extends AppCompatActivity {

    // Constants for permission and inference
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // UI and processing fields
    private PreviewView previewView;
    private Interpreter tflite;
    private int screenWidth;
    private int screenHeight;
    private OverlayView overlayView;
    private int previewWidth;
    private int previewHeight;
    private ImageView imageView;
    private int frameCounter = 0;
    private static final int INFERENCE_INTERVAL = 5; // Run inference every 5 frames

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // UI bindings
        overlayView = findViewById(R.id.overlayView);
        imageView = findViewById(R.id.debugImageView);

        // Get actual screen size of overlayView after layout pass
        overlayView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                // Remove listener to prevent repeated calls
                overlayView.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                screenWidth = overlayView.getWidth();
                screenHeight = overlayView.getHeight();

                //Log.d("MyApp", "OverlayView size: " + screenWidth + "x" + screenHeight);
            }
        });
        WindowMetrics metrics = getWindowManager().getCurrentWindowMetrics();

        previewView = findViewById(R.id.previewView);
        //Log.d("Debug", "scale: " + previewView.getScaleType());
        overlayView.setPreviewView(previewView);
        // Get PreviewView size after layout
        previewView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                previewView.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                int previewWidth = previewView.getWidth();
                int previewHeight = previewView.getHeight();

                //Log.d("MyApp", "PreviewView size after layout: " + previewWidth + "x" + previewHeight);

                // Store previewWidth and previewHeight somewhere accessible for scaling
                // For example, in your activity fields:
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
        // Load TFLite model
        try{
            MappedByteBuffer modelBuffer = loadModelFile("augs2_float16.tflite");
            if (modelBuffer == null) {
                //Log.e("MyApp", "Model buffer is null! Check assets folder and filename.");
                return; // or handle error properly
            }
            //Log.i("MyApp", "Attempting to load model...");
            GpuDelegate gpuDelegate = null;
            Interpreter.Options options = new Interpreter.Options();
            // Try to use GPU delegate
            try{
                GpuDelegate.Options options1 = new GpuDelegate.Options();
                options1.setPrecisionLossAllowed(true);
                options1.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER);

// Enable serialization
                File cacheDir = getCacheDir(); // or getFilesDir()
                options1.setSerializationParams(cacheDir.toString(), "yolov11s_fp16");

                gpuDelegate = new GpuDelegate(options1); // ← use options1!
                options.addDelegate(gpuDelegate);
                //Log.i("MyApp", "GPU delegate initialized successfully.");
            } catch (Exception e) {
                //Log.e("MyApp", "GPU delegate not supported on this device. Falling back to CPU.", e);
                options.setNumThreads(4); // Fall back to CPU
            }

            tflite = new Interpreter(modelBuffer,options);
            //Log.i("MyApp", "TFLite interpreter created successfully");
        }
        catch(IOException e){
            //Log.e("MyApp", "Error running interpreter", e);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
    YuvToRgbConverter converter = new YuvToRgbConverter(180); //180 for AR, 90 for mobile
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
                imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), image ->{
                    frameCounter++;
                    executor.execute(()-> {
                        try{
                            if (frameCounter % INFERENCE_INTERVAL == 0){
                                Bitmap bitmap = converter.toBitmap(image);
                                List<OverlayView.Detection> detections = runInference(bitmap);
                                //DebugUtils.drawDetectionsOnBitmap(bitmap, detections, "frame123");
                                runOnUiThread(()-> overlayView.setDetections(detections));
                            }
                    }catch (Exception e){
                            //Log.e("MyApp", "Error during analysis", e);
                        } finally {
                            image.close();
                        }
                    });
                    if (frameCounter > 10000) frameCounter = 0;
                });
                // Bind to lifecycle
                Camera camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

// Is this the front camera? Mirror horizontally if so
                boolean isFrontCamera = (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA);
                //Log.d("Camera", "Mirror horizontal: " + isFrontCamera);

// Sensor rotation
                int sensorRotation = camera.getCameraInfo().getSensorRotationDegrees();
                //Log.d("Camera", "Sensor rotation degrees: " + sensorRotation);

// Display rotation
                Display display = previewView.getDisplay();
                int displayRotation = display.getRotation(); // This returns Surface.ROTATION_0, etc.
                int displayRotationDegrees = rotationToDegrees(displayRotation);
                //Log.d("Camera", "Display rotation degrees: " + displayRotationDegrees);
                //Log.e("MyApp", "Camera works");
            } catch (ExecutionException | InterruptedException e) {
                //Log.e("MyApp", "Couldnt add listener to camera",e);
            }
        }, ContextCompat.getMainExecutor(this));
    }
    private Bitmap toBitmap(ImageProxy image){
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        //Log.e("MyApp", "Starting Bitmap process");

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();
        byte[] nv21 = new byte[ySize+uSize+vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize+vSize, uSize);
        YuvImage yuvImage = new YuvImage(nv21,
                ImageFormat.NV21,
                image.getWidth(),
                image.getHeight(),
                null
        );
        //Log.e("MyApp", "Image converted to bitmap");
        float rotation = 180;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0,0,image.getWidth(), image.getHeight()), 100, out);
        byte[] jpegBytes = out.toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);  // 180 on AR glasses
        overlayView.setRotation((int)rotation);
        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
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
    private MappedByteBuffer loadModelFile(String modelName) throws IOException{
        //Log.i("MyApp", "Opening model file: " + modelName);
        AssetFileDescriptor fileDescriptor = getAssets().openFd(modelName);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }
    // Preprocess bitmap to 640x640 with padding, normalization
    private PreprocessingResult preprocessBitmap(Bitmap originalBitmap) {
        int targetSize = 640;
        int originalWidth = originalBitmap.getWidth();
        int originalHeight = originalBitmap.getHeight();

        float scaleX = (float) targetSize / originalWidth;
        float scaleY = (float) targetSize / originalHeight;
        float scale = Math.min(scaleX, scaleY); // maintain aspect ratio
        int newWidth = Math.round(originalWidth * scale);
        int newHeight = Math.round(originalHeight * scale);
        float padX = (targetSize - newWidth) / 2f;
        float padY = (targetSize - newHeight) / 2f;
        //Log.d("Preprocess", "originalWidth=" + originalWidth + ", originalHeight=" + originalHeight);
        //Log.d("Preprocess", "scale=" + scale + ", padX=" + padX + ", padY=" + padY);
        //Log.d("Preprocess", "newWidth=" + newWidth + ", newHeight=" + newHeight);

        Bitmap scaled = Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true);
        Bitmap resized = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(resized);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(scaled, padX, padY, null);

        float[][][][] input = new float[1][targetSize][targetSize][3];
        for (int y = 0; y < targetSize; y++) {
            for (int x = 0; x < targetSize; x++) {
                int px = resized.getPixel(x, y);
                input[0][y][x][0] = ((px >> 16) & 0xFF) / 255.0f;
                input[0][y][x][1] = ((px >> 8) & 0xFF) / 255.0f;
                input[0][y][x][2] = (px & 0xFF) / 255.0f;
            }
        }

        PreprocessingResult result = new PreprocessingResult();
        result.input = input;
        result.scale = scale;
        result.padX = padX;
        result.padY = padY;
        result.paddedBitmap = resized;
        return result;
    }
    // Runs inference and postprocesses the results
    private List<OverlayView.Detection> runInference(Bitmap bitmap){
        int originalWidth = bitmap.getWidth();
        int originalHeight = bitmap.getHeight();
        PreprocessingResult prep = preprocessBitmap(bitmap);
        float[][][][] input = prep.input;
        float scale = prep.scale;
        float padX = prep.padX;
        float padY = prep.padY;
        float [][][] output = new float [1][300][6];
        Bitmap debugBitmap = prep.paddedBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        Canvas canvas = new Canvas(debugBitmap);

        if (tflite == null) {
            //Log.e("MyApp", "TFLite interpreter is null! Cannot run inference.");
            return new ArrayList<>();
        }
        tflite.run(input, output);

        List<OverlayView.Detection> results = new ArrayList<>();
        //Log.e("MyApp", "Starting inference");

        for (int i = 0; i < 300; i++){
            float x1 = output[0][i][0];
            float y1 = output[0][i][1];
            float x2 = output[0][i][2];
            float y2 = output[0][i][3];
            float confidence = output[0][i][4];
            int classId = (int) output[0][i][5];

            if(confidence < 0.5f) continue;
            Log.d("Debug", "class id: " + classId);
            Log.d("Debug", "raw output, x1: " + x1 + " y1: " + y1 + " x2: " +  x2 + " y2: " + y2);

            // Scale to model's 640×640 padded input
            x1 *= 640;
            x2 *= 640;
            y1 *= 640;
            y2 *= 640;
            Log.d("Debug", "after multiplying, x1: " + x1 + " y1: " + y1 + " x2: " +  x2 + " y2: " + y2);
            canvas.drawRect(x1,y1,x2,y2, paint);
// Undo the letterboxing pad
            x1 -= padX;
            x2 -= padX;
            y1 -= padY;
            y2 -= padY;

            Log.d("Debug", "after unpadding, x1: " + x1 + " y1: " + y1 + " x2: " +  x2 + " y2: " + y2);

// Scale back to original image
            x1 /= scale;
            x2 /= scale;
            y1 /= scale;
            y2 /= scale;
            Log.d("Debug", "after scaling, x1: " + x1 + " y1: " + y1 + " x2: " +  x2 + " y2: " + y2);
            Log.d("Debug", "scale: " + scale);

            x1 = clamp(x1, 0, originalWidth);
            x2 = clamp(x2, 0, originalWidth);
            y1 = clamp(y1, 0, originalHeight);
            y2 = clamp(y2, 0, originalHeight);
            if (x2 <= x1 || y2 <= y1) {
                //Log.w("Debug", "Invalid box dimensions after clamping. Skipping.");
                continue;
            }
            /*float screenX1 = x1 * screenWidth / originalWidth;
            float screenY1 = y1 * screenHeight / originalHeight;
            float screenX2 = x2 * screenWidth / originalWidth;
            float screenY2 = y2 * screenHeight / originalHeight;*/
            Log.d("Debug", "after clamping, x1: " + x1 + " y1: " + y1 + " x2: " +  x2 + " y2: " + y2);
            Log.d("Debug", "Screen size: " + screenWidth + "X" + screenHeight);
            Log.d("Debug", "Original image size: " + originalWidth + "X" + originalHeight);
            RectF scaledBox = new RectF(x1, y1, x2, y2);
            results.add(new OverlayView.Detection(scaledBox, "class_" + classId, confidence));
        }
        //imageView.setImageBitmap(debugBitmap);
        Log.d("MyApp", "results: " + results.size());
        Log.e("MyApp", "Inference done");
        return results;
    }
    private int rotationToDegrees(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_0: return 0;
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }
}