package com.flowguard.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * 单个 App 的流量上限。
 *
 * 每个 App（按 uid 记）最多两个额度：
 * - 每日上限（每天 0 点重新开始）
 * - 本周期上限（跟套餐计费周期一起重置）
 * 两个都是「0 或未设置 = 不限」，可以只设一个。
 *
 * 顺带存一份应用名，这样通知里能直接说「抖音今天已用…」，不用再去查包管理器。
 */
public class AppLimitPrefs {

    private static final String FILE = "flowguard_app_limit";
    private static final String P_DAILY = "d_";
    private static final String P_CYCLE = "c_";
    private static final String P_NAME = "n_";
    private static final String P_ALERT_DAY = "ad_";     // + uid + "_" + dayStart
    private static final String P_ALERT_CYCLE = "ac_";   // + uid + "_" + periodStart

    private final SharedPreferences sp;

    public AppLimitPrefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** uid -> [每日上限, 本周期上限]，未设置的项为 0 */
    public Map<Integer, long[]> limits() {
        Map<Integer, long[]> out = new HashMap<>();
        for (Map.Entry<String, ?> en : sp.getAll().entrySet()) {
            String k = en.getKey();
            if (!k.startsWith(P_DAILY)) continue;
            int uid = parseUid(k, P_DAILY);
            if (uid < 0) continue;
            long daily = asLong(en.getValue());
            long cycle = sp.getLong(P_CYCLE + uid, 0L);
            if (daily > 0 || cycle > 0) out.put(uid, new long[]{daily, cycle});
        }
        // 只设了本周期上限的 App 没有 d_ 键，需要单独补一遍
        for (Map.Entry<String, ?> en : sp.getAll().entrySet()) {
            String k = en.getKey();
            if (!k.startsWith(P_CYCLE)) continue;
            int uid = parseUid(k, P_CYCLE);
            if (uid < 0 || out.containsKey(uid)) continue;
            long cycle = asLong(en.getValue());
            if (cycle > 0) out.put(uid, new long[]{0L, cycle});
        }
        return out;
    }

    public long dailyOf(int uid) {
        return Math.max(0L, sp.getLong(P_DAILY + uid, 0L));
    }

    public long cycleOf(int uid) {
        return Math.max(0L, sp.getLong(P_CYCLE + uid, 0L));
    }

    /** 传 0 表示取消该项限制；两个都为 0 时整条记录会被清掉 */
    public void set(int uid, String label, long daily, long cycle) {
        SharedPreferences.Editor ed = sp.edit();
        if (daily > 0) ed.putLong(P_DAILY + uid, daily); else ed.remove(P_DAILY + uid);
        if (cycle > 0) ed.putLong(P_CYCLE + uid, cycle); else ed.remove(P_CYCLE + uid);
        if (daily > 0 || cycle > 0) {
            if (label != null && !label.isEmpty()) ed.putString(P_NAME + uid, label);
        } else {
            ed.remove(P_NAME + uid);
        }
        ed.apply();
    }

    public void clear(int uid) {
        sp.edit()
                .remove(P_DAILY + uid)
                .remove(P_CYCLE + uid)
                .remove(P_NAME + uid)
                .apply();
    }

    public String labelOf(int uid) {
        return sp.getString(P_NAME + uid, null);
    }

    /** 有没有设过任何限制，用来决定要不要在 UI 上提示 */
    public boolean hasAny() {
        return !limits().isEmpty();
    }

    // ---- 提醒去重：同一个 App 的同一种超限，每天 / 每周期只提醒一次 ----

    public boolean alertedDaily(int uid, long dayStart) {
        return sp.getBoolean(P_ALERT_DAY + uid + "_" + dayStart, false);
    }

    public void markAlertedDaily(int uid, long dayStart) {
        SharedPreferences.Editor ed = sp.edit().putBoolean(P_ALERT_DAY + uid + "_" + dayStart, true);
        pruneStale(ed, P_ALERT_DAY, "_" + dayStart);
        ed.apply();
    }

    public boolean alertedCycle(int uid, long periodStart) {
        return sp.getBoolean(P_ALERT_CYCLE + uid + "_" + periodStart, false);
    }

    public void markAlertedCycle(int uid, long periodStart) {
        SharedPreferences.Editor ed = sp.edit().putBoolean(P_ALERT_CYCLE + uid + "_" + periodStart, true);
        pruneStale(ed, P_ALERT_CYCLE, "_" + periodStart);
        ed.apply();
    }

    /** 把上一个记账周期留下的去重标记删掉，避免 key 越攒越多 */
    private void pruneStale(SharedPreferences.Editor ed, String prefix, String keepSuffix) {
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith(prefix) && !k.endsWith(keepSuffix)) ed.remove(k);
        }
    }

    private static int parseUid(String key, String prefix) {
        try {
            return Integer.parseInt(key.substring(prefix.length()));
        } catch (Exception e) {
            return -1;
        }
    }

    private static long asLong(Object v) {
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer) return ((Integer) v).longValue();
        return 0L;
    }
}
