package com.example.vuzixcameraapp;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;

//class for the Preprocessing result used by methods in MainActivity
public class PreprocessingResult {
    public ByteBuffer inputBuffer;
    public float padX, padY;
    public float scale;
    public Bitmap paddedBitmap;
}