package com.flowguard.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

/** 只负责画那个进度圆环，文字交给布局里的 TextView，省得跟 Canvas 的字宽较劲 */
public class RingView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float sweepDeg = 0f;

    public RingView(Context c) {
        super(c);
        init();
    }

    public RingView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public RingView(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        init();
    }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(ContextCompat.getColor(getContext(), R.color.track));
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(ContextCompat.getColor(getContext(), R.color.brand));
    }

    /**
     * @param progress 0~1
     * @param color    进度色（正常蓝 / 接近超标橙 / 超标红）
     */
    public void setProgress(float progress, int color) {
        float p = progress;
        if (p < 0f) p = 0f;
        if (p > 1f) p = 1f;
        sweepDeg = p * 360f;
        arcPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float stroke = Math.min(w, h) * 0.085f;
        if (stroke < 6f) stroke = 6f;

        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);

        float pad = stroke / 2f + 1f;
        oval.set(pad, pad, w - pad, h - pad);

        canvas.drawArc(oval, 0f, 360f, false, trackPaint);
        if (sweepDeg > 1f) {
            canvas.drawArc(oval, -90f, sweepDeg, false, arcPaint);
        }
    }
}
