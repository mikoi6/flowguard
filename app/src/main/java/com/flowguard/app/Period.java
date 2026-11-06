package com.flowguard.app;

import java.util.Calendar;

/**
 * 运营商的「计费周期」不是自然月。
 * 用户填一个起始日（比如每月 26 号清零），这里负责算出本周期 [start, end) 的毫秒区间。
 */
public final class Period {

    public static final long DAY = 86400000L; // 一天毫秒数（国内无夏令时，可直接算）

    public final long start;
    public final long end; // 开区间

    public Period(long start, long end) {
        this.start = start;
        this.end = end;
    }

    /** 本周期总天数，通常是 30 或 31 */
    public int totalDays() {
        long d = Math.round((end - start) / (double) DAY);
        return (int) Math.max(1L, d);
    }

    /** 今天是本周期第几天（1 开始） */
    public int dayIndex(long now) {
        long d = (now - start) / DAY;
        int idx = (int) Math.max(0L, d) + 1;
        return Math.min(idx, totalDays());
    }

    /** 含今天在内还剩几天 */
    public int daysLeft(long now) {
        return Math.max(1, totalDays() - dayIndex(now) + 1);
    }

    /** 第 i 天（i 从 0 开始）的 0 点时间戳 */
    public long dayStart(int i) {
        return start + i * DAY;
    }

    /** 根据「每月起始日」和当前时间，定位所属周期 */
    public static Period of(int cycleDay, long now) {
        int day = Math.max(1, Math.min(31, cycleDay));

        Calendar cur = Calendar.getInstance();
        cur.setTimeInMillis(now);
        zeroClock(cur);

        Calendar s = (Calendar) cur.clone();
        s.set(Calendar.DAY_OF_MONTH, 1);
        int maxOfCur = s.getActualMaximum(Calendar.DAY_OF_MONTH);
        s.set(Calendar.DAY_OF_MONTH, Math.min(day, maxOfCur));

        // 本月的起始日还没到 -> 退到上一个月
        if (s.getTimeInMillis() > now) {
            s.set(Calendar.DAY_OF_MONTH, 1);
            s.add(Calendar.MONTH, -1);
            int maxOfPrev = s.getActualMaximum(Calendar.DAY_OF_MONTH);
            s.set(Calendar.DAY_OF_MONTH, Math.min(day, maxOfPrev));
        }
        long startMs = s.getTimeInMillis();

        Calendar e = (Calendar) s.clone();
        e.set(Calendar.DAY_OF_MONTH, 1);
        e.add(Calendar.MONTH, 1);
        int maxOfNext = e.getActualMaximum(Calendar.DAY_OF_MONTH);
        e.set(Calendar.DAY_OF_MONTH, Math.min(day, maxOfNext));

        return new Period(startMs, e.getTimeInMillis());
    }

    private static void zeroClock(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }
}
