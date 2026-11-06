package com.flowguard.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 提醒相关的配置与状态：
 * - 两个阈值（套餐用量百分比 / 消耗速度 MB 每分钟）
 * - 上一次采样（用来算「两次检查之间用了多少」）
 * - 本周期已经提醒过的等级（防止同一个阈值反复弹）
 */
public class AlertPrefs {

    private static final String FILE = "flowguard_alert";
    private static final String K_WARN = "warn_pct";
    private static final String K_SPEED = "speed_mb_min";
    private static final String K_SAMPLE_BYTES = "sample_bytes";
    private static final String K_SAMPLE_AT = "sample_at";
    private static final String K_SAMPLE_PERIOD = "sample_period";
    private static final String K_ALERTED_PREFIX = "alerted_";

    public static final int DEFAULT_WARN_PCT = 80;
    public static final int DEFAULT_SPEED_MB_MIN = 10;

    private final SharedPreferences sp;

    public AlertPrefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int warnPct() {
        return clamp(sp.getInt(K_WARN, DEFAULT_WARN_PCT), 0, 100);
    }

    public void setWarnPct(int v) {
        sp.edit().putInt(K_WARN, clamp(v, 0, 100)).apply();
    }

    public int speedMbPerMin() {
        return Math.max(0, sp.getInt(K_SPEED, DEFAULT_SPEED_MB_MIN));
    }

    public void setSpeedMbPerMin(int v) {
        sp.edit().putInt(K_SPEED, Math.max(0, v)).apply();
    }

    public void saveSample(long periodStart, long bytes, long at) {
        sp.edit()
                .putLong(K_SAMPLE_BYTES, bytes)
                .putLong(K_SAMPLE_AT, at)
                .putLong(K_SAMPLE_PERIOD, periodStart)
                .apply();
    }

    public long sampleBytes() {
        return sp.getLong(K_SAMPLE_BYTES, -1L);
    }

    public long sampleAt() {
        return sp.getLong(K_SAMPLE_AT, 0L);
    }

    public long samplePeriod() {
        return sp.getLong(K_SAMPLE_PERIOD, -1L);
    }

    public boolean wasAlerted(long periodStart, int level) {
        String cur = "," + sp.getString(K_ALERTED_PREFIX + periodStart, "") + ",";
        return cur.contains("," + level + ",");
    }

    public void markAlerted(long periodStart, int level) {
        if (wasAlerted(periodStart, level)) return;
        String key = K_ALERTED_PREFIX + periodStart;
        String cur = sp.getString(key, "");
        cur = cur.isEmpty() ? String.valueOf(level) : cur + "," + level;

        SharedPreferences.Editor ed = sp.edit().putString(key, cur);
        // 清掉其它周期的记录，避免 key 无限堆积
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith(K_ALERTED_PREFIX) && !k.equals(key)) ed.remove(k);
        }
        ed.apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
