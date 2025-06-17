package com.example.vuzixcameraapp;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;

public class PreprocessingResult {
    public ByteBuffer inputBuffer;//float[][][][] input;
    public float padX, padY;
    public float scale;
    public Bitmap paddedBitmap;
}