package com.flowguard.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int CHART_DAYS = 14;
    private static final int APP_ROWS = 8;
    private static final int REQ_NOTIFY = 88;

    private PlanPrefs prefs;
    private AlertPrefs alertPrefs;
    private TrafficRepo repo;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean loading = false;

    // 当前展示的计费周期 / 单天明细游标
    private Period currentPeriod;
    private int todayIdx = 1;      // 1 开始
    private int dayCursor = 0;     // 0 开始，相对周期起点
    private int daySeq = 0;
    private int chartBase = 0;
    private long[] dayHours;
    private final Map<Long, long[]> dayCache = new HashMap<>();

    private TextView tvCycle;
    private TextView tvUsedValue;
    private TextView tvPlanHint;
    private TextView tvRingStatus;
    private TextView tvToday;
    private TextView tvQuota;
    private TextView tvDaysLeft;
    private TextView tvForecast;
    private TextView tvAdvice;
    private TextView tvAppsEmpty;
    private TextView tvDayTitle;
    private TextView tvDaySummary;
    private TextView tvDayPick;
    private LinearLayout llApps;
    private View vForecastFill;
    private View vForecastRest;
    private View cardPerm;
    private ImageButton btnDayPrev;
    private ImageButton btnDayNext;
    private RingView ringView;
    private BarChartView barChart;
    private BarChartView barDay;

    private static class Snapshot {
        long total;
        long[] daily = new long[0];
        List<TrafficRepo.AppUsage> apps;
        boolean perm;
        String error;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new PlanPrefs(this);
        alertPrefs = new AlertPrefs(this);
        repo = new TrafficRepo(this);
        bindViews();
        bindActions();

        GuardScheduler.ensure(this);
        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    private void bindViews() {
        tvCycle = findViewById(R.id.tvCycle);
        tvUsedValue = findViewById(R.id.tvUsedValue);
        tvPlanHint = findViewById(R.id.tvPlanHint);
        tvRingStatus = findViewById(R.id.tvRingStatus);
        tvToday = findViewById(R.id.tvToday);
        tvQuota = findViewById(R.id.tvQuota);
        tvDaysLeft = findViewById(R.id.tvDaysLeft);
        tvForecast = findViewById(R.id.tvForecast);
        tvAdvice = findViewById(R.id.tvAdvice);
        tvAppsEmpty = findViewById(R.id.tvAppsEmpty);
        tvDayTitle = findViewById(R.id.tvDayTitle);
        tvDaySummary = findViewById(R.id.tvDaySummary);
        tvDayPick = findViewById(R.id.tvDayPick);
        llApps = findViewById(R.id.llApps);
        vForecastFill = findViewById(R.id.vForecastFill);
        vForecastRest = findViewById(R.id.vForecastRest);
        cardPerm = findViewById(R.id.cardPerm);
        btnDayPrev = findViewById(R.id.btnDayPrev);
        btnDayNext = findViewById(R.id.btnDayNext);
        ringView = findViewById(R.id.ringView);
        barChart = findViewById(R.id.barChart);
        barDay = findViewById(R.id.barDay);
    }

    private void bindActions() {
        ImageButton refresh = findViewById(R.id.btnRefresh);
        ImageButton settings = findViewById(R.id.btnSettings);
        refresh.setOnClickListener(v -> refresh(true));
        settings.setOnClickListener(v -> showSettings());
        findViewById(R.id.btnGrant).setOnClickListener(v -> openUsageAccess());

        btnDayPrev.setOnClickListener(v -> loadDay(dayCursor - 1));
        btnDayNext.setOnClickListener(v -> loadDay(dayCursor + 1));

        // 点近 14 天图上的某根柱 -> 下方单天明细切到那天
        barChart.setOnBarClick(i -> loadDay(chartBase + i));
        barDay.setOnBarClick(this::onDayBarClick);
    }

    // ==================== 取数 ====================

    private void refresh(boolean toast) {
        if (loading) return;
        loading = true;

        final long now = System.currentTimeMillis();
        final Period period = Period.of(prefs.cycleDay(), now);
        final long planBytes = prefs.planBytes();
        final int cycleDays = period.totalDays();

        currentPeriod = period;
        todayIdx = period.dayIndex(now);
        if (dayCursor > todayIdx - 1) dayCursor = todayIdx - 1;
        if (dayCursor < 0) dayCursor = 0;
        dayCache.clear();

        tvCycle.setText("计费周期 " + fmtDate(period.start) + " - " + fmtDate(period.end - 1)
                + " · 共 " + cycleDays + " 天");

        io.execute(() -> {
            Snapshot s = new Snapshot();
            s.perm = repo.hasUsageAccess();

            try {
                s.total = repo.mobileBytes(period.start, now);
                prefs.cache(period.start, s.total);
            } catch (Exception e) {
                long cached = prefs.cachedTotal(period.start);
                s.total = cached > 0 ? cached : 0L;
                s.error = "读取系统流量失败，先显示上次结果";
            }

            s.daily = repo.dailyBytes(period.start, cycleDays);
            s.apps = repo.perApp(period.start, now, APP_ROWS);

            ui.post(() -> {
                loading = false;
                render(s, period, planBytes, now);
                if (toast) {
                    Toast.makeText(this, s.error == null ? "已刷新" : s.error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ==================== 渲染 ====================

    private void render(Snapshot s, Period p, long planBytes, long now) {
        cardPerm.setVisibility(s.perm ? View.GONE : View.VISIBLE);

        double ratio = planBytes > 0 ? s.total / (double) planBytes : 0d;
        int ringColor = ratio >= 1d
                ? ContextCompat.getColor(this, R.color.danger)
                : ratio >= 0.85d
                ? ContextCompat.getColor(this, R.color.warn)
                : ContextCompat.getColor(this, R.color.brand);
        ringView.setProgress((float) ratio, ringColor);

        long remain = Math.max(0L, planBytes - s.total);
        tvUsedValue.setText(Fmt.bytes(s.total));
        tvPlanHint.setText("套餐 " + Fmt.bytes(planBytes));
        tvRingStatus.setText("已用 " + Fmt.pct(ratio) + " · 剩余 " + Fmt.bytes(remain));

        int dayIdx = p.dayIndex(now);
        int left = p.daysLeft(now);
        long todayBytes = dayIdx - 1 < s.daily.length ? s.daily[dayIdx - 1] : 0L;
        long quota = remain / Math.max(left, 1);

        tvToday.setText(Fmt.bytes(todayBytes));
        tvQuota.setText(Fmt.bytes(quota));
        tvDaysLeft.setText(left + " 天");

        // ---- 走势预测 ----
        double avg = s.total / (double) Math.max(dayIdx, 1);
        double forecast = avg * p.totalDays();
        tvForecast.setText("已过 " + dayIdx + "/" + p.totalDays() + " 天，日均 " + Fmt.bytes((long) avg)
                + "；按此速度整周期约 " + Fmt.bytes((long) forecast) + "。");

        setBar(vForecastFill, vForecastRest, (float) Math.min(1d, forecast / (double) planBytes));

        if (ratio >= 1d) {
            tvAdvice.setText("套餐已经超了。去下面的应用排行看看谁在偷跑，把它的后台数据关掉。");
            tvAdvice.setTextColor(ContextCompat.getColor(this, R.color.danger));
        } else if (forecast > planBytes) {
            long over = (long) (forecast - planBytes);
            tvAdvice.setText("按这个节奏会超出约 " + Fmt.bytes(over) + "。想兜住，接下来每天控制在 "
                    + Fmt.bytes(quota) + " 以内。");
            tvAdvice.setTextColor(ContextCompat.getColor(this, R.color.warn));
        } else {
            long leftBytes = (long) Math.max(0d, planBytes - forecast);
            tvAdvice.setText("节奏正常，按当前速度月底大概还剩 " + Fmt.bytes(leftBytes) + "。");
            tvAdvice.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        }

        // ---- 柱状图：只画到今天 ----
        int endIdx = Math.min(dayIdx, s.daily.length);
        int n = Math.min(CHART_DAYS, endIdx);
        if (n > 0) {
            long[] vals = new long[n];
            String[] labels = new String[n];
            SimpleDateFormat df = new SimpleDateFormat("d日", Locale.CHINA);
            chartBase = endIdx - n;
            for (int i = 0; i < n; i++) {
                int src = chartBase + i;
                vals[i] = s.daily[src];
                labels[i] = (i % 2 == 0) ? df.format(new Date(p.dayStart(src))) : null;
            }
            barChart.setData(vals, labels);
        } else {
            barChart.setData(new long[0], new String[0]);
        }

        renderApps(s.apps, s.perm);

        // ---- 单天明细（异步，会自己刷新） ----
        loadDay(dayCursor);
    }

    private void renderApps(List<TrafficRepo.AppUsage> apps, boolean perm) {
        llApps.removeAllViews();
        if (apps == null || apps.isEmpty()) {
            tvAppsEmpty.setVisibility(View.VISIBLE);
            tvAppsEmpty.setText(perm
                    ? "本周期还没有应用产生移动流量。"
                    : "授权「使用情况访问权限」后，可看到每个 App 用了多少流量。");
            return;
        }
        tvAppsEmpty.setVisibility(View.GONE);

        long top = Math.max(1L, apps.get(0).bytes);
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < apps.size(); i++) {
            TrafficRepo.AppUsage a = apps.get(i);
            View row = inf.inflate(R.layout.item_app_usage, llApps, false);
            ((TextView) row.findViewById(R.id.tvRank)).setText(String.valueOf(i + 1));
            ((TextView) row.findViewById(R.id.tvAppName)).setText(a.label);
            ((TextView) row.findViewById(R.id.tvAppSize)).setText(Fmt.bytes(a.bytes));
            setBar(row.findViewById(R.id.vFill), row.findViewById(R.id.vRest),
                    (float) (a.bytes / (double) top));
            llApps.addView(row);
        }
    }

    // ==================== 单天明细 ====================

    private void loadDay(int idx) {
        if (currentPeriod == null) return;
        int maxIdx = Math.max(0, todayIdx - 1);
        if (idx < 0) idx = 0;
        if (idx > maxIdx) idx = maxIdx;
        dayCursor = idx;

        final long dayStart = currentPeriod.dayStart(idx);
        final boolean isToday = idx == maxIdx;

        tvDayTitle.setText(dayTitle(idx));
        btnDayPrev.setEnabled(idx > 0);
        btnDayPrev.setAlpha(idx > 0 ? 1f : 0.28f);
        btnDayNext.setEnabled(idx < maxIdx);
        btnDayNext.setAlpha(idx < maxIdx ? 1f : 0.28f);

        // 历史日期查过就不再查（当天数据会变，不缓存）
        long[] cached = dayCache.get(dayStart);
        if (cached != null && !isToday) {
            renderDay(cached);
            return;
        }

        final int seq = ++daySeq;
        tvDaySummary.setText("正在按小时统计…");
        tvDayPick.setVisibility(View.GONE);
        barDay.setData(new long[0], new String[0]);

        io.execute(() -> {
            long[] hours = repo.hourlyBytes(dayStart);
            ui.post(() -> {
                if (seq != daySeq) return; // 用户已经切到别的日子了，丢弃这次结果
                dayCache.put(dayStart, hours);
                renderDay(hours);
            });
        });
    }

    private void renderDay(long[] hours) {
        dayHours = hours;
        long sum = 0;
        int peak = -1;
        for (int i = 0; i < hours.length; i++) {
            sum += hours[i];
            if (hours[i] > 0 && (peak < 0 || hours[i] > hours[peak])) peak = i;
        }

        String[] labels = new String[24];
        for (int i = 0; i < 24; i++) {
            if (i % 3 == 0) labels[i] = String.valueOf(i);
        }
        barDay.setData(hours, labels);

        if (sum <= 0) {
            tvDaySummary.setText("这天没有消耗移动数据（Wi-Fi 不计入）。");
        } else {
            String s = "全天 " + Fmt.bytes(sum);
            if (peak >= 0) {
                s += " · 高峰 " + hh(peak) + "-" + hh(peak + 1) + " 用了 " + Fmt.bytes(hours[peak]);
            }
            tvDaySummary.setText(s);
        }
        tvDayPick.setVisibility(View.GONE);
    }

    private void onDayBarClick(int i) {
        if (dayHours == null || i < 0 || i >= dayHours.length) return;
        barDay.setHighlight(i);
        tvDayPick.setVisibility(View.VISIBLE);
        tvDayPick.setText(hh(i) + " - " + hh(i + 1) + " · " + Fmt.bytes(dayHours[i]));
    }

    private String dayTitle(int idx) {
        if (currentPeriod == null) return "";
        String d = new SimpleDateFormat("M月d日", Locale.CHINA)
                .format(new Date(currentPeriod.dayStart(idx)));
        return idx == todayIdx - 1 ? "今天 " + d : d;
    }

    // ==================== 交互 ====================

    /** 用 weight 做进度条，避免再引一个 ProgressBar drawable */
    private void setBar(View fill, View rest, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        LinearLayout.LayoutParams p1 = (LinearLayout.LayoutParams) fill.getLayoutParams();
        LinearLayout.LayoutParams p2 = (LinearLayout.LayoutParams) rest.getLayoutParams();
        p1.weight = r;
        p2.weight = 1f - r;
        fill.setLayoutParams(p1);
        rest.setLayoutParams(p2);
    }

    private void openUsageAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "请到「设置 - 应用 - 特殊权限」里手动授权", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            }
        }
    }

    private void showSettings() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        final TextInputLayout tilPlan = v.findViewById(R.id.tilPlan);
        final TextInputLayout tilDay = v.findViewById(R.id.tilDay);
        final TextInputLayout tilWarn = v.findViewById(R.id.tilWarn);
        final TextInputLayout tilSpeed = v.findViewById(R.id.tilSpeed);
        final TextInputEditText etPlan = v.findViewById(R.id.etPlan);
        final TextInputEditText etDay = v.findViewById(R.id.etDay);
        final TextInputEditText etWarn = v.findViewById(R.id.etWarn);
        final TextInputEditText etSpeed = v.findViewById(R.id.etSpeed);

        etPlan.setText(String.format(Locale.CHINA, "%.2f", prefs.planBytes() / (double) PlanPrefs.GB));
        etDay.setText(String.valueOf(prefs.cycleDay()));
        etWarn.setText(String.valueOf(alertPrefs.warnPct()));
        etSpeed.setText(String.valueOf(alertPrefs.speedMbPerMin()));

        final AlertDialog dlg = new MaterialAlertDialogBuilder(this)
                .setTitle("套餐与提醒")
                .setView(v)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();

        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(b -> {
                    double gb = parseDouble(text(etPlan));
                    int day = parseInt(text(etDay));
                    int warn = parseInt(text(etWarn));
                    int speed = parseInt(text(etSpeed));

                    boolean ok = true;
                    if (gb <= 0 || gb > 100000) {
                        tilPlan.setError("填 0.1 ~ 100000 之间的数字");
                        ok = false;
                    } else {
                        tilPlan.setError(null);
                    }
                    if (day < 1 || day > 31) {
                        tilDay.setError("填 1 ~ 31 之间的整数");
                        ok = false;
                    } else {
                        tilDay.setError(null);
                    }
                    if (warn < 0 || warn > 100) {
                        tilWarn.setError("填 0 ~ 100（0 = 关闭）");
                        ok = false;
                    } else {
                        tilWarn.setError(null);
                    }
                    if (speed < 0 || speed > 1000) {
                        tilSpeed.setError("填 0 ~ 1000（0 = 关闭）");
                        ok = false;
                    } else {
                        tilSpeed.setError(null);
                    }
                    if (!ok) return;

                    prefs.setPlanBytes((long) (gb * PlanPrefs.GB));
                    prefs.setCycleDay(day);
                    alertPrefs.setWarnPct(warn);
                    alertPrefs.setSpeedMbPerMin(speed);

                    dlg.dismiss();
                    // 阈值改了，让后台任务按新配置立即跑一次
                    GuardScheduler.ensure(MainActivity.this);
                    refresh(true);
                }));

        dlg.show();
    }

    // ==================== 小工具 ====================

    private static String hh(int hour) {
        return String.format(Locale.CHINA, "%02d:00", hour);
    }

    private static String text(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return -1d;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String fmtDate(long ms) {
        return new SimpleDateFormat("M月d日", Locale.CHINA).format(new Date(ms));
    }
}
