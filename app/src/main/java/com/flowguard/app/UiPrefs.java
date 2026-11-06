package com.flowguard.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * 昼夜模式的持久化。
 *
 * 用 AppCompatDelegate.setDefaultNightMode 而不是自己换两套布局：
 * 系统会自动走 values-night 的资源，控件、对话框、状态栏颜色一次性全跟着变，
 * 不用在每个页面里手动判断。
 *
 * 未设置时跟随系统（MODE_NIGHT_FOLLOW_SYSTEM），用户点过切换按钮之后就固定下来。
 */
public final class UiPrefs {

    private static final String FILE = "flowguard_ui";
    private static final String K_NIGHT = "night_mode";

    public static final int FOLLOW_SYSTEM = -100;

    private UiPrefs() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 用户选过的模式；没选过返回 FOLLOW_SYSTEM */
    public static int mode(Context c) {
        return sp(c).getInt(K_NIGHT, FOLLOW_SYSTEM);
    }

    public static void setMode(Context c, int mode) {
        sp(c).edit().putInt(K_NIGHT, mode).apply();
    }

    /** 当前实际是不是深色（跟随系统时由系统配置决定） */
    public static boolean isNight(Context c) {
        int cfg = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return cfg == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 在 setContentView 之前调用。
     * 传进去的值和当前一致时 AppCompatDelegate 不会重建 Activity，所以重复调用是安全的。
     */
    public static void apply(Context c) {
        int m = mode(c);
        AppCompatDelegate.setDefaultNightMode(
                m == FOLLOW_SYSTEM ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM : m);
    }

    /** 切换：亮 -> 暗，暗 -> 亮 */
    public static void toggle(Context c) {
        boolean night = isNight(c);
        int next = night ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES;
        setMode(c, next);
        AppCompatDelegate.setDefaultNightMode(next);
    }

    /** 三态循环：跟随系统 -> 亮 -> 暗 -> 跟随系统，长按按钮时用 */
    public static void cycle(Context c) {
        int m = mode(c);
        int next;
        if (m == FOLLOW_SYSTEM) next = AppCompatDelegate.MODE_NIGHT_NO;
        else if (m == AppCompatDelegate.MODE_NIGHT_NO) next = AppCompatDelegate.MODE_NIGHT_YES;
        else next = FOLLOW_SYSTEM;

        setMode(c, next);
        AppCompatDelegate.setDefaultNightMode(
                next == FOLLOW_SYSTEM ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM : next);
    }
}
