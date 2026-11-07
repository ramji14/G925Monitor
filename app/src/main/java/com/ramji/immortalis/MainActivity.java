package com.ramji.immortalis;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private TextView loading, blocking, idle;
    private TextView frequency, temperature, status;
    private GaugeView gauge;
    private GraphView graph;

    private static final int OVERLAY_PERMISSION_REQUEST = 701;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final String command =
        "cat /sys/kernel/ged/hal/gpu_utilization; " +
        "echo __GPUFREQ__; " +
        "cat /proc/gpufreqv2/gpufreq_status";

    private final int BG = Color.rgb(8, 12, 23);
    private final int CARD = Color.rgb(19, 27, 43);
    private final int CARD2 = Color.rgb(24, 34, 52);
    private final int GREEN = Color.rgb(0, 235, 170);
    private final int MUTED = Color.rgb(145, 158, 180);
    private final int WHITE = Color.WHITE;
    private final int ORANGE = Color.rgb(255, 181, 75);

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        buildDashboard();
        startReading();
    }

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Intent permission = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(permission, OVERLAY_PERMISSION_REQUEST);
            return;
        }

        Intent service = new Intent(this, OverlayService.class);

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }

        Toast.makeText(this, "GPU overlay starting...", Toast.LENGTH_SHORT).show();
    }

    private void stopOverlay() {
        stopService(new Intent(this, OverlayService.class));
        Toast.makeText(this, "GPU overlay stopped", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Settings.canDrawOverlays(this)) {
                startOverlay();
            } else {
                Toast.makeText(
                    this,
                    "Overlay permission not granted",
                    Toast.LENGTH_LONG
                ).show();
            }
        }
    }

    private void startReading() {
        if (worker.isShutdown()) return;

        worker.execute(() -> {
            try {
                Process process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

                StringBuilder output = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }

                process.waitFor();
                parseData(output.toString());

            } catch (Exception e) {
                handler.post(() -> {
                    if (status != null) {
                        status.setText("ROOT ACCESS / READING ERROR");
                    }
                });
            }

            handler.postDelayed(() -> {
                if (!isFinishing() && !worker.isShutdown()) {
                    startReading();
                }
            }, 1000);
        });
    }

    private void parseData(String raw) {
        try {
            String[] sections = raw.split("__GPUFREQ__", 2);
            if (sections.length < 2) {
                throw new IllegalArgumentException("GPU frequency data missing");
            }

            String[] values = sections[0].trim().split("\\s+");
            if (values.length < 3) {
                throw new IllegalArgumentException("GPU utilization data missing");
            }

            int load = Integer.parseInt(values[0]);
            int block = Integer.parseInt(values[1]);
            int idleValue = Integer.parseInt(values[2]);

            String gpuStatus = sections[1];

            // FmeterFreq is reported in kHz by this device's GPUFREQ driver.
            Matcher frequencyMatcher = Pattern.compile(
                "FmeterFreq:\\s*(\\d+)"
            ).matcher(gpuStatus);

            String frequencyText = "N/A";
            if (frequencyMatcher.find()) {
                long frequencyKHz = Long.parseLong(frequencyMatcher.group(1));
                frequencyText = String.format(
                    Locale.US, "%.1f MHz", frequencyKHz / 1000.0);
            }

            // The driver's MFGSYS temperature is reported in degrees Celsius.
            Matcher temperatureMatcher = Pattern.compile(
                "Temperature:\\s*(-?\\d+)\\s*'C"
            ).matcher(gpuStatus);

            String temperatureText = "N/A";
            if (temperatureMatcher.find()) {
                int tempC = Integer.parseInt(temperatureMatcher.group(1));
                if (tempC > -100) {
                    temperatureText = tempC + "°C";
                }
            }

            final String finalFrequency = frequencyText;
            final String finalTemperature = temperatureText;

            handler.post(() -> {
                if (loading == null || isFinishing()) return;

                loading.setText(load + "%");
                blocking.setText(block + "%");
                idle.setText(idleValue + "%");

                frequency.setText(finalFrequency);
                temperature.setText(finalTemperature);

                gauge.setValue(load);
                graph.addValue(load);

                status.setText("● LIVE  •  Refreshing every second");
            });

        } catch (Exception e) {
            handler.post(() -> {
                if (status != null) {
                    status.setText("WAITING FOR VALID TELEMETRY");
                }
                if (frequency != null) frequency.setText("N/A");
                if (temperature != null) temperature.setText("N/A");
            });
        }
    }

    private void buildDashboard() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Extra top padding moves the title farther below the status bar.
        root.setPadding(dp(20), dp(40), dp(20), dp(28));
        root.setBackgroundColor(BG);

        scroll.addView(root);
        setContentView(scroll);

        // HEADER
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout branding = new LinearLayout(this);
        branding.setOrientation(LinearLayout.VERTICAL);

        TextView title = text("IMMORTALIS", 25, WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        TextView subtitle = text("GPU PERFORMANCE MONITOR", 10, MUTED);
        subtitle.setLetterSpacing(0.12f);

        branding.addView(title);
        branding.addView(subtitle);

        header.addView(branding, new LinearLayout.LayoutParams(0, -2, 1));

        TextView live = text("● LIVE", 11, GREEN);
        live.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        live.setPadding(dp(12), dp(8), dp(12), dp(8));
        live.setBackground(round(CARD2, 30));

        header.addView(live);
        root.addView(header);

        addSpace(root, 25);

        // DEVICE INFO
        TextView device = text(
            "POCO X8 PRO MAX  •  IMMORTALIS-G925 MC11",
            10, MUTED);
        device.setLetterSpacing(0.04f);
        root.addView(device);

        addSpace(root, 18);

        // MAIN GPU CARD
        LinearLayout mainCard = card();
        mainCard.setGravity(Gravity.CENTER);
        mainCard.setPadding(dp(15), dp(22), dp(15), dp(22));

        TextView section = text("GPU UTILIZATION", 12, MUTED);
        section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        section.setLetterSpacing(0.15f);

        mainCard.addView(section);
        addSpace(mainCard, 14);

        gauge = new GaugeView();
        mainCard.addView(gauge,
            new LinearLayout.LayoutParams(dp(190), dp(190)));

        addSpace(mainCard, 10);

        TextView gaugeCaption = text("LIVE GPU LOAD", 10, MUTED);
        gaugeCaption.setLetterSpacing(0.15f);
        mainCard.addView(gaugeCaption);

        root.addView(mainCard);

        addSpace(root, 18);

        // UTILIZATION METRICS
        addSection(root, "UTILIZATION BREAKDOWN");

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);

        loading = addMetric(metrics, "LOADING", "--%", GREEN);
        blocking = addMetric(metrics, "BLOCKING", "--%", ORANGE);
        idle = addMetric(metrics, "IDLE", "--%", WHITE);

        root.addView(metrics);

        addSpace(root, 20);

        // FREQUENCY CARD
        addSection(root, "GPU FREQUENCY");

        LinearLayout freqCard = card();
        freqCard.setPadding(dp(18), dp(16), dp(18), dp(16));

        TextView freqLabel = text(
            "HARDWARE-MEASURED CLOCK • FMETER", 10, MUTED);
        freqLabel.setLetterSpacing(0.06f);

        frequency = text("-- MHz", 29, GREEN);
        frequency.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        freqCard.addView(freqLabel);
        addSpace(freqCard, 8);
        freqCard.addView(frequency);

        root.addView(freqCard);

        addSpace(root, 18);

        // THERMAL CARD
        addSection(root, "THERMAL INFORMATION");

        LinearLayout thermalCard = card();
        thermalCard.setPadding(dp(18), dp(16), dp(18), dp(16));

        TextView thermalLabel = text(
            "GPU DRIVER TEMPERATURE", 10, MUTED);
        thermalLabel.setLetterSpacing(0.08f);

        temperature = text("Waiting...", 22, ORANGE);
        temperature.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        thermalCard.addView(thermalLabel);
        addSpace(thermalCard, 8);
        thermalCard.addView(temperature);

        root.addView(thermalCard);

        addSpace(root, 20);

        // GRAPH
        addSection(root, "GPU ACTIVITY HISTORY");

        LinearLayout graphCard = card();
        graphCard.setPadding(dp(12), dp(15), dp(12), dp(12));

        graph = new GraphView();
        graphCard.addView(graph,
            new LinearLayout.LayoutParams(-1, dp(180)));

        TextView graphCaption = text(
            "LAST 40 SAMPLES  •  1 SECOND INTERVAL",
            9, MUTED);
        graphCaption.setPadding(0, dp(10), 0, 0);

        graphCard.addView(graphCaption);
        root.addView(graphCard);

        addSpace(root, 20);

        // STATUS
        status = text("● CONNECTING TO GPU TELEMETRY...", 11, GREEN);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setGravity(Gravity.CENTER);
        root.addView(status);
        // FLOATING GPU OVERLAY CONTROLS
        addSpace(root, 12);

        LinearLayout overlayControls = new LinearLayout(this);
        overlayControls.setOrientation(LinearLayout.HORIZONTAL);
        overlayControls.setGravity(Gravity.CENTER);
        overlayControls.setPadding(0, dp(4), 0, dp(4));

        Button startButton = new Button(this);
        startButton.setText("START OVERLAY");
        startButton.setTextColor(Color.BLACK);
        startButton.setTextSize(12);
        startButton.setAllCaps(false);
        startButton.setBackground(round(GREEN, 14));
        startButton.setOnClickListener(v -> startOverlay());

        Button stopButton = new Button(this);
        stopButton.setText("STOP OVERLAY");
        stopButton.setTextColor(WHITE);
        stopButton.setTextSize(12);
        stopButton.setAllCaps(false);
        stopButton.setBackground(round(CARD2, 14));
        stopButton.setOnClickListener(v -> stopOverlay());

        LinearLayout.LayoutParams buttonParams =
            new LinearLayout.LayoutParams(0, dp(54), 1);
        buttonParams.setMargins(dp(5), 0, dp(5), 0);

        overlayControls.addView(startButton, buttonParams);
        overlayControls.addView(stopButton, buttonParams);

        root.addView(overlayControls);

        // IMMORTALIS GAMING HUD SETTINGS
        addSpace(root, 18);
        addSection(root, "GAMING HUD SETTINGS");

        android.content.SharedPreferences hudPrefs =
            getSharedPreferences("hud_settings", MODE_PRIVATE);

        LinearLayout hudSettings = card();
        hudSettings.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView modeLabel = text("DEFAULT HUD LAYOUT", 10, MUTED);
        modeLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hudSettings.addView(modeLabel);
        addSpace(hudSettings, 6);

        Spinner modeSpinner = new Spinner(this);
        String[] modeOptions = {"Compact", "Expanded"};
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, modeOptions);
        modeAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);

        String savedMode = hudPrefs.getString("hud_mode", "compact");
        modeSpinner.setSelection(
            savedMode.equals("expanded") ? 1 : 0);

        hudSettings.addView(modeSpinner,
            new LinearLayout.LayoutParams(-1, dp(48)));

        addSpace(hudSettings, 14);

        TextView transparencyLabel =
            text("HUD TRANSPARENCY", 10, MUTED);
        transparencyLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hudSettings.addView(transparencyLabel);

        TextView transparencyValue =
            text(hudPrefs.getInt("hud_transparency", 90) + "%", 16, GREEN);
        hudSettings.addView(transparencyValue);

        SeekBar transparencySeek = new SeekBar(this);
        transparencySeek.setMax(65);
        transparencySeek.setProgress(
            Math.max(0, Math.min(65,
                hudPrefs.getInt("hud_transparency", 90) - 35)));

        transparencySeek.setOnSeekBarChangeListener(
            new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(
                    SeekBar seekBar, int progress, boolean fromUser) {
                    transparencyValue.setText((progress + 35) + "%");
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });

        hudSettings.addView(transparencySeek,
            new LinearLayout.LayoutParams(-1, dp(42)));

        addSpace(hudSettings, 10);

        TextView refreshLabel =
            text("HUD REFRESH INTERVAL", 10, MUTED);
        refreshLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hudSettings.addView(refreshLabel);
        addSpace(hudSettings, 6);

        Spinner refreshSpinner = new Spinner(this);
        String[] refreshOptions = {
            "500 milliseconds",
            "1 second",
            "2 seconds"
        };

        ArrayAdapter<String> refreshAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, refreshOptions);
        refreshAdapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item);
        refreshSpinner.setAdapter(refreshAdapter);

        int savedRefresh = hudPrefs.getInt("hud_refresh", 1000);
        int refreshSelection =
            savedRefresh == 500 ? 0 :
            savedRefresh == 2000 ? 2 : 1;

        refreshSpinner.setSelection(refreshSelection);

        hudSettings.addView(refreshSpinner,
            new LinearLayout.LayoutParams(-1, dp(48)));

        addSpace(hudSettings, 14);

        Button saveHudButton = new Button(this);
        saveHudButton.setText("SAVE & APPLY HUD SETTINGS");
        saveHudButton.setTextColor(Color.BLACK);
        saveHudButton.setTextSize(12);
        saveHudButton.setAllCaps(false);
        saveHudButton.setBackground(round(GREEN, 14));

        saveHudButton.setOnClickListener(v -> {
            int[] refreshValues = {500, 1000, 2000};
            int selectedRefresh = refreshValues[
                refreshSpinner.getSelectedItemPosition()];

            String selectedMode =
                modeSpinner.getSelectedItemPosition() == 1
                    ? "expanded" : "compact";

            int selectedTransparency =
                transparencySeek.getProgress() + 35;

            hudPrefs.edit()
                .putString("hud_mode", selectedMode)
                .putInt("hud_transparency", selectedTransparency)
                .putInt("hud_refresh", selectedRefresh)
                .apply();

            startOverlay();
        });

        hudSettings.addView(saveHudButton,
            new LinearLayout.LayoutParams(-1, dp(52)));

        root.addView(hudSettings);



        addSpace(root, 16);

        TextView footer = text(
            "IMMORTALIS MONITOR  •  VERSION 1.4",
            10, MUTED);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer);

        addSpace(root, 8);

        TextView footer2 = text("BUILT FOR IMMORTALIS-G925", 9, MUTED);
        footer2.setGravity(Gravity.CENTER);
        root.addView(footer2);
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD, 18));
        return box;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private TextView addMetric(
        LinearLayout row, String label, String initial, int valueColor) {

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(15), dp(8), dp(15));
        box.setBackground(round(CARD, 16));

        TextView name = text(label, 9, MUTED);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        TextView value = text(initial, 23, valueColor);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        box.addView(name);
        addSpace(box, 8);
        box.addView(value);

        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        row.addView(box, params);

        return value;
    }

    private void addSection(LinearLayout root, String label) {
        TextView view = text(label, 11, MUTED);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLetterSpacing(0.12f);
        view.setPadding(dp(2), 0, 0, dp(10));
        root.addView(view);
    }

    private void addSpace(LinearLayout parent, int height) {
        View view = new View(this);
        parent.addView(view,
            new LinearLayout.LayoutParams(1, dp(height)));
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(float value) {
        return (int) (value *
            getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
    }

    // CIRCULAR GPU GAUGE
    class GaugeView extends View {

        private final Paint paint = new Paint(3);
        private int value = 0;

        GaugeView() {
            super(MainActivity.this);
        }

        void setValue(int newValue) {
            value = Math.max(0, Math.min(100, newValue));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(cx, cy) * 0.78f;

            RectF rect = new RectF(
                cx - radius, cy - radius,
                cx + radius, cy + radius);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(12));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.rgb(39, 52, 70));

            canvas.drawArc(rect, 135, 270, false, paint);

            paint.setColor(GREEN);
            canvas.drawArc(
                rect, 135, 270 * value / 100f,
                false, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(dp(42));

            canvas.drawText(
                value + "%", cx,
                cy + dp(14), paint);

            paint.setColor(MUTED);
            paint.setTextSize(dp(10));
            paint.setTypeface(Typeface.DEFAULT);

            canvas.drawText(
                "GPU LOAD", cx,
                cy + dp(35), paint);
        }
    }

    // GPU ACTIVITY GRAPH
    class GraphView extends View {

        private final ArrayList<Integer> values = new ArrayList<>();
        private final Paint paint = new Paint(3);

        GraphView() {
            super(MainActivity.this);
        }

        void addValue(int value) {
            values.add(Math.max(0, Math.min(100, value)));

            if (values.size() > 40) {
                values.remove(0);
            }

            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.rgb(39, 52, 70));

            for (int i = 0; i <= 4; i++) {
                float y = h * i / 4f;
                canvas.drawLine(0, y, w, y, paint);
            }

            if (values.size() < 2) return;

            Path path = new Path();
            Path fill = new Path();

            float step = w / (values.size() - 1);

            for (int i = 0; i < values.size(); i++) {
                float x = i * step;
                float y = h - values.get(i) * h / 100f;

                if (i == 0) {
                    path.moveTo(x, y);
                    fill.moveTo(x, h);
                    fill.lineTo(x, y);
                } else {
                    path.lineTo(x, y);
                    fill.lineTo(x, y);
                }
            }

            fill.lineTo(w, h);
            fill.close();

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(35, 0, 235, 170));
            canvas.drawPath(fill, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(GREEN);

            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
