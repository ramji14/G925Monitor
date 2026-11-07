package com.g925monitor.app;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OverlayService extends Service {

    private static final String ACTION_HIDE =
            "com.g925monitor.app.ACTION_HIDE_HUD";
    private static final String ACTION_SHOW =
            "com.g925monitor.app.ACTION_SHOW_HUD";
    private static final String ACTION_STOP =
            "com.g925monitor.app.ACTION_STOP_HUD";

    private boolean foregroundStarted = false;

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout overlay;

    private TextView gpuText, clockText, tempText;
    private TextView sessionText, headerText;

    // Separate Expanded HUD components.
    private TextView expandedGpuText;
    private TextView expandedClockText;
    private TextView expandedTempText;
    private TextView loadingMetricText;
    private TextView blockingMetricText;
    private TextView idleMetricText;
    private TextView expandedAverageText;
    private TextView expandedPeakText;
    private HistoryGraphView historyGraph;

    private int gpuBlocking = 0;
    private int gpuIdle = 0;
    private int gpuAverage = 0;
    private int gpuPeak = 0;

    private long gpuLoadTotal = 0;
    private long gpuSampleCount = 0;

    private final int[] gpuHistory = new int[40];
    private int gpuHistoryIndex = 0;
    private int gpuHistoryCount = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor();

    private SharedPreferences preferences;

    private boolean expanded = false;
    private boolean attached = false;
    private boolean moved = false;

    private int gpuLoad = 0;
    private int transparency = 90;
    private int refreshInterval = 1000;

    private String gpuClock = "N/A";
    private String gpuTemp = "N/A";

    private long sessionStart;

    private int savedX = 30;
    private int savedY = 250;

    private final String command =
            "cat /sys/kernel/ged/hal/gpu_utilization; " +
            "echo __GPUFREQ__; " +
            "cat /proc/gpufreqv2/gpufreq_status";

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            readGpu();
            updateSessionTimer();
            handler.postDelayed(this, refreshInterval);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        preferences = getSharedPreferences(
                "hud_settings", MODE_PRIVATE);

        sessionStart = SystemClock.elapsedRealtime();

        windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        loadSettings();
        showOverlay();
        createNotification();

        handler.post(refreshTask);
    }

    private PendingIntent notificationIntent(String action, int requestCode) {
        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction(action);

        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createNotification() {
        String channelId = "g925monitor_overlay";

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel =
                    new NotificationChannel(
                            channelId,
                            "G925 Gaming HUD",
                            NotificationManager.IMPORTANCE_LOW);

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }

        Notification.Builder builder =
                Build.VERSION.SDK_INT >= 26
                        ? new Notification.Builder(this, channelId)
                        : new Notification.Builder(this);

        builder.setContentTitle("G925 Monitor")
                .setContentText(
                        attached
                                ? "Gaming HUD is running"
                                : "Gaming HUD is hidden")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true);

        if (attached) {
            builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Hide HUD",
                    notificationIntent(ACTION_HIDE, 101));
        } else {
            builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Show HUD",
                    notificationIntent(ACTION_SHOW, 102));
        }

        builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop HUD",
                notificationIntent(ACTION_STOP, 103));

        Notification notification = builder.build();

        if (!foregroundStarted) {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                        12,
                        notification,
                        android.content.pm.ServiceInfo
                                .FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(12, notification);
            }

            foregroundStarted = true;
        } else {
            NotificationManager manager =
                    (NotificationManager)
                            getSystemService(NOTIFICATION_SERVICE);

            manager.notify(12, notification);
        }
    }

    private void loadSettings() {
        expanded = preferences.getString(
                "hud_mode", "compact").equals("expanded");

        transparency = preferences.getInt(
                "hud_transparency", 90);

        refreshInterval = preferences.getInt(
                "hud_refresh", 1000);

        savedX = preferences.getInt("hud_x", 30);
        savedY = preferences.getInt("hud_y", 250);

        transparency = Math.max(35, Math.min(100, transparency));

        if (refreshInterval != 500 &&
                refreshInterval != 1000 &&
                refreshInterval != 2000) {
            refreshInterval = 1000;
        }
    }

    private GradientDrawable background(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(22);
        bg.setStroke(1, Color.rgb(0, 235, 170));
        return bg;
    }

    private TextView makeText(
            String value, int size, int color) {

        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private void showOverlay() {
        overlay = new LinearLayout(this);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackground(
                background(Color.rgb(15, 25, 39)));

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = savedX;
        params.y = savedY;

        gpuText = makeText("0%", 17,
                Color.rgb(0, 235, 170));

        clockText = makeText("N/A", 12, Color.WHITE);
        tempText = makeText("N/A", 12,
                Color.rgb(255, 181, 75));

        headerText = makeText(
                "● G925  •  GAMING HUD",
                10, Color.rgb(0, 235, 170));

        sessionText = makeText(
                "00:00",
                10, Color.LTGRAY);

        updateMode();

        overlay.setOnTouchListener(
                new View.OnTouchListener() {

            private int startX, startY;
            private float touchX, touchY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {

                switch (event.getAction()) {

                    case MotionEvent.ACTION_DOWN:
                        startX = params.x;
                        startY = params.y;
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        moved = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - touchX);
                        int dy = (int) (event.getRawY() - touchY);

                        if (Math.abs(dx) > 5 ||
                                Math.abs(dy) > 5) {
                            moved = true;
                        }

                        params.x = startX + dx;
                        params.y = startY + dy;

                        if (attached) {
                            windowManager.updateViewLayout(
                                    overlay, params);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            savePosition();
                        } else {
                            expanded = !expanded;

                            preferences.edit()
                                    .putString(
                                            "hud_mode",
                                            expanded
                                                    ? "expanded"
                                                    : "compact")
                                    .apply();

                            updateMode();
                        }
                        return true;
                }

                return true;
            }
        });

        try {
            windowManager.addView(overlay, params);
            attached = true;
        } catch (Exception e) {
            stopSelf();
        }
    }

    private void hideOverlay() {
        if (attached && overlay != null) {
            savePosition();

            try {
                windowManager.removeView(overlay);
            } catch (Exception e) {
                // The view may already have been detached.
            }
        }

        attached = false;
        overlay = null;
    }

    private void addMetric(
            LinearLayout parent,
            TextView child,
            int leftMargin) {

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        -2, -2);

        lp.leftMargin = leftMargin;
        parent.addView(child, lp);
    }

    private int dp(float value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable expandedBackground() {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {
                        Color.rgb(7, 12, 23),
                        Color.rgb(15, 25, 42)
                });
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(115, 255, 45));
        return bg;
    }

    private GradientDrawable metricBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(23, 35, 52));
        bg.setCornerRadius(dp(11));
        bg.setStroke(dp(1), Color.rgb(48, 68, 85));
        return bg;
    }

    private TextView expandedValue(String value, int size, int color) {
        TextView text = makeText(value, size, color);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private LinearLayout metricCard(
            String label, TextView value, int valueColor) {

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(9), dp(10), dp(9));
        card.setBackground(metricBackground());

        TextView title = makeText(
                label, 9, Color.rgb(145, 163, 183));
        title.setLetterSpacing(0.07f);

        value.setTextColor(valueColor);
        value.setTextSize(16);

        card.addView(title);
        card.addView(value);

        return card;
    }

    private void addWeightedCard(
            LinearLayout parent, LinearLayout card) {

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, -2, 1f);

        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        parent.addView(card, lp);
    }

    private void buildExpandedHud() {

        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER_VERTICAL);
        overlay.setPadding(dp(14), dp(13), dp(14), dp(13));
        overlay.setBackground(expandedBackground());

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        headerText.setText("G925 // GAMING HUD");
        headerText.setTextSize(11);
        headerText.setTextColor(Color.rgb(165, 255, 55));

        TextView live = makeText(
                "● LIVE", 9, Color.rgb(165, 255, 55));

        live.setGravity(Gravity.CENTER_VERTICAL);

        header.addView(headerText,
                new LinearLayout.LayoutParams(0, -2, 1f));
        header.addView(live);

        overlay.addView(header);

        // Main GPU load section
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(0, dp(12), 0, dp(10));

        LinearLayout loadSection = new LinearLayout(this);
        loadSection.setOrientation(LinearLayout.VERTICAL);
        loadSection.setGravity(Gravity.CENTER_VERTICAL);

        TextView loadLabel = makeText(
                "GPU LOAD", 10, Color.rgb(145, 163, 183));

        expandedGpuText = expandedValue(
                gpuLoad + "%", 36, Color.rgb(165, 255, 55));

        TextView hardware = makeText(
                "G925 MC11 GPU", 9,
                Color.rgb(145, 163, 183));

        loadSection.addView(loadLabel);
        loadSection.addView(expandedGpuText);
        loadSection.addView(hardware);

        hero.addView(loadSection,
                new LinearLayout.LayoutParams(0, -2, 1.15f));

        LinearLayout hardwareCards = new LinearLayout(this);
        hardwareCards.setOrientation(LinearLayout.VERTICAL);

        expandedClockText = expandedValue(
                gpuClock, 15, Color.WHITE);

        expandedTempText = expandedValue(
                gpuTemp, 15, Color.rgb(255, 174, 65));

        LinearLayout clockCard = metricCard(
                "GPU CLOCK", expandedClockText, Color.WHITE);

        LinearLayout tempCard = metricCard(
                "GPU TEMP", expandedTempText,
                Color.rgb(255, 174, 65));

        hardwareCards.addView(clockCard);
        LinearLayout.LayoutParams tempParams =
                new LinearLayout.LayoutParams(-1, -2);
        tempParams.topMargin = dp(6);
        hardwareCards.addView(tempCard, tempParams);

        hero.addView(hardwareCards,
                new LinearLayout.LayoutParams(0, -2, 1f));

        overlay.addView(hero);

        // Loading / Blocking / Idle
        TextView utilizationTitle = makeText(
                "GPU UTILIZATION BREAKDOWN", 9,
                Color.rgb(145, 163, 183));

        utilizationTitle.setLetterSpacing(0.06f);
        overlay.addView(utilizationTitle);

        LinearLayout utilizationRow = new LinearLayout(this);
        utilizationRow.setOrientation(LinearLayout.HORIZONTAL);

        loadingMetricText = expandedValue(
                gpuLoad + "%", 17, Color.rgb(165, 255, 55));

        blockingMetricText = expandedValue(
                gpuBlocking + "%", 17, Color.rgb(255, 180, 65));

        idleMetricText = expandedValue(
                gpuIdle + "%", 17, Color.rgb(100, 190, 255));

        addWeightedCard(utilizationRow,
                metricCard("LOADING", loadingMetricText,
                        Color.rgb(165, 255, 55)));

        addWeightedCard(utilizationRow,
                metricCard("BLOCKING", blockingMetricText,
                        Color.rgb(255, 180, 65)));

        addWeightedCard(utilizationRow,
                metricCard("IDLE", idleMetricText,
                        Color.rgb(100, 190, 255)));

        overlay.addView(utilizationRow);

        // Session / average / peak analytics
        TextView analyticsTitle = makeText(
                "SESSION ANALYTICS", 9,
                Color.rgb(145, 163, 183));

        analyticsTitle.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams analyticsTitleParams =
                new LinearLayout.LayoutParams(-1, -2);
        analyticsTitleParams.topMargin = dp(9);
        overlay.addView(analyticsTitle, analyticsTitleParams);

        LinearLayout analyticsRow = new LinearLayout(this);
        analyticsRow.setOrientation(LinearLayout.HORIZONTAL);

        expandedAverageText = expandedValue(
                gpuAverage + "%", 15, Color.WHITE);

        expandedPeakText = expandedValue(
                gpuPeak + "%", 15, Color.rgb(165, 255, 55));

        addWeightedCard(analyticsRow,
                metricCard("SESSION TIME", sessionText,
                        Color.WHITE));

        addWeightedCard(analyticsRow,
                metricCard("AVERAGE", expandedAverageText,
                        Color.WHITE));

        addWeightedCard(analyticsRow,
                metricCard("PEAK LOAD", expandedPeakText,
                        Color.rgb(165, 255, 55)));

        overlay.addView(analyticsRow);

        // GPU history graph
        TextView graphTitle = makeText(
                "GPU LOAD HISTORY  /  LAST 40 SAMPLES",
                9, Color.rgb(145, 163, 183));

        LinearLayout.LayoutParams graphTitleParams =
                new LinearLayout.LayoutParams(-1, -2);
        graphTitleParams.topMargin = dp(10);
        overlay.addView(graphTitle, graphTitleParams);

        historyGraph = new HistoryGraphView();

        LinearLayout.LayoutParams graphParams =
                new LinearLayout.LayoutParams(-1, dp(55));
        graphParams.topMargin = dp(5);

        overlay.addView(historyGraph, graphParams);

        updateExpandedMetrics();
    }

    private void updateExpandedMetrics() {
        if (expandedGpuText == null) return;

        expandedGpuText.setText(gpuLoad + "%");
        expandedClockText.setText(gpuClock);
        expandedTempText.setText(gpuTemp);

        loadingMetricText.setText(gpuLoad + "%");
        blockingMetricText.setText(gpuBlocking + "%");
        idleMetricText.setText(gpuIdle + "%");

        expandedAverageText.setText(gpuAverage + "%");
        expandedPeakText.setText(gpuPeak + "%");

        if (historyGraph != null) {
            historyGraph.invalidate();
        }
    }

    private class HistoryGraphView extends View {

        private final Paint graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        HistoryGraphView() {
            super(OverlayService.this);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();

            if (w <= 0 || h <= 0) return;

            graphPaint.setStyle(Paint.Style.STROKE);
            graphPaint.setStrokeWidth(dp(1));
            graphPaint.setColor(Color.rgb(43, 60, 78));

            for (int i = 1; i <= 3; i++) {
                float y = h * i / 4f;
                canvas.drawLine(0, y, w, y, graphPaint);
            }

            if (gpuHistoryCount < 2) return;

            Path path = new Path();

            int oldest = (gpuHistoryIndex - gpuHistoryCount + 40) % 40;

            for (int i = 0; i < gpuHistoryCount; i++) {
                int index = (oldest + i) % 40;
                float x = w * i / (gpuHistoryCount - 1f);
                float y = h - (gpuHistory[index] / 100f) * h;

                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }

            graphPaint.setColor(Color.rgb(165, 255, 55));
            graphPaint.setStrokeWidth(dp(2));
            graphPaint.setStrokeCap(Paint.Cap.ROUND);
            graphPaint.setStrokeJoin(Paint.Join.ROUND);

            canvas.drawPath(path, graphPaint);
        }
    }

    private void detachHudView(View view) {
        if (view != null && view.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) view.getParent()).removeView(view);
        }
    }

    private void updateMode() {
        if (overlay == null) return;

        detachHudView(headerText);
        detachHudView(gpuText);
        detachHudView(clockText);
        detachHudView(tempText);
        detachHudView(sessionText);

        overlay.removeAllViews();

        overlay.setAlpha(transparency / 100f);

        if (expanded) {
            buildExpandedHud();
        } else {
            // Original Compact HUD: intentionally unchanged.
            overlay.setBackground(
                    background(Color.rgb(15, 25, 39)));

            overlay.setOrientation(
                    LinearLayout.HORIZONTAL);

            overlay.setPadding(16, 11, 16, 11);

            overlay.addView(gpuText);

            addMetric(overlay, clockText, 12);
            addMetric(overlay, tempText, 12);
        }

        if (attached) {
            windowManager.updateViewLayout(
                    overlay, params);
        }
    }

    private void savePosition() {
        preferences.edit()
                .putInt("hud_x", params.x)
                .putInt("hud_y", params.y)
                .apply();
    }

    private void updateSessionTimer() {
        if (sessionText == null) return;

        long seconds =
                (SystemClock.elapsedRealtime() - sessionStart)
                        / 1000;

        long minutes = seconds / 60;
        long hours = minutes / 60;

        String time;

        if (hours > 0) {
            time = String.format(
                    Locale.US,
                    "%02d:%02d:%02d",
                    hours, minutes % 60, seconds % 60);
        } else {
            time = String.format(
                    Locale.US,
                    "%02d:%02d",
                    minutes, seconds % 60);
        }

        sessionText.setText(time);
    }

    private final java.util.concurrent.atomic.AtomicBoolean readingGpu =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void readGpu() {
        if (worker.isShutdown()) return;

        if (!readingGpu.compareAndSet(false, true)) return;

        try {
            worker.execute(() -> {
                try {
                Process process = new ProcessBuilder(
                        "su", "-c", command)
                        .redirectErrorStream(true)
                        .start();

                StringBuilder output = new StringBuilder();

                try (BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             process.getInputStream()))) {

                    String line;

                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }

                process.waitFor();

                String[] sections =
                        output.toString().split(
                                "__GPUFREQ__", 2);

                if (sections.length < 2) return;

                String[] values =
                        sections[0].trim().split("\\s+");

                if (values.length < 3) return;

                int load = Integer.parseInt(values[0]);
                int blocking = Integer.parseInt(values[1]);
                int idle = Integer.parseInt(values[2]);

                String gpuStatus = sections[1];

                Matcher frequency = Pattern.compile(
                        "FmeterFreq:\\s*(\\d+)")
                        .matcher(gpuStatus);

                String clock = "N/A";

                if (frequency.find()) {
                    long khz = Long.parseLong(
                            frequency.group(1));

                    clock = String.format(
                            Locale.US,
                            "%.1f MHz",
                            khz / 1000.0);
                }

                Matcher temperature = Pattern.compile(
                        "Temperature:\\s*(-?\\d+)\\s*'C")
                        .matcher(gpuStatus);

                String temp = "N/A";

                if (temperature.find()) {
                    int value = Integer.parseInt(
                            temperature.group(1));

                    if (value > -100) {
                        temp = value + "°C";
                    }
                }

                final int finalLoad =
                        Math.max(0, Math.min(100, load));

                final int finalBlocking =
                        Math.max(0, Math.min(100, blocking));

                final int finalIdle =
                        Math.max(0, Math.min(100, idle));

                final String finalClock = clock;
                final String finalTemp = temp;

                handler.post(() -> {
                    gpuLoad = finalLoad;
                    gpuBlocking = finalBlocking;
                    gpuIdle = finalIdle;

                    gpuClock = finalClock;
                    gpuTemp = finalTemp;

                    // Session average and peak.
                    gpuLoadTotal += gpuLoad;
                    gpuSampleCount++;

                    gpuAverage = (int) (
                            gpuLoadTotal / gpuSampleCount);

                    gpuPeak = Math.max(gpuPeak, gpuLoad);

                    // Rolling 40-sample history.
                    gpuHistory[gpuHistoryIndex] = gpuLoad;
                    gpuHistoryIndex =
                            (gpuHistoryIndex + 1) % 40;

                    if (gpuHistoryCount < 40) {
                        gpuHistoryCount++;
                    }

                    // Original Compact HUD values.
                    if (gpuText != null) {
                        gpuText.setText(gpuLoad + "%");
                        clockText.setText(gpuClock);
                        tempText.setText(gpuTemp);
                    }

                    // Separate Expanded HUD values.
                    updateExpandedMetrics();
                });

                } catch (Exception ignored) {
                } finally {
                    readingGpu.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            readingGpu.set(false);
        }
    }

    @Override
    public int onStartCommand(
            Intent intent, int flags, int startId) {

        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_HIDE.equals(action)) {
            hideOverlay();
            createNotification();
            return START_STICKY;
        }

        if (ACTION_SHOW.equals(action)) {
            loadSettings();

            if (overlay == null || !attached) {
                showOverlay();
            }

            createNotification();
            return START_STICKY;
        }

        loadSettings();

        if (overlay != null && attached) {
            updateMode();
        } else {
            showOverlay();
        }

        createNotification();

        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);

        if (attached && overlay != null) {
            savePosition();
            windowManager.removeView(overlay);
            attached = false;
        }

        worker.shutdownNow();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
