/*
 * Copyright 2026
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.dumbos.code;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Draws a dimmed camera overlay with a simple square QR target. */
public final class ScannerOverlayView extends View {
    private final Paint shadePaint = new Paint();
    private final Paint cornerPaint = new Paint();

    public ScannerOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);

        shadePaint.setColor(0x88000000);

        cornerPaint.setColor(0xFFFFFFFF);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeCap(Paint.Cap.SQUARE);
        cornerPaint.setStrokeWidth(dp(5));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        float side = Math.min(width, height) * 0.72f;
        float left = (width - side) / 2.0f;
        float top = (height - side) / 2.0f;
        float right = left + side;
        float bottom = top + side;

        canvas.drawRect(0, 0, width, top, shadePaint);
        canvas.drawRect(0, bottom, width, height, shadePaint);
        canvas.drawRect(0, top, left, bottom, shadePaint);
        canvas.drawRect(right, top, width, bottom, shadePaint);

        float corner = side * 0.16f;

        canvas.drawLine(left, top, left + corner, top, cornerPaint);
        canvas.drawLine(left, top, left, top + corner, cornerPaint);

        canvas.drawLine(right, top, right - corner, top, cornerPaint);
        canvas.drawLine(right, top, right, top + corner, cornerPaint);

        canvas.drawLine(left, bottom, left + corner, bottom, cornerPaint);
        canvas.drawLine(left, bottom, left, bottom - corner, cornerPaint);

        canvas.drawLine(right, bottom, right - corner, bottom, cornerPaint);
        canvas.drawLine(right, bottom, right, bottom - corner, cornerPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
