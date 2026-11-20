package app.sthira;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Daily steps for the Train tab. Health Connect comes first (Google Fit, OHealth, a watch or the phone
 * itself can all write steps there). When it has nothing, the phone's own step counter is sampled every
 * 15 minutes in the background and the count is kept here, so the card still works without any fit app.
 */
public final class Steps {

    static final int GOAL = 10_000;
    static final String HC_READ_STEPS = "android.permission.health.READ_STEPS";
    static final int REQ_HEALTH = 3, REQ_SENSOR = 4;
    private static final int JOB_ID = 41;

    interface Result {
        /** week[6] is today, week[0] six days ago. source is "Health Connect", "phone sensor" or null. */
        void done(long[] week, String source);
    }

    private Steps() {
    }

    // ---- permissions ----

    static boolean healthConnectAvailable() {
        return Build.VERSION.SDK_INT >= 34;
    }

    static boolean healthAllowed(Context c) {
        return healthConnectAvailable() && c.checkSelfPermission(HC_READ_STEPS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean sensorAllowed(Context c) {
        if (Build.VERSION.SDK_INT < 29) return true;
        return c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasSensor(Context c) {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        return sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
    }

    /** Health Connect first; the phone sensor is asked for straight after, from the permission result. */
    static void connect(MainActivity a) {
        Store.put(Store.root(a), "stepsAsked", true);
        Store.save(a);
        if (healthConnectAvailable() && !healthAllowed(a)) {
            a.requestPermissions(new String[]{HC_READ_STEPS}, REQ_HEALTH);
        } else {
            askSensor(a);
        }
    }

    static void askSensor(MainActivity a) {
        if (Build.VERSION.SDK_INT >= 29 && !sensorAllowed(a) && hasSensor(a)) {
            a.requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, REQ_SENSOR);
        } else {
            start(a);
        }
    }

    /** Opens Health Connect's own permission page for Sthira, for when the dialog was dismissed. */
    static void openHealthConnect(Context c) {
        if (!healthConnectAvailable()) return;
        try {
            Intent i = new Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
                    .putExtra(Intent.EXTRA_PACKAGE_NAME, c.getPackageName());
            c.startActivity(i);
        } catch (Exception e) {
            try {
                c.startActivity(new Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"));
            } catch (Exception ignored) {
            }
        }
    }

    // ---- the phone's own counter ----

    /** Takes a reading now and keeps the 15-minute background sampling armed. */
    static void start(Context c) {
        if (!sensorAllowed(c) || !hasSensor(c)) return;
        sample(c, null);
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        try {
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, Job.class))
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build());
        } catch (Exception ignored) {
        }
    }

    /** Reads the cumulative counter once, adds the change since the last reading to today, then lets go. */
    static void sample(Context c, Runnable after) {
        Context app = c.getApplicationContext();
        SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (s == null || !sensorAllowed(app)) {
            if (after != null) after.run();
            return;
        }
        Handler h = new Handler(Looper.getMainLooper());
        final boolean[] finished = {false};
        SensorEventListener l = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (finished[0]) return;
                finished[0] = true;
                sm.unregisterListener(this);
                record(app, (long) e.values[0]);
                if (after != null) after.run();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL, 0, h);
        h.postDelayed(() -> {
            if (finished[0]) return;
            finished[0] = true;
            sm.unregisterListener(l);
            if (after != null) after.run();
        }, 6000);
    }

    private static synchronized void record(Context c, long counter) {
        JSONObject steps = Store.obj(Store.root(c), "steps");
        long last = steps.optLong("last", -1);
        steps.remove("last");
        Store.put(steps, "last", counter);
        if (last >= 0) {
            long delta = counter - last;
            if (delta < 0) delta = counter;          // the counter restarts from zero after a reboot
            if (delta > 0 && delta < 60_000) {
                JSONObject days = Store.obj(steps, "days");
                String today = LocalDate.now().toString();
                Store.put(days, today, days.optLong(today, 0) + delta);
            }
        }
        Store.save(c);
    }

    static long sensorDay(Context c, LocalDate d) {
        JSONObject days = Store.obj(Store.root(c), "steps").optJSONObject("days");
        return days == null ? 0 : days.optLong(d.toString(), 0);
    }

    /** Background sampling: one reading every 15 minutes. */
    public static final class Job extends JobService {
        @Override
        public boolean onStartJob(JobParameters params) {
            sample(this, () -> jobFinished(params, false));
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return true;
        }
    }

    // ---- reading ----

    static void load(Context c, Result r) {
        Handler main = new Handler(Looper.getMainLooper());
        LocalDate today = LocalDate.now();
        long[] phone = new long[7];
        for (int i = 0; i < 7; i++) phone[i] = sensorDay(c, today.minusDays(6 - i));
        boolean phoneHas = sensorAllowed(c) && hasSensor(c) && Store.obj(Store.root(c), "steps").has("last");
        if (!healthAllowed(c)) {
            main.post(() -> r.done(phone, phoneHas ? "phone sensor" : null));
            return;
        }
        HealthSteps.week(c, (hc, ok) -> main.post(() -> {
            if (ok && hc[6] > 0) r.done(hc, "Health Connect");
            else if (phoneHas) r.done(phone, "phone sensor");
            else r.done(ok ? hc : phone, "Health Connect");
        }));
    }

    // ---- the card on the Train tab ----

    static View card(MainActivity a) {
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.fill(Ui.BLACK, 4));
        Ui.pad(card, 14, 12, 14, 12);
        boolean connected = healthAllowed(a) || (sensorAllowed(a) && hasSensor(a) && Store.root(a).optBoolean("stepsAsked", false));

        LinearLayout top = Ui.row(a);
        LinearLayout left = Ui.col(a);
        left.addView(Ui.label(a, "steps today", Ui.MUTED));
        TextView count = Ui.display(a, "—", 44, Ui.CREAM);
        count.setFontFeatureSettings("tnum");
        left.addView(count, Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 4, 0, 0));
        top.addView(left, Ui.lp(0, Ui.WRAP, 1));
        TextView pct = Ui.display(a, "", 28, Ui.MUTED);
        top.addView(pct);
        card.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP));

        Bars bars = new Bars(a);
        card.addView(bars, Ui.gap(Ui.lp(Ui.MATCH, Ui.dp(84)), 0, 10, 0, 0));
        TextView sub = Ui.text(a, "Goal " + fmt(GOAL), 12, Ui.MUTED, Ui.regular);
        card.addView(sub, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));

        if (!connected) {
            count.setText("—");
            sub.setText("Reads steps from Health Connect (Google Fit and other apps write there), "
                    + "or counts them with the phone's own sensor.");
            card.addView(Ui.button(a, "Connect steps", Ui.JADE, Ui.BLACK, v -> connect(a)),
                    Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 10, 0, 0));
            return card;
        }

        start(a);
        load(a, (week, source) -> {
            long today = week[6];
            count.setText(fmt(today));
            int p = (int) Math.min(999, today * 100 / GOAL);
            pct.setText(p + "%");
            pct.setTextColor(today >= GOAL ? Ui.CREAM : Ui.MUTED);
            bars.set(week);
            long sum = 0;
            for (long w : week) sum += w;
            String left7 = today >= GOAL ? "Goal done" : fmt(GOAL - today) + " to go";
            sub.setText(left7 + " · 7-day average " + fmt(sum / 7)
                    + (source == null ? "" : " · " + source));
        });
        if (healthConnectAvailable() && !healthAllowed(a)) {
            sub.setOnClickListener(v -> openHealthConnect(a));
        }
        return card;
    }

    static String fmt(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    /** Seven bars, today on the right, with the goal as a dashed line. */
    static final class Bars extends View {
        private long[] week = new long[7];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bars(Context c) {
            super(c);
        }

        void set(long[] w) {
            week = w.clone();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), labelH = Ui.dp(16), chartH = h - labelH;
            long max = GOAL;
            for (long v : week) max = Math.max(max, v);
            float slot = w / 7f, bar = Math.min(slot * 0.56f, Ui.dp(26));
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(Ui.dp(10));
            p.setTypeface(Ui.bold);
            LocalDate today = LocalDate.now();
            for (int i = 0; i < 7; i++) {
                float cx = slot * i + slot / 2;
                float top = chartH - chartH * (week[i] / (float) max);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Ui.CELL);
                c.drawRect(cx - bar / 2, 0, cx + bar / 2, chartH, p);
                p.setColor(week[i] >= GOAL ? Ui.CREAM : i == 6 ? 0xFFD9C4AE : Ui.MUTED);
                if (week[i] > 0) c.drawRect(cx - bar / 2, Math.min(top, chartH - Ui.dp(2)), cx + bar / 2, chartH, p);
                p.setColor(i == 6 ? Ui.CREAM : Ui.MUTED);
                String name = today.minusDays(6 - i).getDayOfWeek().getDisplayName(TextStyle.NARROW, Locale.ENGLISH);
                c.drawText(name, cx, h - Ui.dp(2), p);
            }
            float goalY = chartH - chartH * (GOAL / (float) max);
            p.setColor(Ui.CREAM);
            p.setStrokeWidth(Math.max(1, Ui.dp(1)));
            for (float x = 0; x < w; x += Ui.dp(8)) c.drawLine(x, goalY, Math.min(w, x + Ui.dp(4)), goalY, p);
        }
    }
}
