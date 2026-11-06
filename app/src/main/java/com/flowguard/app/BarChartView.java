package com.flowguard.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

/**
 * 迷你柱状图。
 * - labels 允许稀疏：某个位置给 null 就不画刻度（24 小时图只标 0/3/6/9...）
 * - 支持点击某根柱子回调，用来做「点 14 天图上的某天，看那天的小时分布」
 */
public class BarChartView extends View {

    public interface OnBarClick {
        void onBarClick(int index);
    }

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private long[] values = new long[0];
    private String[] labels = new String[0];
    private long max = 1L;
    private float density = 3f;
    private int highlight = -1;
    private OnBarClick barClick;

    private int colorBar;
    private int colorToday;
    private int colorLabel;
    private int colorHighlight;

    public BarChartView(Context c) {
        super(c);
        init();
    }

    public BarChartView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        colorBar = ContextCompat.getColor(getContext(), R.color.brand);
        colorToday = ContextCompat.getColor(getContext(), R.color.brand_dark);
        colorLabel = ContextCompat.getColor(getContext(), R.color.text_tertiary);
        colorHighlight = ContextCompat.getColor(getContext(), R.color.warn);
        labelPaint.setTextSize(9f * density);
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(long[] v, String[] l) {
        values = v == null ? new long[0] : v;
        labels = l == null ? new String[0] : l;
        max = 1L;
        for (long x : values) {
            if (x > max) max = x;
        }
        highlight = -1;
        invalidate();
    }

    /** 单独高亮某一根（比如用户点的那天） */
    public void setHighlight(int index) {
        highlight = index;
        invalidate();
    }

    public void setOnBarClick(OnBarClick listener) {
        barClick = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (values.length == 0) return super.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float slot = (float) getWidth() / values.length;
            if (slot > 0) {
                int idx = (int) (event.getX() / slot);
                if (idx < 0) idx = 0;
                if (idx >= values.length) idx = values.length - 1;
                if (barClick != null) barClick.onBarClick(idx);
                performClick();
            }
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = values.length;
        if (n == 0) {
            labelPaint.setColor(colorLabel);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("暂无数据", getWidth() / 2f, getHeight() / 2f, labelPaint);
            return;
        }

        float labelH = 16f * density;
        float top = 4f * density;
        float chartH = getHeight() - labelH - top;
        if (chartH <= 0) return;

        float slot = (float) getWidth() / n;
        float barW = Math.max(2f * density, slot * 0.52f);
        float radius = Math.min(barW / 2f, 3f * density);

        for (int i = 0; i < n; i++) {
            float cx = slot * i + slot / 2f;
            float h = chartH * (values[i] / (float) max);
            if (values[i] > 0 && h < 2f * density) h = 2f * density;

            if (i == highlight) {
                barPaint.setColor(colorHighlight);
                barPaint.setAlpha(255);
            } else if (i == n - 1 && highlight < 0) {
                barPaint.setColor(colorToday);
                barPaint.setAlpha(values[i] == 0 ? 60 : 255);
            } else {
                barPaint.setColor(colorBar);
                barPaint.setAlpha(values[i] == 0 ? 60 : 230);
            }

            rect.set(cx - barW / 2f, getHeight() - labelH - h, cx + barW / 2f, getHeight() - labelH);
            canvas.drawRoundRect(rect, radius, radius, barPaint);

            if (i < labels.length && labels[i] != null && !labels[i].isEmpty()) {
                labelPaint.setColor(colorLabel);
                labelPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(labels[i], cx, getHeight() - 3f * density, labelPaint);
            }
        }
    }
}
