package app.sthira;

import android.app.TimePickerDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;

/** Sleep: set the alarm and start the night; during the night a dark clock; in the morning the report. */
final class SleepScreen {

    static final int REQ_HC_SLEEP = 6;

    private SleepScreen() {
    }

    static View build(MainActivity a) {
        Sleep.checkStale(a);
        if (Sleep.active(a) != null) return night(a);
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        View back = Ui.iconButton(a, Ui.I_LEFT, Ui.CREAM, true, "Back to Today", v -> a.open(MainActivity.TODAY));
        LinearLayout body = Ui.col(a);
        body.addView(setup(a), Ui.lp(Ui.MATCH, Ui.WRAP));
        JSONObject last = Sleep.lastNight(a);
        if (last != null) body.addView(report(a, last), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 22, 0, 0));
        View history = history(a);
        if (history != null) body.addView(history, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 22, 0, 0));
        return Ui.page(a, Ui.header(a, "tonight", "SLEEP", back), body);
    }

    // ---- setup ----

    private static View setup(MainActivity a) {
        LinearLayout box = Ui.col(a);
        String mode = Sleep.mode(a);
        boolean noAlarm = Sleep.MODE_TRACK.equals(mode);

        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.fill(Ui.BLACK, 4));
        Ui.pad(card, 14, 12, 14, 14);
        card.addView(Ui.label(a, noAlarm ? "no alarm" : "wake me by", Ui.MUTED));
        TextView time = Ui.display(a, noAlarm ? "—" : Sleep.alarmTime(a), 72, Ui.CREAM);
        time.setFontFeatureSettings("tnum");
        if (!noAlarm) {
            time.setBackground(Ui.press(Ui.fill(0x00000000, 0), 0));
            time.setOnClickListener(v -> {
                int m = Store.minutes(Sleep.alarmTime(a));
                new TimePickerDialog(a, (tp, h, min) -> {
                    boolean wasArmed = Sleep.alarmArmed(a) && Sleep.active(a) == null;
                    Store.setSetting(a, "sleepAlarm", Store.hhmm(h * 60 + min));
                    if (wasArmed) {
                        // the backup alarm was armed for the old time: move it
                        long t = Sleep.nextTarget(a);
                        SleepAlarm.arm(a, t, t);
                    }
                    a.refresh();
                }, m / 60, m % 60, true).show();
            });
        }
        card.addView(time, Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 6, 0, 0));
        String sub;
        if (noAlarm) sub = "Tracking only. Nothing will ring.";
        else if (Sleep.MODE_EXACT.equals(mode)) sub = "Rings at exactly " + Sleep.alarmTime(a) + ".";
        else {
            int m = Store.minutes(Sleep.alarmTime(a));
            sub = "Wakes you between " + Store.hhmm(m - Sleep.window(a)) + " and " + Sleep.alarmTime(a)
                    + ", in the lightest moment it hears. Tap the time to change it.";
        }
        card.addView(Ui.text(a, sub, 13, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 12));

        // window: Off means an exact alarm
        LinearLayout chips = Ui.row(a);
        String[] names = {"EXACT", "10", "20", "30", "45", "NONE"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            boolean on;
            if (i == 0) on = Sleep.MODE_EXACT.equals(mode);
            else if (i == 5) on = noAlarm;
            else on = Sleep.MODE_SMART.equals(mode) && Sleep.window(a) == Integer.parseInt(names[i]);
            TextView c = Ui.text(a, names[i], 12, on ? Ui.BLACK : Ui.MUTED, Ui.bold);
            c.setGravity(Gravity.CENTER);
            c.setBackground(Ui.press(on ? Ui.fill(Ui.JADE, 4) : Ui.stroke(Ui.MUTED, 4, false), 4));
            c.setContentDescription(i == 0 ? "Exact alarm" : i == 5 ? "No alarm" : names[i] + " minute wake window");
            c.setOnClickListener(v -> {
                if (idx == 0) Store.setSetting(a, "sleepMode", Sleep.MODE_EXACT);
                else if (idx == 5) {
                    Store.setSetting(a, "sleepMode", Sleep.MODE_TRACK);
                    if (Sleep.active(a) == null && Sleep.alarmArmed(a)) SleepAlarm.cancel(a);
                }
                else {
                    Store.setSetting(a, "sleepMode", Sleep.MODE_SMART);
                    Store.setSetting(a, "sleepWindow", Integer.parseInt(names[idx]));
                }
                a.refresh();
            });
            chips.addView(c, Ui.gap(Ui.lp(0, Ui.dp(40), 1), i == 0 ? 0 : 3, 0, i == names.length - 1 ? 0 : 3, 0));
        }
        card.addView(chips, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView chipHelp = Ui.text(a, "Smart window in minutes · exact time · no alarm", 11, Ui.MUTED, Ui.regular);
        card.addView(chipHelp, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
        if (!noAlarm && Sleep.active(a) == null && Sleep.alarmArmed(a)) {
            card.addView(Ui.ghost(a, "Skip tonight's alarm", Ui.CREAM, false, v -> {
                SleepAlarm.cancel(a);
                a.refresh();
            }), Ui.gap(Ui.lp(Ui.MATCH, Ui.dp(46)), 0, 10, 0, 0));
        }
        box.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP));

        // things that would stop the alarm or the tracking from working
        if (Sleep.colorOsNeedsSetup(a)) {
            JSONObject last = Sleep.lastNight(a);
            boolean cut = last != null && last.optBoolean("interrupted");
            box.addView(fix(a, cut ? "Tracking was cut short. Stop OnePlus killing it" : "Stop OnePlus killing tracking at night",
                    "In App info: Battery → Allow background activity, and turn on Auto launch. "
                            + "Then in Recents, lock Sthira (long-press its card) so Clear all skips it.",
                    v -> Sleep.openAppInfo(a)), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        }
        if (!noAlarm && !Sleep.fullScreenAllowed(a)) {
            box.addView(fix(a, "Let the alarm show over the lock screen", "Full-screen alarm is off for Sthira.",
                    v -> Sleep.askFullScreen(a)), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        }
        if (!Sleep.batteryUnrestricted(a)) {
            box.addView(fix(a, "Don't let the phone pause Sthira at night",
                    "OnePlus battery saving can stop tracking. Allow Sthira to run unrestricted.",
                    v -> Sleep.askBattery(a)), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        }
        if (Build.VERSION.SDK_INT >= 34 && !HealthSteps.canWriteSleep(a)
                && !Store.root(a).optBoolean("hcSleepAsked", false)) {
            box.addView(fix(a, "Save nights to Health Connect", "Optional. Other apps can then see your sleep.",
                    v -> {
                        Store.put(Store.root(a), "hcSleepAsked", true);
                        Store.save(a);
                        a.requestPermissions(new String[]{HealthSteps.HC_WRITE_SLEEP}, REQ_HC_SLEEP);
                    }), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        }

        String tip = Sleep.charging(a) ? "Phone near your pillow, mic facing you. Not under the pillow."
                : "Put the phone on the charger, near your pillow, mic facing you. Not under the pillow.";
        box.addView(Ui.text(a, tip, 13, Ui.CREAM, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 12, 0, 0));
        TextView go = Ui.button(a, "START SLEEP", Ui.JADE, Ui.BLACK, v -> Sleep.start(a));
        go.setTypeface(Ui.bebas);
        go.setTextSize(26);
        box.addView(go, Ui.gap(Ui.lp(Ui.MATCH, Ui.dp(60)), 0, 12, 0, 0));
        return box;
    }

    private static View fix(MainActivity a, String title, String sub, View.OnClickListener l) {
        LinearLayout c = Ui.card(a, Ui.CREAM, l);
        c.setBackground(Ui.press(Ui.stroke(Ui.CREAM, 4, true), 4));
        LinearLayout t = Ui.col(a);
        t.addView(Ui.text(a, title, 14, Ui.CREAM, Ui.bold));
        t.addView(Ui.text(a, sub, 12, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 3, 0, 0));
        c.addView(t, Ui.lp(0, Ui.WRAP, 1));
        c.addView(Ui.label(a, "fix", Ui.OCHRE));
        return c;
    }

    // ---- during the night ----

    private static View night(MainActivity a) {
        JSONObject s = Sleep.active(a);
        LinearLayout page = Ui.col(a);
        page.setBackgroundColor(0xFF0B0204);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(page, 20, 16, 20, 28);
        int dim = 0x66F0E2D3;

        LinearLayout top = Ui.row(a);
        top.addView(Ui.iconButton(a, Ui.I_LEFT, dim, true, "Back to Today", v -> a.open(MainActivity.TODAY)),
                Ui.lp(Ui.dp(46), Ui.dp(46)));
        TextView label = Ui.label(a, "sleeping", dim);
        label.setGravity(Gravity.CENTER);
        top.addView(label, Ui.lp(0, Ui.WRAP, 1));
        top.addView(new View(a), Ui.lp(Ui.dp(46), Ui.dp(46)));
        page.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP));
        page.addView(new View(a), Ui.lp(Ui.MATCH, 0, 1));

        TextView clock = Ui.display(a, Store.hhmm(Sleep.minuteOf(System.currentTimeMillis())), 110, dim);
        clock.setFontFeatureSettings("tnum");
        page.addView(clock);
        long target = s.optLong("target");
        int w = s.optInt("window");
        String line = target <= 0 ? "No alarm. Tracking only."
                : w > 0 ? "Alarm " + Store.hhmm(Sleep.minuteOf(target)) + " · from " + Store.hhmm(Sleep.minuteOf(target) - w)
                + " in light sleep" : "Alarm " + Store.hhmm(Sleep.minuteOf(target));
        page.addView(Ui.text(a, line, 15, dim, Ui.regular), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 10, 0, 0));
        Dot dot = new Dot(a);
        page.addView(dot, Ui.gap(Ui.lp(Ui.dp(14), Ui.dp(14)), 0, 22, 0, 0));
        TextView hint = Ui.text(a, "Listening. No audio is saved. You can turn the screen off.", 12, 0x55F0E2D3, Ui.regular);
        hint.setGravity(Gravity.CENTER);
        page.addView(hint, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 10, 0, 0));
        page.addView(new View(a), Ui.lp(Ui.MATCH, 0, 1));

        TextView stop = Ui.ghost(a, "Hold to stop tracking", dim, false, null);
        Runnable done = () -> {
            Sleep.stopNow(a, true);
            a.handler.postDelayed(a::refresh, 300);
        };
        stop.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                stop.setText("Keep holding…");
                a.handler.postDelayed(done, 1000);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                a.handler.removeCallbacks(done);
                stop.setText("Hold to stop tracking");
            }
            return true;
        });
        page.addView(stop, Ui.lp(Ui.MATCH, Ui.dp(56)));

        a.handler.post(new Runnable() {
            boolean seen;
            int waits;

            @Override
            public void run() {
                if (clock.isAttachedToWindow()) seen = true;
                else if (seen || ++waits > 25) return;
                if (Sleep.active(a) == null) {
                    a.refresh();
                    return;
                }
                clock.setText(Store.hhmm(Sleep.minuteOf(System.currentTimeMillis())));
                dot.set(SleepService.level(), SleepService.instance != null);
                a.handler.postDelayed(this, 250);
            }
        });
        return page;
    }

    /** A small dot that brightens with sound over the background, so you can see it hears you. */
    static final class Dot extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double level;
        private boolean alive;

        Dot(Context c) {
            super(c);
        }

        void set(double level, boolean alive) {
            this.level = level;
            this.alive = alive;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int alpha = alive ? (int) (40 + Math.min(1, Math.max(0, level / 12)) * 200) : 30;
            p.setColor(alive ? Ui.CREAM : 0xFFFF6B6B);
            p.setAlpha(alpha);
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f, p);
        }
    }

    // ---- the morning report ----

    private static View report(MainActivity a, JSONObject n) {
        LinearLayout box = Ui.col(a);
        LocalDate d = LocalDate.parse(n.optString("date", LocalDate.now().toString()));
        String when = d.equals(LocalDate.now()) ? "last night"
                : d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " morning";
        box.addView(Ui.label(a, when, Ui.MUTED));

        LinearLayout head = Ui.row(a);
        LinearLayout left = Ui.col(a);
        left.addView(Ui.display(a, Sleep.hm(n.optLong("tst")), 46, Ui.CREAM));
        left.addView(Ui.text(a, "asleep · " + Sleep.hm(n.optLong("tib")) + " in bed", 13, Ui.MUTED, Ui.regular),
                Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 4, 0, 0));
        head.addView(left, Ui.lp(0, Ui.WRAP, 1));
        LinearLayout right = Ui.col(a);
        right.setGravity(Gravity.END);
        right.addView(Ui.display(a, String.valueOf(n.optInt("score")), 46, Ui.CREAM));
        right.addView(Ui.label(a, "score", Ui.MUTED), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 4, 0, 0));
        head.addView(right);
        box.addView(head, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));

        if (n.optString("stages").length() > 0) {
            box.addView(new Hypnogram(a, n, Sleep.showRem(a)), Ui.gap(Ui.lp(Ui.MATCH, Ui.dp(150)), 0, 14, 0, 0));
        }

        // how the alarm went
        String alarm = null;
        if (n.has("firedAt")) {
            String reason = n.optString("firedReason");
            long early = (n.optLong("target") - n.optLong("firedAt")) / 60_000;
            alarm = "DEADLINE".equals(reason) ? "Woke you at the set time; it never found a light moment in the window."
                    : "Woke you at " + Store.hhmm(Sleep.minuteOf(n.optLong("firedAt")))
                    + (early > 0 ? ", " + early + " min early" : "") + ", because " + Sleep.reasonText(reason) + ".";
        }
        if (alarm != null) box.addView(Ui.text(a, alarm, 13, Ui.CREAM, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 10, 0, 0));
        JSONArray pickups = n.optJSONArray("pickups");
        if (pickups != null && pickups.length() > 0) {
            StringBuilder times = new StringBuilder();
            for (int i = 0; i < Math.min(4, pickups.length()); i++) {
                times.append(i == 0 ? "" : ", ").append(Store.hhmm(Sleep.minuteOf(pickups.optLong(i))));
            }
            if (pickups.length() > 4) times.append(", …");
            String line = "Picked up your phone " + (pickups.length() == 1 ? "once" : pickups.length() + " times")
                    + " after falling asleep (" + times + ").";
            box.addView(Ui.text(a, line, 13, Ui.CREAM, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
        }

        // numbers
        LinearLayout grid = Ui.col(a);
        boolean rem = Sleep.showRem(a);
        java.util.List<String[]> stats = new java.util.ArrayList<>();
        stats.add(new String[]{"Fell asleep in", n.optLong("latency") + " min"});
        stats.add(new String[]{"Awakenings", String.valueOf(n.optInt("awakenings"))});
        stats.add(new String[]{"Deep", Sleep.hm(n.optLong("deep"))});
        stats.add(new String[]{"Light", Sleep.hm(n.optLong("light") + (rem ? 0 : n.optLong("rem")))});
        if (rem) stats.add(new String[]{"Dream (est.)", Sleep.hm(n.optLong("rem"))});
        stats.add(new String[]{"Awake in bed", Sleep.hm(n.optLong("awake"))});
        stats.add(new String[]{"Efficiency", n.optInt("eff") + "%"});
        stats.add(new String[]{"Snoring (est.)", n.optLong("snore") + " min"});
        for (int i = 0; i < stats.size(); i += 2) {
            LinearLayout row = Ui.row(a);
            row.addView(stat(a, stats.get(i)[0], stats.get(i)[1]), Ui.lp(0, Ui.WRAP, 1));
            if (i + 1 < stats.size()) row.addView(stat(a, stats.get(i + 1)[0], stats.get(i + 1)[1]), Ui.lp(0, Ui.WRAP, 1));
            else row.addView(new View(a), Ui.lp(0, Ui.WRAP, 1));
            grid.addView(row, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        }
        box.addView(grid, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));

        // score parts
        JSONObject parts = n.optJSONObject("parts");
        if (parts != null) {
            LinearLayout pc = Ui.col(a);
            pc.setBackground(Ui.fill(Ui.BLACK, 4));
            Ui.pad(pc, 14, 10, 14, 10);
            String[][] ps = {{"duration", "Duration", "35"}, {"deep", "Deep sleep", "20"}, {"calm", "Calm", "20"},
                    {"continuity", "Continuity", "15"}, {"regularity", "Regular bedtime", "10"}};
            for (String[] p : ps) {
                LinearLayout row = Ui.row(a);
                row.setMinimumHeight(Ui.dp(28));
                row.addView(Ui.text(a, p[1], 13, Ui.MUTED, Ui.regular), Ui.lp(0, Ui.WRAP, 1));
                row.addView(Ui.text(a, parts.optInt(p[0]) + " / " + p[2], 13, Ui.CREAM, Ui.bold));
                pc.addView(row);
            }
            box.addView(pc, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 12, 0, 0));
        }

        // how do you feel: kept to tune the alarm later
        box.addView(Ui.label(a, "how do you feel?", Ui.MUTED), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 16, 0, 6));
        LinearLayout feel = Ui.row(a);
        String[] faces = {"1", "2", "3", "4", "5"};
        int chosen = n.optInt("feel", 0);
        for (int i = 0; i < 5; i++) {
            final int v = i + 1;
            boolean on = chosen == v;
            TextView t = Ui.text(a, faces[i], 16, on ? Ui.BLACK : Ui.CREAM, Ui.bold);
            t.setGravity(Gravity.CENTER);
            t.setBackground(Ui.press(on ? Ui.fill(Ui.JADE, 4) : Ui.stroke(Ui.MUTED, 4, false), 4));
            t.setContentDescription("Feel " + v + " out of 5");
            t.setOnClickListener(x -> {
                Store.put(n, "feel", v);
                Store.save(a);
                a.refresh();
            });
            feel.addView(t, Ui.gap(Ui.lp(0, Ui.dp(44), 1), i == 0 ? 0 : 3, 0, i == 4 ? 0 : 3, 0));
        }
        box.addView(feel, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView feelHelp = Ui.text(a, "1 groggy · 5 fresh", 11, Ui.MUTED, Ui.regular);
        box.addView(feelHelp, Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 4, 0, 0));

        StringBuilder notes = new StringBuilder("Stages are estimates from sound, not a medical measurement.");
        if (n.optBoolean("short")) notes.append(" Short night, so the stages are rough.");
        if (n.optBoolean("interrupted")) notes.append(" Tracking stopped early; this covers what was recorded.");
        if (n.optInt("gapMin") >= 5) notes.append(" ").append(n.optInt("gapMin"))
                .append(" min weren't recorded (the phone stopped Sthira); those count as light sleep, not deep.");
        if (n.optInt("motionMin") >= 5) notes.append(" ").append(n.optInt("motionMin"))
                .append(" min came from the motion sensor after a restart, so keep the phone on the mattress.");
        if (!n.optBoolean("charging")) notes.append(" Battery left: " + n.optInt("batteryEnd") + "%.");
        box.addView(Ui.text(a, notes.toString(), 11, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 12, 0, 0));
        return box;
    }

    private static View stat(MainActivity a, String name, String value) {
        LinearLayout c = Ui.col(a);
        c.addView(Ui.text(a, name, 12, Ui.MUTED, Ui.regular));
        c.addView(Ui.text(a, value, 17, Ui.CREAM, Ui.bold), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 2, 0, 0));
        return c;
    }

    private static View history(MainActivity a) {
        JSONArray all = Sleep.nights(a);
        if (all.length() < 2) return null;
        LinearLayout box = Ui.col(a);
        box.addView(Ui.label(a, "past nights", Ui.MUTED), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, 0, 6));
        for (int i = all.length() - 2; i >= Math.max(0, all.length() - 8); i--) {
            JSONObject n = all.optJSONObject(i);
            LinearLayout row = Ui.row(a);
            row.setMinimumHeight(Ui.dp(40));
            LocalDate d = Instant.ofEpochMilli(n.optLong("end")).atZone(ZoneId.systemDefault()).toLocalDate();
            row.addView(Ui.text(a, d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getDayOfMonth(),
                    14, Ui.MUTED, Ui.regular), Ui.lp(Ui.dp(70), Ui.WRAP));
            row.addView(Ui.text(a, Sleep.hm(n.optLong("tst")), 14, Ui.CREAM, Ui.bold), Ui.lp(0, Ui.WRAP, 1));
            row.addView(Ui.text(a, String.valueOf(n.optInt("score")), 14, Ui.CREAM, Ui.bold));
            box.addView(row);
            View rule = new View(a);
            rule.setBackgroundColor(0x22F0E2D3);
            box.addView(rule, Ui.lp(Ui.MATCH, 1));
        }
        return box;
    }

    /** Step chart of the night: Awake on top, then Dream (est.), Light, Deep at the bottom. */
    static final class Hypnogram extends View {
        private final String st;
        private final long start, end;
        private final boolean rem;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Hypnogram(Context c, JSONObject n, boolean rem) {
            super(c);
            this.st = n.optString("stages");
            this.start = n.optLong("start");
            this.end = n.optLong("end");
            this.rem = rem;
        }

        private int row(char ch) {
            switch (ch) {
                case 'W':
                    return 0;
                case 'R':
                    return rem ? 1 : 2;
                case 'D':
                    return 3;
                default:
                    return 2;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float labelW = Ui.dp(54), axisH = Ui.dp(18);
            float w = getWidth() - labelW, h = getHeight() - axisH, rowH = h / 4f;
            String[] names = {"Awake", rem ? "Dream" : "", "Light", "Deep"};
            p.setTextSize(Ui.dp(11));
            p.setTypeface(Ui.regular);
            for (int r = 0; r < 4; r++) {
                p.setColor(r % 2 == 0 ? 0x14F0E2D3 : 0x08F0E2D3);
                p.setStyle(Paint.Style.FILL);
                c.drawRect(labelW, r * rowH, labelW + w, (r + 1) * rowH, p);
                p.setColor(Ui.MUTED);
                c.drawText(names[r], 0, r * rowH + rowH / 2 + Ui.dp(4), p);
            }
            int n = st.length();
            if (n == 0) return;
            float step = w / n;
            // filled bars per bout, lighter the deeper
            int[] colors = {0x99F0E2D3, 0xFFD9A86C, 0xFFB86B6B, Ui.CREAM};
            int i = 0;
            path.reset();
            while (i < n) {
                char ch = st.charAt(i);
                int j = i;
                while (j < n && st.charAt(j) == ch) j++;
                int r = row(ch);
                float x0 = labelW + i * step, x1 = labelW + j * step, y = r * rowH;
                p.setColor(colors[r]);
                c.drawRect(x0, y + rowH * 0.2f, x1, y + rowH * 0.8f, p);
                if (i == 0) path.moveTo(x0, y + rowH / 2);
                else path.lineTo(x0, y + rowH / 2);
                path.lineTo(x1, y + rowH / 2);
                i = j;
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, Ui.dp(1)));
            p.setColor(0x88F0E2D3);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.MUTED);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(Store.hhmm(Sleep.minuteOf(start)), labelW, getHeight() - Ui.dp(3), p);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(Store.hhmm(Sleep.minuteOf(end)), getWidth(), getHeight() - Ui.dp(3), p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }
}
