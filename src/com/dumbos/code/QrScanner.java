/*
 * Copyright 2026
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.dumbos.code;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.view.Display;
import android.view.Surface;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Small rear-camera QR scanner using Android's platform camera API and AOSP's
 * in-tree zxing-core module.
 *
 * This class owns one worker thread. Create a new instance for each scan.
 */
@SuppressWarnings("deprecation")
final class QrScanner {
    interface Callback {
        void onQrCode(String value);
        void onError(Exception error);
    }

    private static final long FRAME_WAIT_SLICE_MS = 250;

    private final Activity activity;
    private final Callback callback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object cameraLock = new Object();
    private final MultiFormatReader reader = new MultiFormatReader();

    private Camera camera;

    QrScanner(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(
                DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);
    }

    void start(SurfaceTexture surface, int viewWidth, int viewHeight) {
        if (surface == null) {
            callback.onError(new IllegalArgumentException("Camera surface is null"));
            return;
        }

        if (!running.compareAndSet(false, true)) {
            return;
        }

        executor.execute(() -> runCamera(surface, viewWidth, viewHeight));
    }

    void stop() {
        running.set(false);
        executor.shutdownNow();
        releaseCamera();
    }

    private void runCamera(SurfaceTexture surface, int viewWidth, int viewHeight) {
        try {
            int cameraId = findRearCamera();
            CameraInfo cameraInfo = new CameraInfo();
            Camera.getCameraInfo(cameraId, cameraInfo);

            Camera openedCamera = Camera.open(cameraId);
            synchronized (cameraLock) {
                camera = openedCamera;
            }

            configureCamera(openedCamera, viewWidth, viewHeight);
            setDisplayOrientation(openedCamera, cameraInfo);
            openedCamera.setPreviewTexture(surface);
            openedCamera.startPreview();

            Parameters parameters = openedCamera.getParameters();
            Camera.Size previewSize = parameters.getPreviewSize();

            decodeFrames(openedCamera, previewSize.width, previewSize.height);
        } catch (Exception error) {
            if (running.get()) {
                callback.onError(error);
            }
        } finally {
            running.set(false);
            releaseCamera();
        }
    }

    private int findRearCamera() throws IOException {
        CameraInfo info = new CameraInfo();

        for (int id = 0; id < Camera.getNumberOfCameras(); id++) {
            Camera.getCameraInfo(id, info);
            if (info.facing == CameraInfo.CAMERA_FACING_BACK) {
                return id;
            }
        }

        throw new IOException("No rear camera is available");
    }

    private void configureCamera(Camera target, int viewWidth, int viewHeight) {
        Parameters parameters = target.getParameters();

        Camera.Size previewSize =
                choosePreviewSize(parameters.getSupportedPreviewSizes(), viewWidth, viewHeight);
        parameters.setPreviewSize(previewSize.width, previewSize.height);
        parameters.setPreviewFormat(ImageFormat.NV21);

        List<String> focusModes = parameters.getSupportedFocusModes();
        if (focusModes != null
                && focusModes.contains(Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            parameters.setFocusMode(Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        } else if (focusModes != null && focusModes.contains(Parameters.FOCUS_MODE_AUTO)) {
            parameters.setFocusMode(Parameters.FOCUS_MODE_AUTO);
        }

        List<String> flashModes = parameters.getSupportedFlashModes();
        if (flashModes != null && flashModes.contains(Parameters.FLASH_MODE_OFF)) {
            parameters.setFlashMode(Parameters.FLASH_MODE_OFF);
        }

        target.setParameters(parameters);
    }

    private Camera.Size choosePreviewSize(
            List<Camera.Size> sizes, int viewWidth, int viewHeight) {
        if (sizes == null || sizes.isEmpty()) {
            throw new IllegalStateException("Camera reports no preview sizes");
        }

        if (viewWidth <= 0 || viewHeight <= 0) {
            return sizes.get(0);
        }

        double targetRatio =
                (double) Math.max(viewWidth, viewHeight) / Math.min(viewWidth, viewHeight);
        double targetArea = (double) viewWidth * viewHeight;

        Camera.Size best = sizes.get(0);
        double bestScore = Double.MAX_VALUE;

        for (Camera.Size size : sizes) {
            double ratio = (double) Math.max(size.width, size.height)
                    / Math.min(size.width, size.height);
            double area = (double) size.width * size.height;

            double ratioPenalty = Math.abs(ratio - targetRatio);
            double areaPenalty = Math.abs(Math.log(area / targetArea)) * 0.12;
            double score = ratioPenalty + areaPenalty;

            if (score < bestScore) {
                best = size;
                bestScore = score;
            }
        }

        return best;
    }

    private void setDisplayOrientation(Camera target, CameraInfo info) {
        Display display = activity.getDisplay();
        int rotation = display == null ? Surface.ROTATION_0 : display.getRotation();

        int displayDegrees;
        switch (rotation) {
            case Surface.ROTATION_90:
                displayDegrees = 90;
                break;
            case Surface.ROTATION_180:
                displayDegrees = 180;
                break;
            case Surface.ROTATION_270:
                displayDegrees = 270;
                break;
            case Surface.ROTATION_0:
            default:
                displayDegrees = 0;
                break;
        }

        int result;
        if (info.facing == CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + displayDegrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - displayDegrees + 360) % 360;
        }

        target.setDisplayOrientation(result);
    }

    private void decodeFrames(Camera target, int width, int height)
            throws InterruptedException {
        while (running.get()) {
            AtomicReference<byte[]> frame = new AtomicReference<>();
            CountDownLatch frameReady = new CountDownLatch(1);

            target.setOneShotPreviewCallback((data, ignoredCamera) -> {
                frame.set(data);
                frameReady.countDown();
            });

            while (running.get()
                    && !frameReady.await(FRAME_WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
                // Wake periodically so stop() does not depend solely on interruption.
            }

            if (!running.get()) {
                return;
            }

            byte[] data = frame.get();
            if (data == null) {
                continue;
            }

            Result result = decode(data, width, height);
            if (result != null) {
                running.set(false);
                callback.onQrCode(result.getText());
                return;
            }
        }
    }

    private Result decode(byte[] data, int width, int height) {
        LuminanceSource source = new QrYuvLuminanceSource(data, width, height);

        // QR finder patterns encode orientation, so ZXing can decode the frame
        // without allocating and rotating another full luminance image.
        return decode(source);
    }

    private Result decode(LuminanceSource source) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            return reader.decodeWithState(bitmap);
        } catch (ReaderException ignored) {
            return null;
        } finally {
            reader.reset();
        }
    }

    private void releaseCamera() {
        Camera toRelease;

        synchronized (cameraLock) {
            toRelease = camera;
            camera = null;
        }

        if (toRelease == null) {
            return;
        }

        try {
            toRelease.setPreviewCallback(null);
        } catch (RuntimeException ignored) {
        }

        try {
            toRelease.stopPreview();
        } catch (RuntimeException ignored) {
        }

        toRelease.release();
    }
}
