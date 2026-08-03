/*
 * Copyright 2026
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.dumbos.code;

import com.google.zxing.LuminanceSource;

/**
 * Exposes the Y plane of an Android NV21 preview frame to ZXing.
 *
 * NV21 stores the full-resolution luminance plane first, so QR decoding only
 * needs the first width * height bytes. The interleaved chroma plane is ignored.
 */
final class QrYuvLuminanceSource extends LuminanceSource {
    private final byte[] yuvData;
    private final int dataWidth;
    private final int dataHeight;

    QrYuvLuminanceSource(byte[] yuvData, int width, int height) {
        super(width, height);

        if (yuvData == null) {
            throw new NullPointerException("yuvData");
        }
        if (yuvData.length < width * height) {
            throw new IllegalArgumentException("Frame is smaller than its luminance plane");
        }

        this.yuvData = yuvData;
        dataWidth = width;
        dataHeight = height;
    }

    @Override
    public byte[] getRow(int y, byte[] row) {
        if (y < 0 || y >= dataHeight) {
            throw new IllegalArgumentException("Requested row is outside the image: " + y);
        }

        if (row == null || row.length < dataWidth) {
            row = new byte[dataWidth];
        }

        System.arraycopy(yuvData, y * dataWidth, row, 0, dataWidth);
        return row;
    }

    @Override
    public byte[] getMatrix() {
        int luminanceSize = dataWidth * dataHeight;

        if (yuvData.length == luminanceSize) {
            return yuvData;
        }

        byte[] luminance = new byte[luminanceSize];
        System.arraycopy(yuvData, 0, luminance, 0, luminanceSize);
        return luminance;
    }

    @Override
    public boolean isCropSupported() {
        return true;
    }

    @Override
    public LuminanceSource crop(int left, int top, int width, int height) {
        if (left < 0 || top < 0
                || left + width > dataWidth
                || top + height > dataHeight) {
            throw new IllegalArgumentException("Crop rectangle does not fit inside the image");
        }

        byte[] cropped = new byte[width * height];
        int sourceOffset = top * dataWidth + left;

        for (int y = 0; y < height; y++) {
            System.arraycopy(yuvData, sourceOffset, cropped, y * width, width);
            sourceOffset += dataWidth;
        }

        return new QrYuvLuminanceSource(cropped, width, height);
    }

}
