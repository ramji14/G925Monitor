package com.ramji.immortalis;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout overlay;
    private TextView gpuText, clockText, tempText;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker =
        Executors.newSingleThreadExecutor();

    private boolean expanded = false;
    private boolean attached = false;

    private int gpuLoad = 0;
    private String gpuClock = "N/A";
    private String gpuTemp = "N/A";

    private final String command =
        "cat /sys/kernel/ged/hal/gpu_utilization; " +
        "echo __GPUFREQ__; " +
        "cat /proc/gpufreqv2/gpufreq_status";

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            readGpu();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        createNotification();

        windowManager =
            (WindowManager) getSystemService(WINDOW_SERVICE);

        showOverlay();
        handler.post(refreshTask);
    }

    private void createNotification() {
        String channelId = "immortalis_overlay";

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel =
                new NotificationChannel(
                    channelId,
                    "Immortalis Overlay",
                    NotificationManager.IMPORTANCE_LOW
                );

            NotificationManager manager =
                getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }

        Notification.Builder builder =
            Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this);

        Notification notification = builder
            .setContentTitle("Immortalis Monitor")
            .setContentText("GPU overlay is running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                12,
                notification,
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(12, notification);
        }
    }

    private GradientDrawable background(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(100);
        bg.setStroke(1, Color.rgb(0, 235, 170));
        return bg;
    }

    private TextView makeText(String value, int size, int color) {
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
        overlay.setOrientation(LinearLayout.HORIZONTAL);
        overlay.setPadding(16, 10, 16, 10);
        overlay.setBackground(background(Color.rgb(15, 25, 39)));

        gpuText = makeText("0%", 15, Color.rgb(0, 235, 170));
        overlay.addView(gpuText);

        clockText = makeText("  N/A", 12, Color.WHITE);
        tempText = makeText("  N/A", 12, Color.rgb(255, 181, 75));

        params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 30;
        params.y = 250;

        updateMode();

        try {
            windowManager.addView(overlay, params);
            attached = true;
        } catch (Exception e) {
            stopSelf();
            return;
        }

        overlay.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float touchX, touchY;
            private boolean moved;

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

                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                            moved = true;
                        }

                        params.x = startX + dx;
                        params.y = startY + dy;

                        if (attached) {
                            windowManager.updateViewLayout(
                                overlay, params
                            );
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            expanded = !expanded;
                            updateMode();
                        }
                        return true;
                }
                return true;
            }
        });
    }

    private void updateMode() {
        if (overlay == null) return;

        overlay.removeAllViews();

        if (expanded) {
            overlay.setPadding(16, 10, 16, 10);

            overlay.addView(gpuText);
            overlay.addView(clockText);
            overlay.addView(tempText);
        } else {
            overlay.setPadding(20, 14, 20, 14);
            overlay.addView(gpuText);
        }

        if (attached) {
            windowManager.updateViewLayout(overlay, params);
        }
    }

    private void readGpu() {
        if (worker.isShutdown()) return;

        worker.execute(() -> {
            try {
                Process process = new ProcessBuilder(
                    "su", "-c", command
                ).redirectErrorStream(true).start();

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
                );

                StringBuilder output = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }

                process.waitFor();

                String[] sections =
                    output.toString().split("__GPUFREQ__", 2);

                if (sections.length < 2) return;

                String[] values = sections[0].trim().split("\\s+");

                if (values.length < 3) return;

                int load = Integer.parseInt(values[0]);
                String gpuStatus = sections[1];

                Matcher frequency = Pattern.compile(
                    "FmeterFreq:\\s*(\\d+)"
                ).matcher(gpuStatus);

                String clock = "N/A";

                if (frequency.find()) {
                    long khz = Long.parseLong(frequency.group(1));
                    clock = String.format(
                        Locale.US, "%.1f MHz", khz / 1000.0
                    );
                }

                Matcher temperature = Pattern.compile(
                    "Temperature:\\s*(-?\\d+)\\s*'C"
                ).matcher(gpuStatus);

                String temp = "N/A";

                if (temperature.find()) {
                    int value = Integer.parseInt(
                        temperature.group(1)
                    );

                    if (value > -100) {
                        temp = value + "°C";
                    }
                }

                final int finalLoad = Math.max(0, Math.min(100, load));
                final String finalClock = clock;
                final String finalTemp = temp;

                handler.post(() -> {
                    gpuLoad = finalLoad;
                    gpuClock = finalClock;
                    gpuTemp = finalTemp;

                    if (gpuText != null) {
                        gpuText.setText(gpuLoad + "%");
                        clockText.setText("  " + gpuClock);
                        tempText.setText("  " + gpuTemp);
                    }
                });

            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);

        if (attached && overlay != null) {
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
