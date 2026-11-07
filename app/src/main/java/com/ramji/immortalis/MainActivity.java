package com.ramji.immortalis;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.widget.*;
import android.graphics.Typeface;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {

    private TextView loading, blocking, idle, frequency, temperature, status;
    private GraphView graph;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private final String command =
        "cat /sys/kernel/ged/hal/gpu_utilization; " +
        "echo __INFO__; " +
        "cat /sys/kernel/thermal/gpu_info; " +
        "echo __OPP__; " +
        "cat /sys/kernel/ged/hal/opp_logs";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(Color.rgb(11,16,32));
        getWindow().setNavigationBarColor(Color.rgb(11,16,32));

        buildDashboard();

        worker.execute(() -> {
            try {
                Process p = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));

                StringBuilder output = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }

                p.waitFor();
                parseData(output.toString());

            } catch (Exception e) {
                handler.post(() -> status.setText("Root access unavailable"));
            }

            handler.postDelayed(() -> {
                if (!isFinishing()) startReading();
            }, 1000);
        });
    }

    private void startReading() {
        worker.execute(() -> {
            try {
                Process p = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start();

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));

                StringBuilder output = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }

                p.waitFor();
                parseData(output.toString());

            } catch (Exception e) {
                handler.post(() -> status.setText("Reading failed"));
            }

            handler.postDelayed(() -> {
                if (!isFinishing()) startReading();
            }, 1000);
        });
    }

    private void parseData(String raw) {
        try {
            String[] infoParts = raw.split("__INFO__", 2);
            String[] oppParts = infoParts[1].split("__OPP__", 2);

            String[] values = infoParts[0].trim().split("\\s+");
            String[] thermal = oppParts[0].trim().split(",");

            String freq = "Unknown";
            java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(?m)^\\s*\\*\\s+(\\d+)")
                .matcher(oppParts[1]);

            if (m.find()) freq = m.group(1);

            int load = Integer.parseInt(values[0]);
            int block = Integer.parseInt(values[1]);
            int idleValue = Integer.parseInt(values[2]);

            String finalFreq = freq;
            String temp = thermal[0].trim();

            handler.post(() -> {
                loading.setText(load + "%");
                blocking.setText(block + "%");
                idle.setText(idleValue + "%");
                frequency.setText(finalFreq + " MHz");
                temperature.setText(temp + " (raw)");
                graph.addValue(load);
                status.setText("LIVE • Updating every second");
            });

        } catch (Exception e) {
            handler.post(() -> status.setText("Waiting for valid telemetry"));
        }
    }

    private void buildDashboard() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(22, 24, 22, 24);
        root.setBackgroundColor(Color.rgb(11,16,32));

        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("IMMORTALIS", 27, Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text("GPU MONITOR  •  POCO X8 PRO MAX", 12,
            Color.rgb(0,210,190));
        root.addView(subtitle);

        root.addView(space(25));

        addLabel(root, "GPU UTILIZATION");

        loading = text("--%", 54, Color.rgb(0,230,180));
        loading.setTypeface(null, Typeface.BOLD);
        root.addView(loading);

        root.addView(space(18));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        blocking = metric(row, "BLOCKING");
        idle = metric(row, "IDLE");
        root.addView(row);

        root.addView(space(25));
        addLabel(root, "SELECTED OPP CANDIDATE");

        frequency = text("-- MHz", 30, Color.WHITE);
        root.addView(frequency);

        root.addView(space(20));
        addLabel(root, "THERMAL SENSOR • RAW");

        temperature = text("Waiting...", 22, Color.rgb(255,190,80));
        root.addView(temperature);

        root.addView(space(25));
        addLabel(root, "GPU ACTIVITY HISTORY");

        graph = new GraphView();
        root.addView(graph, new LinearLayout.LayoutParams(
            -1, 220));

        root.addView(space(25));

        status = text("Starting GPU monitor...", 13,
            Color.rgb(0,210,190));
        root.addView(status);

        root.addView(space(12));

        TextView footer = text("IMMORTALIS MONITOR • V1.0", 11,
            Color.GRAY);
        root.addView(footer);
    }

    private TextView metric(LinearLayout row, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(15, 15, 15, 15);
        box.setBackgroundColor(Color.rgb(24,32,52));

        TextView name = text(label, 12, Color.LTGRAY);
        TextView value = text("--%", 26, Color.WHITE);

        box.addView(name);
        box.addView(value);

        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(0, 0, 10, 0);
        row.addView(box, params);

        return value;
    }

    private void addLabel(LinearLayout root, String value) {
        TextView v = text(value, 13, Color.LTGRAY);
        v.setPadding(0, 0, 0, 8);
        root.addView(v);
    }

    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private View space(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, height));
        return v;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }

    class GraphView extends View {
        private final ArrayList<Integer> values = new ArrayList<>();
        private final Paint paint = new Paint(3);

        GraphView() {
            super(MainActivity.this);
        }

        void addValue(int value) {
            values.add(value);
            if (values.size() > 40) values.remove(0);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();

            paint.setColor(Color.rgb(30,42,62));
            paint.setStrokeWidth(1);

            for (int i = 1; i <= 4; i++) {
                canvas.drawLine(0, h*i/5, w, h*i/5, paint);
            }

            if (values.size() < 2) return;

            paint.setColor(Color.rgb(0,230,180));
            paint.setStrokeWidth(4);
            paint.setStyle(Paint.Style.STROKE);

            float step = w / (values.size()-1);

            for (int i = 1; i < values.size(); i++) {
                float x1 = (i-1)*step;
                float x2 = i*step;
                float y1 = h - values.get(i-1)*h/100f;
                float y2 = h - values.get(i)*h/100f;

                canvas.drawLine(x1,y1,x2,y2,paint);
            }

            paint.setStyle(Paint.Style.FILL);
        }
    }
}
