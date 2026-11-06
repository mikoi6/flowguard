package com.flowguard.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.Map;

/**
 * 后台每 15 分钟跑一次（WorkManager 的最小周期），做两件事：
 * 1) 用「本次总量 - 上次总量」除以真实经过的分钟数，算出速度，判断是否超速
 * 2) 判断套餐用量是否越过预警线
 *
 * 两个判断都做了「同一个指标只提醒一次」的去重，避免到 80% 之后每 15 分钟弹一次。
 */
public class GuardWorker extends Worker {

    /** 增量小于这个值时不算超速，防止小样本抖动（比如 3 分钟用了 40MB 被判成超速） */
    private static final long MIN_DELTA_FOR_SPEED = 50L * 1024 * 1024;

    public GuardWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        PlanPrefs plan = new PlanPrefs(ctx);
        AlertPrefs alert = new AlertPrefs(ctx);
        TrafficRepo repo = new TrafficRepo(ctx);

        long now = System.currentTimeMillis();
        Period period = Period.of(plan.cycleDay(), now);

        long total;
        try {
            total = repo.mobileBytes(period.start, now);
        } catch (Exception e) {
            // 系统查询失败，本轮跳过，等下次
            return Result.success();
        }

        checkSpeed(ctx, alert, period, total, now);
        checkThreshold(ctx, plan, alert, period, total, now);
        checkAppLimits(ctx, new AppLimitPrefs(ctx), period, now);
        return Result.success();
    }

    private void checkSpeed(Context ctx, AlertPrefs alert, Period period, long total, long now) {
        int limit = alert.speedMbPerMin();
        if (limit <= 0) {
            alert.saveSample(period.start, total, now);
            return;
        }

        long lastAt = alert.sampleAt();
        long lastBytes = alert.sampleBytes();
        boolean samePeriod = alert.samplePeriod() == period.start;

        if (lastAt > 0 && samePeriod && lastBytes >= 0 && now > lastAt) {
            long delta = total - lastBytes;
            long minutes = Math.max(1L, Math.round((now - lastAt) / 60000.0));
            if (delta > 0) {
                double mb = delta / 1048576.0;
                double rate = mb / minutes;
                if (rate >= limit && delta >= MIN_DELTA_FOR_SPEED) {
                    Notifier.speed(ctx, delta, minutes, rate);
                }
            }
        }

        alert.saveSample(period.start, total, now);
    }

    private void checkThreshold(Context ctx, PlanPrefs plan, AlertPrefs alert,
                                Period period, long total, long now) {
        long planBytes = plan.planBytes();
        if (planBytes <= 0) return;

        double ratio = total / (double) planBytes;
        int usedPct = (int) Math.floor(ratio * 100);

        int daysLeft = period.daysLeft(now);
        long remain = Math.max(0L, planBytes - total);
        long quota = remain / Math.max(1, daysLeft);

        int warn = alert.warnPct();
        if (warn > 0 && usedPct >= warn && !alert.wasAlerted(period.start, warn)) {
            Notifier.threshold(ctx, total, planBytes, usedPct, daysLeft, quota);
            alert.markAlerted(period.start, warn);
            return;
        }

        // 用完了无论如何都提醒一次（哪怕用户把预警关掉了）
        if (ratio >= 1.0 && !alert.wasAlerted(period.start, 100)) {
            Notifier.threshold(ctx, total, planBytes, usedPct, daysLeft, 0L);
            alert.markAlerted(period.start, 100);
        }
    }

    /**
     * 逐个检查用户给单个 App 设的上限。
     *
     * 无论设了多少个 App，都只需要两次查询：一次拿「今天」各 uid 的用量，
     * 一次拿「本周期」各 uid 的用量（querySummary 一次就返回所有 uid 的桶）。
     * 没设任何限额时直接返回，不产生额外开销。
     */
    private void checkAppLimits(Context ctx, AppLimitPrefs limits, Period period, long now) {
        Map<Integer, long[]> configured = limits.limits();
        if (configured.isEmpty()) return;

        TrafficRepo repo = new TrafficRepo(ctx);
        if (!repo.hasUsageAccess()) return;

        int todayIdx = period.dayIndex(now);
        if (todayIdx < 1) return;
        long dayStart = period.dayStart(todayIdx - 1);

        Map<Integer, Long> today;
        Map<Integer, Long> cycle;
        try {
            today = repo.uidTotals(dayStart, now);
            cycle = repo.uidTotals(period.start, now);
        } catch (Exception e) {
            return;
        }

        for (Map.Entry<Integer, long[]> en : configured.entrySet()) {
            int uid = en.getKey();
            long dailyLimit = en.getValue()[0];
            long cycleLimit = en.getValue()[1];
            if (dailyLimit <= 0 && cycleLimit <= 0) continue;

            String label = limits.labelOf(uid);
            if (label == null || label.isEmpty()) label = repo.identForUid(uid).label;

            if (dailyLimit > 0) {
                long used = today.containsKey(uid) ? today.get(uid) : 0L;
                if (used >= dailyLimit && !limits.alertedDaily(uid, dayStart)) {
                    Notifier.appLimit(ctx, uid, label, used, dailyLimit, true);
                    limits.markAlertedDaily(uid, dayStart);
                }
            }
            if (cycleLimit > 0) {
                long used = cycle.containsKey(uid) ? cycle.get(uid) : 0L;
                if (used >= cycleLimit && !limits.alertedCycle(uid, period.start)) {
                    Notifier.appLimit(ctx, uid, label, used, cycleLimit, false);
                    limits.markAlertedCycle(uid, period.start);
                }
            }
        }
    }
}
