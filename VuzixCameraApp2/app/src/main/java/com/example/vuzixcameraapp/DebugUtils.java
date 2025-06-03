package com.example.vuzixcameraapp;

import android.graphics.*;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class DebugUtils {

    public static void drawDetectionsOnBitmap(Bitmap inputBitmap, List<OverlayView.Detection> detections, String filenameTag) {
        Bitmap mutableBitmap = inputBitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(mutableBitmap);

        Paint boxPaint = new Paint();
        boxPaint.setColor(Color.RED);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4f);

        Paint textPaint = new Paint();
        textPaint.setColor(Color.YELLOW);
        textPaint.setTextSize(32f);
        textPaint.setStyle(Paint.Style.FILL);

        int width = mutableBitmap.getWidth();
        int height = mutableBitmap.getHeight();

        for (OverlayView.Detection detection : detections) {
            RectF box = detection.box;
            /*float left = box.left * width;
            float top = box.top * height;
            float right = box.right * width;
            float bottom = box.bottom * height;*/

            //RectF scaledBox = new RectF(box.left, box.top, box.right, box.bottom);
            canvas.drawRect(box, boxPaint);
            canvas.drawText(detection.label + String.format(" %.2f", detection.confidence), box.left, box.top - 10, textPaint);
        }

        // Save the image to external storage
        saveBitmapToStorage(mutableBitmap, filenameTag);
    }

    private static void saveBitmapToStorage(Bitmap bitmap, String filenameTag) {
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DetectionDebug");

        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                Log.e("DebugUtils", "Failed to create debug directory");
                return;
            }
        }

        // Use a timestamp to create a unique filename
        String timestamp = String.valueOf(System.currentTimeMillis());
        String filename = "detections_" + filenameTag + "_" + timestamp + ".png";

        File file = new File(directory, filename);

        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            Log.i("DebugUtils", "Saved detection image to: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e("DebugUtils", "Failed to save bitmap", e);
        }
    }
}