package com.flowguard.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.Locale;

/** 通知栏提醒的出口，两种场景共用同一个渠道 */
public final class Notifier {

    private static final String CHANNEL_ID = "flow_guard_alert";
    private static final String CHANNEL_NAME = "流量预警";

    private static final int ID_THRESHOLD = 1001;
    private static final int ID_SPEED = 1002;
    /** 单 App 超限用 2000 + uid 的低位，避免和上面两个撞号 */
    private static final int ID_APP_LIMIT = 2000;

    private Notifier() { }

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("套餐用量与流量消耗速度提醒");
        nm.createNotificationChannel(ch);
    }

    /** 套餐用到了设定比例 / 用完了 */
    public static void threshold(Context c, long used, long plan, int pct, int daysLeft, long quota) {
        String title = pct >= 100 ? "套餐流量已用完" : "流量已用 " + pct + "%";
        StringBuilder sb = new StringBuilder();
        sb.append("已用 ").append(Fmt.bytes(used)).append(" / ").append(Fmt.bytes(plan));
        sb.append("，本周期还剩 ").append(daysLeft).append(" 天");
        if (pct < 100 && quota > 0) {
            sb.append("，接下来每天别超过 ").append(Fmt.bytes(quota));
        }
        send(c, ID_THRESHOLD, title, sb.toString());
    }

    /** 两次后台检查之间用得太快 */
    public static void speed(Context c, long delta, long minutes, double mbPerMin) {
        String title = "流量消耗过快";
        String text = "过去 " + minutes + " 分钟用了 " + Fmt.bytes(delta)
                + "，约 " + String.format(Locale.CHINA, "%.0f", mbPerMin) + " MB/分钟";
        send(c, ID_SPEED, title, text);
    }

    /** 单个 App 超过用户设定的额度 */
    public static void appLimit(Context c, int uid, String label, long used, long limit, boolean daily) {
        String name = label == null || label.isEmpty() ? "某个应用" : label;
        String title = name + (daily ? " 今天用超了" : " 本周期用超了");
        String text = (daily ? "今日已用 " : "本周期已用 ") + Fmt.bytes(used)
                + "，超过你设的 " + Fmt.bytes(limit);
        send(c, ID_APP_LIMIT + (uid & 0xFFF), title, text);
    }

    private static void send(Context c, int id, String title, String body) {
        ensureChannel(c);

        Intent it = new Intent(c, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_flow)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pi);

        try {
            NotificationManagerCompat.from(c).notify(id, b.build());
        } catch (SecurityException ignored) {
            // 用户没给通知权限，静默跳过
        }
    }
}
