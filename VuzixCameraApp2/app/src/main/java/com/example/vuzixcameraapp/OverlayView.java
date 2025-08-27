package com.example.vuzixcameraapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.camera.view.PreviewView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OverlayView extends View{
    private final Paint boxPaint;
    private final Paint textPaint;
    private float rotation;
    private int ID = -1;
    private int IDamount = 8;
    private Map<Integer, Info> infoMap;
    private FrameLayout infoOverlay;
    private TextView infoText;
    private ImageView infoImage;
    private TextView detection_status;
    private List<String> labels;

    //receives labels from MainActivity
    public void setLabelsList(List<String> labels){
        this.labels = labels;
    }

    //sets overlay based on info from MainActivity
    public void setOverlay(FrameLayout infoOverlay, TextView infoText, ImageView infoImage, TextView detection_status){
        this.infoOverlay = infoOverlay;
        this.infoText = infoText;
        this.infoImage = infoImage;
        this.detection_status = detection_status;
    }
    //receives infoMap from MainActivity
    public void setMap(Map<Integer, Info> infoMap){
        this.infoMap = infoMap;
    }
    //detection class
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
    //receives PreviewView from MainActivity
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
    //receives detections from MainActivity
    public void setDetections(List<Detection> detections){
        this.detections = detections;
        postInvalidate();
    }
    //Receives rotation from MainActivity
    public void setRotation(float rotation){
        this.rotation = rotation;
    }
    //Changes ID based on MainActivity inputs, also updates the shown info
    //and currently detected object based on the received infoMap and current ID
    public void setID(int ID)
    {
        this.ID += ID;
        if(this.ID > IDamount-1){this.ID = -1;}
        if(this.ID < -1){this.ID = IDamount -1;}
        Log.d("VuzixInput", "current ID: " + this.ID);
        Info info = infoMap.get(this.ID);
        updateInfo(info);
        if(this.ID != -1){
            detection_status.setText("Tällä hetkellä tunnistetaan: " + labels.get(this.ID));
        }
        else{
            detection_status.setText("Tällä hetkellä tunnistetaan kaikki");
        }
    }
    //receives max ID amount
    public void setIDamount(int IDamount){this.IDamount = IDamount;}

    //Draws bounding boxes based on received rotation. this only works on portrait
    //mobile devices and the Vuzix M4000 device. this system can and should be improved
    @Override
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);

        if(rotation == 180){
            int viewWidth = previewView.getWidth();
            int viewHeight = previewView.getHeight();

            //this width and height are incorrect, but they only serve to
            //keep the aspect ratio correct, hence why they work
            float imageWidth = 640f;
            float imageHeight = 480f;
            float scaleX = viewWidth / imageWidth;
            float scaleY = viewHeight / imageHeight;
            float scale = Math.max(scaleX, scaleY);
            float offsetX = (viewWidth - imageWidth * scale) / 2f;
            float offsetY = (viewHeight - imageHeight * scale) /2f;

            //draws a bounding box for all detections in the detection list
            //based on the calculations above. It only draws the bounding box
            //for a detection with the same ID as what is wanted to be shown.
            //it also says the label and confidence below the box
            for(Detection detection: detections){
                if(ID != detection.ID && ID != -1){continue;}
                float left = detection.box.left * scale + offsetX;
                float top = detection.box.top * scale + offsetY;
                float right = detection.box.right * scale + offsetX;
                float bottom = detection.box.bottom * scale + offsetY;
                canvas.drawRect(left, top, right, bottom, boxPaint);
                canvas.drawText(detection.label + " " + String.format("%.2f", detection.confidence),
                        left, top - 10, textPaint);
            }
        }
        if(rotation == 90){
            int viewWidth = previewView.getWidth();
            int viewHeight = previewView.getHeight();

            //this width and height are incorrect, but they only serve to
            //keep the aspect ratio correct, hence why they work
            float imageWidth = 480f;
            float imageHeight = 640f;
            float scaleX = viewWidth / imageWidth;
            float scaleY = viewHeight / imageHeight;
            float scale = Math.max(scaleX, scaleY);
            float offsetX = (viewWidth - imageWidth * scale) / 2f;
            float offsetY = (viewHeight - imageHeight * scale) /2f;

            //draws a bounding box for all detections in the detection list
            //based on the calculations above. It only draws the bounding box
            //for a detection with the same ID as what is wanted to be shown.
            //it also says the label and confidence below the box
            for(Detection detection: detections){
                if(ID != detection.ID && ID != -1){continue;}
                float left = detection.box.left * scale + offsetX;
                float top = detection.box.top * scale + offsetY;
                float right = detection.box.right * scale + offsetX;
                float bottom = detection.box.bottom * scale + offsetY;
                canvas.drawRect(left, top, right, bottom, boxPaint);
                canvas.drawText(detection.label + " " + String.format("%.2f", detection.confidence),
                        left, top - 10, textPaint);
            }
        }
    }

    //gets the info from the passed ID and infomap
    public void ShowInfo(){
            Info info = infoMap.get(ID);
            showInfo(info);
    }

    //shows or hides the info overlay with the given info
    private void showInfo(Info info){
        if(infoOverlay.getVisibility() == View.GONE){
            if(info != null){
                if(info.imageResId != -1){
                    infoImage.setImageResource(info.imageResId);
                    infoImage.setVisibility(View.VISIBLE);
                }else{
                    infoImage.setVisibility(View.GONE);
                }
                infoText.setText(info.text);
                infoOverlay.setVisibility(View.VISIBLE);
            }else{
                infoOverlay.setVisibility(View.GONE);
            }
        }
        else{
            infoOverlay.setVisibility(View.GONE);
        }
    }

    //updates the content of the info overlay based on the given info
    private void updateInfo(Info info){
        if(infoOverlay.getVisibility() == View.VISIBLE){
            if(info != null){
                infoText.setText(info.text);
                if(info.imageResId != -1){
                    infoImage.setImageResource(info.imageResId);
                    infoImage.setVisibility(View.VISIBLE);
                }
                else{
                    infoImage.setVisibility(View.GONE);
                }
            }
        }
    }
}