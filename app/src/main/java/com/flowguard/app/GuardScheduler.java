package com.flowguard.app;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * 注册后台巡检任务。
 * WorkManager 的最小周期就是 15 分钟，这也是「短时间」提醒能做到的最细粒度。
 * 用 KEEP 策略，多次调用不会重复排队。
 */
public final class GuardScheduler {

    private static final String WORK_NAME = "flow_guard_tick";

    private GuardScheduler() { }

    public static void ensure(Context c) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                GuardWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().build())
                .build();
        try {
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req);
        } catch (Exception ignored) {
            // WorkManager 初始化异常时不影响前台功能
        }
    }
}
