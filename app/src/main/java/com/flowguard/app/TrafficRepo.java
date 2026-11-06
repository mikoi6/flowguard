package com.flowguard.app;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Process;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 封装系统的 NetworkStatsManager。
 *
 * 两个坑这里都处理了：
 * 1) subscriberId 在不同 Android 版本上能否读取不一样（10 以后普通 App 拿不到 IMSI），
 *    所以按 null -> IMSI 的顺序逐个尝试，谁能用用谁。
 * 2) 各厂商 ROM 偶发 SecurityException，统一抛出，由上层兜底。
 */
public class TrafficRepo {

    public static class AppUsage {
        public final String label;
        public final long bytes;

        public AppUsage(String label, long bytes) {
            this.label = label;
            this.bytes = bytes;
        }
    }

    private final Context ctx;
    private final NetworkStatsManager nsm;

    public TrafficRepo(Context c) {
        ctx = c.getApplicationContext();
        nsm = (NetworkStatsManager) ctx.getSystemService(Context.NETWORK_STATS_SERVICE);
    }

    /** 「使用情况访问权限」是否已授予：读各 App 明细的前提 */
    public boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = ops.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
            } else {
                mode = ops.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 候选 subscriberId：null 一般代表「所有 SIM 合计」 */
    private List<String> subscriberCandidates() {
        List<String> list = new ArrayList<>();
        list.add(null);
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null) {
                String id = tm.getSubscriberId();
                if (id != null && !id.isEmpty() && !list.contains(id)) list.add(id);
            }
        } catch (Exception ignored) {
            // Android 10+ 普通应用读不到，忽略即可
        }
        return list;
    }

    /** 某个时间段的移动数据总量（上行 + 下行，字节） */
    public long mobileBytes(long start, long end) {
        Exception last = null;
        for (String sub : subscriberCandidates()) {
            try {
                NetworkStats.Bucket b =
                        nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, sub, start, end);
                if (b != null) return b.getRxBytes() + b.getTxBytes();
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw new RuntimeException(last);
        return 0L;
    }

    /** 把 [start, start + days) 拆成每天分别查询 */
    public long[] dailyBytes(long start, int days) {
        long[] out = new long[Math.max(days, 0)];
        long now = System.currentTimeMillis();
        for (int i = 0; i < out.length; i++) {
            long s = start + i * Period.DAY;
            if (s > now) break;
            try {
                out[i] = mobileBytes(s, Math.min(s + Period.DAY, now));
            } catch (Exception e) {
                out[i] = 0L;
            }
        }
        return out;
    }

    /**
     * 某一天按小时拆分的用量，返回长度 24 的数组（下标 = 小时）。
     * 系统本身没有「按小时」的接口，只能把一天切成 24 段分别求和，
     * 所以这个方法一定要放后台线程，别在主线程调。
     */
    public long[] hourlyBytes(long dayStart) {
        long[] out = new long[24];
        long now = System.currentTimeMillis();
        for (int h = 0; h < 24; h++) {
            long s = dayStart + h * 3600000L;
            if (s > now) break;
            long e = Math.min(s + 3600000L, now);
            if (e <= s) break;
            try {
                out[h] = mobileBytes(s, e);
            } catch (Exception ex) {
                out[h] = 0L;
            }
        }
        return out;
    }

    /** 各 App 用量排行（需使用情况访问权限） */
    public List<AppUsage> perApp(long start, long end, int limit) {
        List<AppUsage> out = new ArrayList<>();
        if (!hasUsageAccess()) return out;

        Map<Integer, long[]> agg = new HashMap<>();
        NetworkStats stats = null;
        try {
            stats = queryPerUid(start, end);
            if (stats == null) return out;
            NetworkStats.Bucket b = new NetworkStats.Bucket();
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b);
                long v = b.getRxBytes() + b.getTxBytes();
                if (v <= 0) continue;
                long[] box = agg.get(b.getUid());
                if (box == null) {
                    box = new long[1];
                    agg.put(b.getUid(), box);
                }
                box[0] += v;
            }
        } catch (Exception e) {
            return out;
        } finally {
            if (stats != null) {
                try {
                    stats.close();
                } catch (Exception ignored) { }
            }
        }

        for (Map.Entry<Integer, long[]> en : agg.entrySet()) {
            out.add(new AppUsage(labelForUid(en.getKey()), en.getValue()[0]));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.bytes, a.bytes));
        if (out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    private NetworkStats queryPerUid(long start, long end) {
        Exception last = null;
        for (String sub : subscriberCandidates()) {
            try {
                return nsm.querySummary(ConnectivityManager.TYPE_MOBILE, sub, start, end);
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) throw new RuntimeException(last);
        return null;
    }

    private String labelForUid(int uid) {
        PackageManager pm = ctx.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs == null || pkgs.length == 0) {
            if (uid == Process.SYSTEM_UID) return "Android 系统";
            return "系统进程 (UID " + uid + ")";
        }
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkgs[0], 0)).toString();
        } catch (Exception e) {
            return pkgs[0];
        }
    }
}
