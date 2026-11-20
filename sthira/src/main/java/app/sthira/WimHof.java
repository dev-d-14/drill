package app.sthira;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Guided Wim Hof breathing inside the Meditate screen. Each round: power breaths at a steady pace,
 * let the last one go and hold on empty lungs, tap anywhere when you need to breathe, then one big
 * recovery breath held for a set time. Hold times are kept, and the session counts as the day's sit.
 */
final class WimHof {

    static final String[] PACES = {"Slow", "Medium", "Fast"};
    // Seconds in and out per breath for each pace.
    private static final float[] IN = {2.2f, 1.7f, 1.3f}, OUT = {2.0f, 1.6f, 1.2f};
    private static final int PREP = 0, BREATHE = 1, HOLD = 2, RECOVER_IN = 3, RECOVER = 4, DONE = 5;
    private static final long PREP_MS = 5000, RECOVER_IN_MS = 2500;

    /** The running session. Lives across screen rebuilds; dropped if the screen is left. */
    private static final class Session {
        int rounds, breaths, pace, recover, maxHold;
        int round = 1, phase = PREP;
        long phaseStart, startedAt, lastTick;
        int lastBreath = -1, lastChime = 0;
        final List<Integer> holds = new ArrayList<>();
    }

    private static Session s;

    private WimHof() {
    }

    // ---- settings ----

    static int rounds(Context c) {
        return Store.setting(c, "whmRounds", 3);
    }

    static int breaths(Context c) {
        return Store.setting(c, "whmBreaths", 30);
    }

    static int pace(Context c) {
        return Math.max(0, Math.min(2, Store.setting(c, "whmPace", 1)));
    }

    static int recover(Context c) {
        return Store.setting(c, "whmRecover", 15);
    }

    static int maxHold(Context c) {
        return Store.setting(c, "whmMax", 0);
    }

    static boolean running() {
        return s != null && s.phase != DONE;
    }

    // ---- the idle screen: settings, history, begin ----

    static void buildIdle(MainActivity a, LinearLayout middle) {
        LinearLayout list = Ui.col(a);
        list.addView(setting(a, "Rounds", String.valueOf(rounds(a)),
                v -> Ui.pickNumber(a, "Rounds", 1, 6, rounds(a), n -> save(a, "whmRounds", n))));
        list.addView(setting(a, "Breaths per round", String.valueOf(breaths(a)),
                v -> Ui.pickNumber(a, "Breaths per round", 20, 60, breaths(a), n -> save(a, "whmBreaths", n))));
        list.addView(setting(a, "Pace", PACES[pace(a)], v -> save(a, "whmPace", (pace(a) + 1) % 3)));
        list.addView(setting(a, "Recovery hold", recover(a) + " s",
                v -> Ui.pickNumber(a, "Recovery hold (seconds)", 10, 30, recover(a), n -> save(a, "whmRecover", n))));
        int max = maxHold(a);
        list.addView(setting(a, "Longest hold", max == 0 ? "No limit" : clock(max),
                v -> Ui.pickNumber(a, "Stop the hold at (minutes, 0 = no limit)", 0, 6, max / 60,
                        n -> save(a, "whmMax", n * 60))));
        middle.addView(list, Ui.lp(Ui.MATCH, Ui.WRAP));

        JSONArray hist = Store.root(a).optJSONArray("whm");
        if (hist != null && hist.length() > 0) {
            int best = 0;
            for (int i = 0; i < hist.length(); i++) {
                JSONArray h = hist.optJSONObject(i).optJSONArray("holds");
                for (int j = 0; h != null && j < h.length(); j++) best = Math.max(best, h.optInt(j));
            }
            JSONObject last = hist.optJSONObject(hist.length() - 1);
            StringBuilder b = new StringBuilder();
            JSONArray lh = last.optJSONArray("holds");
            for (int j = 0; lh != null && j < lh.length(); j++) b.append(j == 0 ? "" : ", ").append(clock(lh.optInt(j)));
            LinearLayout card = Ui.col(a);
            card.setBackground(Ui.fill(Ui.BLACK, 4));
            Ui.pad(card, 14, 10, 14, 10);
            LinearLayout row = Ui.row(a);
            row.addView(Ui.label(a, "best hold", Ui.MUTED), Ui.lp(0, Ui.WRAP, 1));
            row.addView(Ui.display(a, clock(best), 28, Ui.CREAM));
            card.addView(row);
            card.addView(Ui.text(a, "Last time: " + b, 13, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 4, 0, 6));
            card.addView(new Trend(a, hist), Ui.lp(Ui.MATCH, Ui.dp(52)));
            middle.addView(card, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 14, 0, 0));
        }

        TextView safety = Ui.text(a, "Sit or lie down. Never in water, in the bath or while driving. "
                + "Stop if you feel unwell.", 12, Ui.MUTED, Ui.regular);
        safety.setGravity(Gravity.CENTER);
        middle.addView(safety, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 14, 0, 0));
    }

    private static void save(MainActivity a, String key, int value) {
        Store.setSetting(a, key, value);
        a.refresh();
    }

    private static View setting(MainActivity a, String name, String value, View.OnClickListener l) {
        LinearLayout r = Ui.row(a);
        r.setMinimumHeight(Ui.dp(48));
        Ui.pad(r, 4, 0, 4, 0);
        r.setBackground(Ui.press(Ui.fill(0x00000000, 0), 0));
        r.setOnClickListener(l);
        r.addView(Ui.text(a, name, 15, Ui.CREAM, Ui.regular), Ui.lp(0, Ui.WRAP, 1));
        r.addView(Ui.text(a, value, 15, Ui.CREAM, Ui.bold));
        LinearLayout wrap = Ui.col(a);
        wrap.addView(r, Ui.lp(Ui.MATCH, Ui.WRAP));
        View rule = new View(a);
        rule.setBackgroundColor(0x33F0E2D3);
        wrap.addView(rule, Ui.lp(Ui.MATCH, 1));
        return wrap;
    }

    static void begin(MainActivity a) {
        s = new Session();
        s.rounds = rounds(a);
        s.breaths = breaths(a);
        s.pace = pace(a);
        s.recover = recover(a);
        s.maxHold = maxHold(a);
        s.startedAt = s.phaseStart = s.lastTick = SystemClock.elapsedRealtime();
        a.refresh();
    }

    // ---- the running session ----

    /** Builds the session view. Returns null when there is no live session to show. */
    static View buildRunning(MainActivity a) {
        if (s == null) return null;
        long now = SystemClock.elapsedRealtime();
        if (s.phase != DONE && now - s.lastTick > 3000) {
            // The screen was left mid-session: keep what was done, don't pretend the clock kept running.
            finish(a, false);
            if (s == null) return null;
        }
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout page = Ui.col(a);
        page.setBackground(new Ui.Ground());
        Ui.pad(page, 20, 16, 20, 28);
        TextView top = Ui.label(a, "wim hof · round " + Math.min(s.round, s.rounds) + " of " + s.rounds, Ui.CREAM);
        top.setGravity(Gravity.CENTER);
        page.addView(top, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 14, 0, 0));

        LinearLayout middle = Ui.col(a);
        middle.setGravity(Gravity.CENTER);
        FrameLayout box = new FrameLayout(a);
        Orb orb = new Orb(a);
        box.addView(orb, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        LinearLayout inner = Ui.col(a);
        inner.setGravity(Gravity.CENTER);
        TextView big = Ui.display(a, "", 80, Ui.CREAM);
        big.setFontFeatureSettings("tnum");
        big.setGravity(Gravity.CENTER);
        inner.addView(big, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView small = Ui.text(a, "", 14, Ui.MUTED, Ui.regular);
        small.setGravity(Gravity.CENTER);
        inner.addView(small, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
        FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        center.gravity = Gravity.CENTER;
        box.addView(inner, center);
        middle.addView(box, Ui.lp(Ui.dp(280), Ui.dp(280)));
        TextView line = Ui.text(a, "", 15, Ui.CREAM, Ui.regular);
        line.setGravity(Gravity.CENTER);
        middle.addView(line, Ui.gap(Ui.lp(Ui.dp(290), Ui.WRAP), 0, 22, 0, 0));
        TextView holdsText = Ui.text(a, "", 13, Ui.MUTED, Ui.regular);
        holdsText.setGravity(Gravity.CENTER);
        middle.addView(holdsText, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 14, 0, 0));
        page.addView(middle, Ui.lp(Ui.MATCH, 0, 1));

        TextView bottom;
        if (s.phase == DONE) {
            bottom = Ui.button(a, "Done", Ui.JADE, Ui.BLACK, v -> {
                s = null;
                a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                a.open(MainActivity.TODAY);
            });
        } else {
            bottom = Ui.ghost(a, "End session", Ui.MUTED, false, v -> {
                finish(a, true);
                a.refresh();
            });
        }
        page.addView(bottom, Ui.lp(Ui.MATCH, Ui.dp(56)));

        // During the hold, a tap anywhere means "I need to breathe now".
        page.setOnClickListener(v -> {
            if (s != null && s.phase == HOLD) endHold(a, SystemClock.elapsedRealtime());
        });
        orb.setClickable(false);

        a.handler.post(new Runnable() {
            boolean seen;
            int waits;

            @Override
            public void run() {
                if (page.isAttachedToWindow()) seen = true;
                else if (seen || ++waits > 25) return;
                if (s == null) return;
                step(a, SystemClock.elapsedRealtime(), top, big, small, line, holdsText, orb);
                if (s != null && s.phase != DONE) a.handler.postDelayed(this, 40);
            }
        });
        return page;
    }

    private static void step(MainActivity a, long now, TextView top, TextView big, TextView small,
                             TextView line, TextView holdsText, Orb orb) {
        Session x = s;
        x.lastTick = now;
        long t = now - x.phaseStart;
        StringBuilder hb = new StringBuilder();
        for (int i = 0; i < x.holds.size(); i++) {
            hb.append(i == 0 ? "Holds: " : " · ").append(clock(x.holds.get(i)));
        }
        holdsText.setText(hb);
        top.setText(("wim hof · round " + Math.min(x.round, x.rounds) + " of " + x.rounds).toUpperCase(Locale.ROOT));
        switch (x.phase) {
            case PREP: {
                long left = PREP_MS - t;
                big.setText(String.valueOf(Math.max(1, (left + 999) / 1000)));
                small.setText("get comfortable");
                line.setText("Sit or lie down. Breathe in through the belly, then the chest. Let it go without pushing.");
                orb.set(0.5f, 0f);
                if (left <= 0) enter(x, BREATHE, now);
                break;
            }
            case BREATHE: {
                long per = (long) ((IN[x.pace] + OUT[x.pace]) * 1000);
                int breath = (int) (t / per);
                if (breath >= x.breaths) {
                    enter(x, HOLD, now);
                    cue(a, 2);
                    break;
                }
                long into = t - breath * per, in = (long) (IN[x.pace] * 1000);
                if (breath != x.lastBreath) {
                    x.lastBreath = breath;
                    cue(a, 0);
                }
                float size = into < in ? ease(into / (float) in) : 1f - ease((into - in) / (float) (per - in));
                orb.set(0.45f + 0.55f * size, 0f);
                big.setText(String.valueOf(breath + 1));
                small.setText(into < in ? "in" : "out");
                line.setText(breath >= x.breaths - 1 ? "Last one. Breathe in fully, let it all go, and hold."
                        : "Fully in, let go. " + (x.breaths - breath - 1) + " more.");
                break;
            }
            case HOLD: {
                int sec = (int) (t / 1000);
                big.setText(clock(sec));
                small.setText("hold on empty lungs");
                line.setText("Tap anywhere when you need to breathe.");
                orb.set(0.45f, (t % 60_000) / 60_000f);
                if (sec / 30 > x.lastChime) {
                    x.lastChime = sec / 30;
                    cue(a, 1);
                }
                if (x.maxHold > 0 && sec >= x.maxHold) endHold(a, now);
                break;
            }
            case RECOVER_IN: {
                float p = Math.min(1f, t / (float) RECOVER_IN_MS);
                orb.set(0.45f + 0.55f * ease(p), 0f);
                big.setText("IN");
                small.setText("recovery breath");
                line.setText("Breathe in all the way and hold it.");
                if (t >= RECOVER_IN_MS) enter(x, RECOVER, now);
                break;
            }
            case RECOVER: {
                long left = x.recover * 1000L - t;
                big.setText(String.valueOf(Math.max(1, (left + 999) / 1000)));
                small.setText("hold full");
                line.setText("Hold the breath in.");
                orb.set(1f, 1f - Math.max(0, left) / (x.recover * 1000f));
                if (left <= 0) {
                    cue(a, 3);
                    if (x.round >= x.rounds) {
                        finish(a, true);
                        a.refresh();
                    } else {
                        x.round++;
                        x.lastBreath = -1;
                        enter(x, BREATHE, now);
                    }
                }
                break;
            }
            default: {
                int best = 0;
                for (int h : x.holds) best = Math.max(best, h);
                big.setText(clock(best));
                small.setText("best hold today");
                line.setText(x.holds.isEmpty() ? "Nothing logged." : "Logged as today's meditation.");
                orb.set(0.7f, 0f);
            }
        }
    }

    private static void enter(Session x, int phase, long now) {
        x.phase = phase;
        x.phaseStart = now;
        x.lastChime = 0;
    }

    private static void endHold(MainActivity a, long now) {
        Session x = s;
        if (x == null || x.phase != HOLD) return;
        x.holds.add((int) ((now - x.phaseStart) / 1000));
        cue(a, 0);
        enter(x, RECOVER_IN, now);
    }

    /** Saves the holds and counts the time as meditation. */
    private static void finish(MainActivity a, boolean show) {
        Session x = s;
        if (x == null) return;
        long minutes = Math.max(1, Math.round((x.lastTick - x.startedAt) / 60_000.0));
        if (!x.holds.isEmpty()) {
            JSONObject e = new JSONObject();
            Store.put(e, "d", LocalDate.now().toString());
            JSONArray h = new JSONArray();
            for (int v : x.holds) h.put(v);
            Store.put(e, "holds", h);
            Store.put(e, "breaths", x.breaths);
            Store.put(e, "pace", PACES[x.pace]);
            Store.arr(Store.root(a), "whm").put(e);
            JSONObject d = Store.day(a, LocalDate.now());
            Store.put(d, "med", d.optInt("med", 0) + (int) minutes);
            java.time.LocalTime t = java.time.LocalTime.now();
            Store.put(d, "medAt", Store.hhmm(t.getHour() * 60 + t.getMinute()));
            Store.put(d, "whm", true);
            Store.save(a);
        }
        x.phase = DONE;
        if (!show || x.holds.isEmpty()) s = null;
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    static String clock(int sec) {
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }

    private static float ease(float p) {
        p = Math.max(0f, Math.min(1f, p));
        return (float) (0.5 - 0.5 * Math.cos(Math.PI * p));
    }

    // ---- cues: soft tones and a light buzz, so it works with eyes closed ----

    /** 0 breath tick, 1 half-minute marker, 2 start of hold, 3 let go. */
    private static void cue(Context c, int kind) {
        double[] freqs;
        double vol;
        int ms;
        switch (kind) {
            case 1:
                freqs = new double[]{528};
                vol = 0.25;
                ms = 260;
                break;
            case 2:
                freqs = new double[]{396, 528};
                vol = 0.4;
                ms = 380;
                break;
            case 3:
                freqs = new double[]{528, 396};
                vol = 0.4;
                ms = 380;
                break;
            default:
                freqs = new double[]{330};
                vol = 0.18;
                ms = 120;
        }
        if (kind != 0) buzz(c, kind == 1 ? 30 : 80);
        new Thread(() -> {
            try {
                int rate = 22050, per = rate * ms / 1000, n = per * freqs.length;
                short[] pcm = new short[n];
                for (int k = 0; k < freqs.length; k++) {
                    for (int i = 0; i < per; i++) {
                        double tt = i / (double) rate;
                        double env = Math.min(1, tt / 0.01) * Math.exp(-tt * 6);
                        pcm[k * per + i] = (short) (Math.sin(2 * Math.PI * freqs[k] * tt) * env * vol * Short.MAX_VALUE);
                    }
                }
                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(n * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                track.write(pcm, 0, n);
                track.play();
                Thread.sleep(ms * freqs.length + 150L);
                track.release();
            } catch (Exception ignored) {
            }
        }, "cue").start();
    }

    private static void buzz(Context c, long ms) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    // ---- views ----

    /** A filled circle that swells with each breath, with an arc for the hold clock. */
    static final class Orb extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float size = 0.5f, arc;

        Orb(Context c) {
            super(c);
        }

        void set(float size, float arc) {
            this.size = size;
            this.arc = arc;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f, stroke = Ui.dp(8);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setColor(Ui.CELL);
            box.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
            c.drawOval(box, p);
            if (arc > 0) {
                p.setColor(Ui.CREAM);
                p.setStrokeCap(Paint.Cap.ROUND);
                c.drawArc(box, -90, 360f * arc, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x26F0E2D3);
            c.drawCircle(w / 2, h / 2, (r - stroke * 2) * size, p);
            p.setColor(Ui.MAROON);
            c.drawCircle(w / 2, h / 2, (r - stroke * 2) * size * 0.86f, p);
        }
    }

    /** Best hold per session, oldest to newest, last 14 sessions. */
    static final class Trend extends View {
        private final int[] best;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Trend(Context c, JSONArray hist) {
            super(c);
            int n = Math.min(14, hist.length());
            best = new int[n];
            for (int i = 0; i < n; i++) {
                JSONArray h = hist.optJSONObject(hist.length() - n + i).optJSONArray("holds");
                for (int j = 0; h != null && j < h.length(); j++) best[i] = Math.max(best[i], h.optInt(j));
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            if (best.length == 0) return;
            int max = 30;
            for (int b : best) max = Math.max(max, b);
            float w = getWidth(), h = getHeight(), slot = w / 14f, bar = slot * 0.55f;
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < best.length; i++) {
                float x = w - slot * (best.length - i) + slot / 2;
                float top = h - h * (best[i] / (float) max);
                p.setColor(i == best.length - 1 ? Ui.CREAM : Ui.MUTED);
                c.drawRect(x - bar / 2, Math.min(top, h - Ui.dp(2)), x + bar / 2, h, p);
            }
        }
    }
}
