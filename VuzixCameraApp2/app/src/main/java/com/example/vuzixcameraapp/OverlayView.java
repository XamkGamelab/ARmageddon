package com.example.vuzixcameraapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.camera.view.PreviewView;

import java.util.ArrayList;
import java.util.List;

public class OverlayView extends View{
    private final Paint boxPaint;
    private final Paint textPaint;
    private float rotation;
    private int ID = 0;
    private int IDamount = 8;
    public static class Detection{
        public RectF box;
        public String label;
        public float confidence;
        public int ID;

        public Detection(RectF box, String label, float confidence, int ID){
            this.box = box;
            this.label = label;
            this.confidence = confidence;
            this.ID = ID;
        }
    }
    private PreviewView previewView;
    public void setPreviewView(PreviewView previewView){
        this.previewView = previewView;
    }
    private List<Detection> detections = new ArrayList<>();
    public OverlayView(Context context, AttributeSet attrs){
        super(context, attrs);

        boxPaint = new Paint();
        boxPaint.setColor(Color.RED);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(4.0f);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(40f);
        textPaint.setStyle(Paint.Style.FILL);
    }
    public void setDetections(List<Detection> detections){
        this.detections = detections;
        postInvalidate();
    }
    public void setRotation(float rotation){
        this.rotation = rotation;
    }
    public void setID(int ID)
    {
        this.ID += ID;
        if(this.ID > IDamount-1){this.ID = 0;}
        if(this.ID < 0){this.ID = IDamount -1;}
        Log.d("VuzixInput", "current ID: " + this.ID);
    }
    public void setIDamount(int IDamount){this.IDamount = IDamount;}
    @Override
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);

        if(rotation == 180){
            int viewWidth = previewView.getWidth();
            int viewHeight = previewView.getHeight();
            Log.d("Debug", "View size: " + viewWidth + "X" + viewHeight);
            float imageWidth = 640f;
            float imageHeight = 480f;
            float scaleX = viewWidth / imageWidth;
            float scaleY = viewHeight / imageHeight;
            float scale = Math.max(scaleX, scaleY);
            float offsetX = (viewWidth - imageWidth * scale) / 2f;
            float offsetY = (viewHeight - imageHeight * scale) /2f;

            //Log.e("MyApp", "trying to draw rectangle");
            for(Detection detection: detections){
                if(ID != detection.ID){continue;}
                float left = detection.box.left * scale + offsetX;
                float top = detection.box.top * scale + offsetY;
                float right = detection.box.right * scale + offsetX;
                float bottom = detection.box.bottom * scale + offsetY;
                canvas.drawRect(left, top, right, bottom, boxPaint);
                canvas.drawText(detection.label + " " + String.format("%.2f", detection.confidence),
                        left, top - 10, textPaint);
                //Log.e("MyApp", "drawing rectangle");
            }
        }
        if(rotation == 90){
            int viewWidth = previewView.getWidth();
            int viewHeight = previewView.getHeight();
            Log.d("Debug", "View size: " + viewWidth + "X" + viewHeight);
            float imageWidth = 480f;
            float imageHeight = 640f;
            float scaleX = viewWidth / imageWidth;
            float scaleY = viewHeight / imageHeight;
            float scale = Math.max(scaleX, scaleY);
            float offsetX = (viewWidth - imageWidth * scale) / 2f;
            float offsetY = (viewHeight - imageHeight * scale) /2f;

            //Log.e("MyApp", "trying to draw rectangle");
            for(Detection detection: detections){
                if(ID != detection.ID){continue;}
                float left = detection.box.left * scale + offsetX;
                float top = detection.box.top * scale + offsetY;
                float right = detection.box.right * scale + offsetX;
                float bottom = detection.box.bottom * scale + offsetY;
                canvas.drawRect(left, top, right, bottom, boxPaint);
                canvas.drawText(detection.label + " " + String.format("%.2f", detection.confidence),
                        left, top - 10, textPaint);
                //Log.e("MyApp", "drawing rectangle");
            }
        }

    }
}