package com.flowguard.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 套餐配置 + 上次查询结果的本地缓存（查询失败时兜底展示） */
public class PlanPrefs {

    private static final String FILE = "flowguard_plan";
    private static final String K_DAY = "cycle_day";
    private static final String K_PLAN = "plan_bytes";
    private static final String K_CACHE_TOTAL = "cache_total";
    private static final String K_CACHE_START = "cache_start";
    private static final String K_CACHE_AT = "cache_at";

    public static final long GB = 1024L * 1024L * 1024L;

    private final SharedPreferences sp;

    public PlanPrefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int cycleDay() {
        return clamp(sp.getInt(K_DAY, 1), 1, 31);
    }

    public void setCycleDay(int day) {
        sp.edit().putInt(K_DAY, clamp(day, 1, 31)).apply();
    }

    public long planBytes() {
        long v = sp.getLong(K_PLAN, 30 * GB);
        return v > 0 ? v : 30 * GB;
    }

    public void setPlanBytes(long bytes) {
        sp.edit().putLong(K_PLAN, Math.max(bytes, 1024L * 1024L)).apply();
    }

    public void cache(long cycleStart, long totalBytes) {
        sp.edit()
                .putLong(K_CACHE_START, cycleStart)
                .putLong(K_CACHE_TOTAL, totalBytes)
                .putLong(K_CACHE_AT, System.currentTimeMillis())
                .apply();
    }

    /** 缓存值只在同一个周期内可信 */
    public long cachedTotal(long currentCycleStart) {
        if (sp.getLong(K_CACHE_START, -1) != currentCycleStart) return -1;
        return sp.getLong(K_CACHE_TOTAL, -1);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
