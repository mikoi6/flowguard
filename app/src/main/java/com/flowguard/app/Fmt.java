package com.flowguard.app;

import java.util.Locale;

/** 单位格式化：把字节数变成人看的 1.23 GB / 456.7 MB */
public final class Fmt {

    private Fmt() { }

    public static String bytes(long b) {
        if (b < 0) b = 0;
        if (b < 1024L) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024.0) return trim(kb) + " KB";
        double mb = kb / 1024.0;
        if (mb < 1024.0) return trim(mb) + " MB";
        return trim(mb / 1024.0) + " GB";
    }

    /** 只取数字部分，用于大字号展示 */
    public static String number(long b) {
        String s = bytes(b);
        int sp = s.indexOf(' ');
        return sp > 0 ? s.substring(0, sp) : s;
    }

    public static String unit(long b) {
        String s = bytes(b);
        int sp = s.indexOf(' ');
        return sp > 0 ? s.substring(sp + 1) : "";
    }

    private static String trim(double v) {
        if (v >= 100) return String.format(Locale.CHINA, "%.0f", v);
        if (v >= 10) return String.format(Locale.CHINA, "%.1f", v);
        return String.format(Locale.CHINA, "%.2f", v);
    }

    public static String pct(double p) {
        return String.format(Locale.CHINA, "%.0f%%", p * 100);
    }
}
