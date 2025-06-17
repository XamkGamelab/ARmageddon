package com.example.vuzixcameraapp;

import static java.lang.Math.clamp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
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
import android.view.ViewTreeObserver;
import android.widget.ImageView;
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

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    // Constants for permission and inference
    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};
    private static int INFERENCE_INTERVAL = 5; // Run inference every 5 frames
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
    private volatile boolean isInferenceRunning = false;
    private HandlerThread analysisThread;
    private Handler analysisHandler;
    private RenderScript rs;
    private ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic;
    private Allocation inputAllocation;
    private Allocation outputAllocation;
    private ByteBuffer inputBuffer;
    private List<String> labels;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int targetSize = 480;
        int numBytes = 4 * targetSize * targetSize * 3;
        inputBuffer = ByteBuffer.allocateDirect(numBytes).order(ByteOrder.nativeOrder());
        setContentView(R.layout.activity_main);
        analysisThread = new HandlerThread("AnalysisThread");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());
        loadLabels();

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
            }
        });

        previewView = findViewById(R.id.previewView);
        overlayView.setPreviewView(previewView);
        // Get PreviewView size after layout
        previewView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                previewView.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                int previewWidth = previewView.getWidth();
                int previewHeight = previewView.getHeight();

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
        try {
            MappedByteBuffer modelBuffer = loadModelFile("chairnano200-250aug_float16.tflite");
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
        float rotation = 180; // 90 for mobile, 180 for AR
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

    // Preprocess bitmap to 640x640 with padding, normalization
    private PreprocessingResult preprocessBitmap(Bitmap originalBitmap) {
        int targetSize = 480;
        int originalWidth = originalBitmap.getWidth();
        int originalHeight = originalBitmap.getHeight();

        float scaleX = (float) targetSize / originalWidth;
        float scaleY = (float) targetSize / originalHeight;
        float scale = Math.min(scaleX, scaleY); // maintain aspect ratio
        int newWidth = Math.round(originalWidth * scale);
        int newHeight = Math.round(originalHeight * scale);
        float padX = (targetSize - newWidth) / 2f;
        float padY = (targetSize - newHeight) / 2f;

        Bitmap scaled = Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true);
        Bitmap resized = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(resized);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(scaled, padX, padY, null);

        inputBuffer.rewind();
        int[] pixels = new int[targetSize * targetSize];
        resized.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize);
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];

            inputBuffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f);
            inputBuffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);
            inputBuffer.putFloat((pixel & 0xFF) / 255.0f);
        }

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
        float[][][] output = new float[1][300][6];

        if (tflite == null) {
            return new ArrayList<>();
        }
        tflite.run(input, output);

        List<OverlayView.Detection> results = new ArrayList<>();

        for (int i = 0; i < 300; i++) {
            float x1 = output[0][i][0];
            float y1 = output[0][i][1];
            float x2 = output[0][i][2];
            float y2 = output[0][i][3];
            float confidence = output[0][i][4];
            int classId = (int) output[0][i][5];
            String label = "unknown";

            if (confidence < 0.7f) continue;
            if(classId >= 0 && classId < labels.size()){
                label = labels.get(classId);
            }

            // Scale to model's 640×640 padded input
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

            x1 = clamp(x1, 0, originalWidth);
            x2 = clamp(x2, 0, originalWidth);
            y1 = clamp(y1, 0, originalHeight);
            y2 = clamp(y2, 0, originalHeight);
            if (x2 <= x1 || y2 <= y1) {
                continue;
            }
            RectF scaledBox = new RectF(x1, y1, x2, y2);
            results.add(new OverlayView.Detection(scaledBox, label, confidence));
        }
        return results;
    }

    private void analyzeImage(ImageProxy image) {
        if(isInferenceRunning){
            image.close();
            return;
        }
        frameCounter++;
        if(frameCounter % INFERENCE_INTERVAL != 0){
            image.close();
            return;
        }
        isInferenceRunning = true;
        long startNs = System.nanoTime();
        Bitmap bitmap = toBitmap(image);

        List<OverlayView.Detection> detections = runInference(bitmap);

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        int targetMs = 200;
        INFERENCE_INTERVAL = Math.max(1, (int)(targetMs / Math.max(elapsedMs, 1)));
        runOnUiThread(() -> overlayView.setDetections(detections));
        image.close();
        isInferenceRunning = false;
    }

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
    private void loadLabels(){
        labels = new ArrayList<>();
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open("labels.txt")))){
            String line;
            while((line = reader.readLine()) != null){
                labels.add(line.trim());
            }
        } catch (IOException e) {
            Log.e("MyApp", "Failed to load labels", e);
        }
    }
}
