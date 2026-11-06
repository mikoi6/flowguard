package com.flowguard.app;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
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
 * 三个坑这里都处理了：
 * 1) subscriberId 在不同 Android 版本上能否读取不一样（10 以后普通 App 拿不到 IMSI），
 *    所以按 null -> IMSI 的顺序逐个尝试，谁能用用谁。
 * 2) 各厂商 ROM 偶发 SecurityException，统一抛出，由上层兜底。
 * 3) 包可见性：Android 11+ 起，即使拿到了 uid，PackageManager.getPackagesForUid()
 *    对「不可见」的包也会返回 null —— 这就是之前排行里只能显示 "系统进程 (UID 10316)"
 *    的原因。manifest 里声明了 QUERY_ALL_PACKAGES 之后才能全部解析出来。
 */
public class TrafficRepo {

    public static class AppUsage {
        public final int uid;
        /** 展示名：应用标签；系统进程用中文名；实在解析不出来才退回 UID */
        public final String label;
        /** 包名，null 表示系统进程或已卸载 */
        public final String pkg;
        public final long bytes;

        public AppUsage(int uid, String label, String pkg, long bytes) {
            this.uid = uid;
            this.label = label;
            this.pkg = pkg;
            this.bytes = bytes;
        }
    }

    /**
     * AOSP Process 里的固定 uid，这些不属于任何第三方应用。
     * 用字面量而不是 Process.XXX_UID，因为其中几个常量在公开 SDK 里并不存在。
     */
    private static final Map<Integer, String> SYSTEM_UIDS = new HashMap<>();

    static {
        SYSTEM_UIDS.put(1000, "Android 系统");   // SYSTEM_UID
        SYSTEM_UIDS.put(1001, "电话服务");        // PHONE_UID
        SYSTEM_UIDS.put(1002, "蓝牙");           // BLUETOOTH_UID
        SYSTEM_UIDS.put(1013, "媒体服务");        // MEDIA_UID
        SYSTEM_UIDS.put(2000, "Shell");         // SHELL_UID
    }

    private final Context ctx;
    private final NetworkStatsManager nsm;

    /** uid -> 解析结果，避免每次刷新都重新扫包 */
    private final Map<Integer, Ident> identCache = new HashMap<>();
    /** uid -> ApplicationInfo，getPackagesForUid 失效时的兜底索引 */
    private Map<Integer, ApplicationInfo> installedByUid;

    public static class Ident {
        public final String label;
        public final String pkg;

        Ident(String label, String pkg) {
            this.label = label;
            this.pkg = pkg;
        }
    }

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

        Map<Integer, Long> agg = uidTotals(start, end);
        for (Map.Entry<Integer, Long> en : agg.entrySet()) {
            Ident id = identForUid(en.getKey());
            out.add(new AppUsage(en.getKey(), id.label, id.pkg, en.getValue()));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.bytes, a.bytes));
        if (limit > 0 && out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    /** uid -> 该时间段用量，给「单 App 设限」判断用；一次查询拿到所有 uid */
    public Map<Integer, Long> uidTotals(long start, long end) {
        Map<Integer, Long> agg = new HashMap<>();
        if (!hasUsageAccess()) return agg;

        NetworkStats stats = null;
        try {
            stats = queryPerUid(start, end);
            if (stats == null) return agg;
            NetworkStats.Bucket b = new NetworkStats.Bucket();
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b);
                long v = b.getRxBytes() + b.getTxBytes();
                if (v <= 0) continue;
                Long old = agg.get(b.getUid());
                agg.put(b.getUid(), old == null ? v : old + v);
            }
        } catch (Exception ignored) {
            // 厂商 ROM 可能拒绝查询，返回已收集到的部分
        } finally {
            if (stats != null) {
                try {
                    stats.close();
                } catch (Exception ignored) { }
            }
        }
        return agg;
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

    // ==================== uid -> 应用名 ====================

    public Ident identForUid(int uid) {
        Ident c = identCache.get(uid);
        if (c != null) return c;
        Ident out = resolveIdent(uid);
        identCache.put(uid, out);
        return out;
    }

    private Ident resolveIdent(int uid) {
        PackageManager pm = ctx.getPackageManager();

        // 1) 首选：直接问系统这个 uid 属于哪些包
        String[] pkgs = null;
        try {
            pkgs = pm.getPackagesForUid(uid);
        } catch (Exception ignored) { }

        if (pkgs != null && pkgs.length > 0) {
            String bestPkg = pkgs[0];
            String bestLabel = null;
            for (String p : pkgs) {
                String l = labelOfPackage(pm, p);
                if (l != null && (bestLabel == null || l.length() > bestLabel.length())) {
                    bestPkg = p;
                    bestLabel = l;
                }
            }
            // 共享 uid（如 Google 服务框架）会对应多个包，标注一下数量
            String label = bestLabel != null ? bestLabel : bestPkg;
            if (pkgs.length > 1) label = label + " 等 " + pkgs.length + " 个";
            return new Ident(label, bestPkg);
        }

        // 2) 兜底：扫一遍已安装应用，按 uid 匹配。
        //    ArrayMap 在部分 ROM 上对可见性判断更宽松，能补回个别漏掉的包。
        ApplicationInfo ai = installedIndex().get(uid);
        if (ai != null) {
            CharSequence l = null;
            try {
                l = ai.loadLabel(pm);
            } catch (Exception ignored) { }
            return new Ident(l != null ? l.toString() : ai.packageName, ai.packageName);
        }

        // 3) 系统固定 uid
        String sys = SYSTEM_UIDS.get(uid);
        if (sys != null) return new Ident(sys, null);

        // 4) 真解析不出来（已卸载 / 其他用户空间），至少说明白是什么情况
        return new Ident("未知应用 (UID " + uid + ")", null);
    }

    private Map<Integer, ApplicationInfo> installedIndex() {
        if (installedByUid != null) return installedByUid;
        Map<Integer, ApplicationInfo> map = new HashMap<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> all = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : all) {
                // 同一个 uid 有多个包时，保留标签更「像主应用」的那个
                ApplicationInfo old = map.get(ai.uid);
                if (old == null || score(ai) > score(old)) map.put(ai.uid, ai);
            }
        } catch (Exception ignored) { }
        installedByUid = map;
        return map;
    }

    /** 判断哪个包更像「主应用」：非系统包优先，其次有启动入口的优先 */
    private static int score(ApplicationInfo ai) {
        int s = 0;
        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) s += 2;
        if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) s += 1;
        return s;
    }

    private static String labelOfPackage(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return null;
        }
    }
}
