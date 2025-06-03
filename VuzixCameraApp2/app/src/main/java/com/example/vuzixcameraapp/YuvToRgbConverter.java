package com.example.vuzixcameraapp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.media.Image;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

public class YuvToRgbConverter {
    private RenderScript rs;
    private ScriptIntrinsicYuvToRGB yuvToRgbIntrinsic;
    private byte[] yuvBytes;
    private Allocation inputAllocation;
    private Allocation outputAllocation;
    private Matrix rotationMatrix;

    public YuvToRgbConverter(float rotationDegrees) {
        rotationMatrix = new Matrix();
        rotationMatrix.postRotate(rotationDegrees);
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    public Bitmap toBitmap(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) return null;

        int width = image.getWidth();
        int height = image.getHeight();

        // Prepare YUV byte buffer
        int ySize = image.getPlanes()[0].getBuffer().remaining();
        int uSize = image.getPlanes()[1].getBuffer().remaining();
        int vSize = image.getPlanes()[2].getBuffer().remaining();
        int totalSize = ySize + uSize + vSize;

        if (yuvBytes == null || yuvBytes.length < totalSize) {
            yuvBytes = new byte[totalSize];
        }

        image.getPlanes()[0].getBuffer().get(yuvBytes, 0, ySize);
        image.getPlanes()[2].getBuffer().get(yuvBytes, ySize, vSize); // V
        image.getPlanes()[1].getBuffer().get(yuvBytes, ySize + vSize, uSize); // U

        // Allocate input
        Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs)).setX(totalSize);
        inputAllocation = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);
        inputAllocation.copyFrom(yuvBytes);

        // Output RGB bitmap
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(width).setY(height);
        outputAllocation = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);

        yuvToRgbIntrinsic.setInput(inputAllocation);
        yuvToRgbIntrinsic.forEach(outputAllocation);
        outputAllocation.copyTo(bitmap);

        // Rotate the bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, width, height, rotationMatrix, true);
    }

    public void release() {
        if (rs != null) rs.destroy();
    }
}