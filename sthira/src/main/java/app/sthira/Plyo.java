package app.sthira;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Plyo mode on the Train tab: the five kinds of plyometric (deep, pogos, locomotive, lateral, vertical), each with
 * its drills, and a live tracker that uses the accelerometer for flight time, jump height, ground contact and
 * reactive strength, and the microphone to count landings when the phone is lying on the floor.
 */
final class Plyo {

    static final int REQ_MIC = 11;
    static final int CONTACT_CAP = 80;   // foot contacts per session for a beginner-intermediate (NSCA)

    /** What the tracker reports for a drill. */
    static final int COUNT = 0, HEIGHT = 1, REACTIVE = 2;

    static final class Ex {
        final String type, name, dose, cue;
        final int metric;
        final boolean distance;

        Ex(String type, String name, String dose, int metric, boolean distance, String cue) {
            this.type = type;
            this.name = name;
            this.dose = dose;
            this.metric = metric;
            this.distance = distance;
            this.cue = cue;
        }
    }

    static final String[][] TYPES = {
            {"DEEP", "Step off a box, then stick it or rebound. Teaches you to absorb force and turn it around fast."},
            {"POGOS", "Small, stiff-ankled bounces. Builds the springy Achilles and calves behind speed."},
            {"LOCOMOTIVE", "Jumps that travel forward. Turns strength into horizontal power for sprinting."},
            {"LATERAL", "Side to side. Cutting, changing direction, and knee and ankle stability."},
            {"VERTICAL", "Straight up for max height. The classic test of explosive power."},
    };

    static final Ex[] DRILLS = {
            new Ex("DEEP", "Drop landing", "2 × 4 · 30 cm box", COUNT, false,
                    "Step off, don't jump off. Land on both feet and freeze for 2 s. As quiet as you can."),
            new Ex("DEEP", "Depth jump", "3 × 3 · 30 cm box", REACTIVE, false,
                    "Step off and touch and go: shortest ground time, then jump as high as you can. Reset between reps."),
            new Ex("DEEP", "Depth jump to broad jump", "2 × 3 · 30 cm box", REACTIVE, true,
                    "Step off, rebound forward as far as you can, stick the landing."),
            new Ex("POGOS", "Two-leg pogo", "3 × 15", REACTIVE, false,
                    "Knees nearly straight, stiff ankles, bounce off the balls of your feet. Quick and quiet."),
            new Ex("POGOS", "Single-leg pogo", "2 × 10 each leg", REACTIVE, false,
                    "Same stiff bounce on one leg. Hips level. One set per leg."),
            new Ex("POGOS", "Forward-back line pogo", "2 × 12", REACTIVE, false,
                    "Hop over a line front and back. Barely touch the ground."),
            new Ex("LOCOMOTIVE", "Broad jump", "3 × 3", COUNT, true,
                    "Swing your arms, jump forward as far as you can, stick it for 2 s. Walk back."),
            new Ex("LOCOMOTIVE", "Bounding", "3 × 6 contacts", REACTIVE, false,
                    "Long springy strides from one leg to the other. Drive the knee, land under your hips."),
            new Ex("LOCOMOTIVE", "Single-leg hop", "2 × 4 each leg", COUNT, true,
                    "Hop forward on one leg and stick each landing before the next."),
            new Ex("LATERAL", "Skater bound", "3 × 3 each side", COUNT, false,
                    "Push sideways off one leg, land on the other, stick for 2 s. Knee over toes."),
            new Ex("LATERAL", "Lateral line hop", "3 × 10", REACTIVE, false,
                    "Feet together, hop side to side over a line. Quick light contacts."),
            new Ex("LATERAL", "Lateral hop and stick", "2 × 4 each side", COUNT, false,
                    "One leg, hop sideways and freeze. Hip, knee and ankle stacked."),
            new Ex("VERTICAL", "Countermovement jump", "3 × 3", HEIGHT, false,
                    "Quick dip, swing your arms, jump as high as you can, land soft. Reset every rep."),
            new Ex("VERTICAL", "Squat jump", "3 × 3", HEIGHT, false,
                    "Hold the bottom for 2 s, no dip, then explode straight up."),
            new Ex("VERTICAL", "Tuck jump", "2 × 5", REACTIVE, false,
                    "Jump and pull your knees to your chest, land soft and go again."),
    };

    private Plyo() {
    }

    static boolean on(Context c) {
        return "plyo".equals(Store.setting(c, "trainMode", "lift"));
    }

    /** Where the phone sits during a set: on the floor (microphone times each landing) or in a pocket (motion). */
    static boolean floor(Context c) {
        return !"pocket".equals(Store.setting(c, "plyoPlace", "floor"));
    }

    static Ex find(String name) {
        for (Ex e : DRILLS) if (e.name.equals(name)) return e;
        return null;
    }

    /** LIFT | PLYO switch at the top of the Train tab. */
    static View modes(MainActivity a) {
        boolean plyo = on(a);
        LinearLayout r = Ui.row(a);
        String[] names = {"LIFT", "PLYO"};
        for (int i = 0; i < 2; i++) {
            boolean sel = (i == 1) == plyo;
            final String mode = i == 1 ? "plyo" : "lift";
            TextView t = Ui.text(a, names[i], 13, sel ? Ui.BLACK : Ui.MUTED, Ui.bold);
            t.setLetterSpacing(0.08f);
            t.setGravity(Gravity.CENTER);
            t.setBackground(Ui.press(sel ? Ui.fill(Ui.JADE, 4) : Ui.stroke(Ui.MUTED, 4, false), 4));
            t.setContentDescription(names[i] + " mode");
            t.setOnClickListener(v -> {
                Store.setSetting(a, "trainMode", mode);
                a.refresh();
            });
            r.addView(t, Ui.gap(Ui.lp(0, Ui.dp(40), 1), i == 0 ? 0 : 4, 0, i == 0 ? 4 : 0, 0));
        }
        return r;
    }

    /** ON THE FLOOR | IN A POCKET switch on the tracker screen. */
    static View places(MainActivity a) {
        boolean floor = floor(a);
        LinearLayout r = Ui.row(a);
        String[] names = {"ON THE FLOOR", "IN A POCKET"};
        for (int i = 0; i < 2; i++) {
            boolean sel = (i == 0) == floor;
            final String place = i == 0 ? "floor" : "pocket";
            TextView t = Ui.text(a, names[i], 13, sel ? Ui.BLACK : Ui.MUTED, Ui.bold);
            t.setLetterSpacing(0.08f);
            t.setGravity(Gravity.CENTER);
            t.setBackground(Ui.press(sel ? Ui.fill(Ui.JADE, 4) : Ui.stroke(Ui.MUTED, 4, false), 4));
            t.setContentDescription("Phone " + names[i].toLowerCase(Locale.ROOT));
            t.setOnClickListener(v -> {
                Store.setSetting(a, "plyoPlace", place);
                a.refresh();
            });
            r.addView(t, Ui.gap(Ui.lp(0, Ui.dp(40), 1), i == 0 ? 0 : 4, 0, i == 0 ? 4 : 0, 0));
        }
        return r;
    }

    /** "0.41 s" */
    static String secs(long ms) {
        return String.format(Locale.ROOT, "%.2f s", ms / 1000.0);
    }

    // ---- history ----

    private static JSONObject log(Context c) {
        return Store.obj(Store.obj(Store.root(c), "plyo"), "log");
    }

    static int contactsOn(Context c, LocalDate d) {
        JSONObject log = log(c);
        String day = d.toString();
        int n = 0;
        for (java.util.Iterator<String> it = log.keys(); it.hasNext(); ) {
            JSONArray sets = log.optJSONArray(it.next());
            for (int i = 0; sets != null && i < sets.length(); i++) {
                JSONObject s = sets.optJSONObject(i);
                if (s != null && day.equals(s.optString("d"))) n += s.optInt("n");
            }
        }
        return n;
    }

    static int setsOn(Context c, String name, LocalDate d) {
        JSONArray sets = log(c).optJSONArray(name);
        int n = 0;
        for (int i = 0; sets != null && i < sets.length(); i++) {
            JSONObject s = sets.optJSONObject(i);
            if (s != null && d.toString().equals(s.optString("d"))) n++;
        }
        return n;
    }

    /** "best 41 cm · RSI 1.9 · 2.3 m", or "" when the drill has never been tracked. */
    static String bests(Context c, Ex ex) {
        JSONArray sets = log(c).optJSONArray(ex.name);
        if (sets == null || sets.length() == 0) return "";
        int h = 0, rsi = 0, dist = 0, gct = Integer.MAX_VALUE, rep = Integer.MAX_VALUE;
        for (int i = 0; i < sets.length(); i++) {
            JSONObject s = sets.optJSONObject(i);
            if (s == null) continue;
            h = Math.max(h, s.optInt("h"));
            rsi = Math.max(rsi, s.optInt("rsi"));
            dist = Math.max(dist, s.optInt("dist"));
            if (s.optInt("gct") > 0) gct = Math.min(gct, s.optInt("gct"));
            if (s.optInt("bestRep") > 0) rep = Math.min(rep, s.optInt("bestRep"));
        }
        StringBuilder b = new StringBuilder("best");
        if (ex.metric != COUNT && h > 0) b.append(' ').append(h / 10).append(" cm");
        if (ex.metric == REACTIVE && gct < Integer.MAX_VALUE) b.append(" · ").append(gct).append(" ms");
        if (ex.metric == REACTIVE && rsi > 0) b.append(String.format(Locale.ROOT, " · RSI %.2f", rsi / 100.0));
        if (ex.distance && dist > 0) b.append(String.format(Locale.ROOT, " · %.2f m", dist / 100.0));
        if (rep < Integer.MAX_VALUE) b.append(" · fastest rep ").append(secs(rep));
        return b.length() > 4 ? b.toString() : sets.length() + " sets logged";
    }

    /** The last few logged sets of a drill, newest first: "28 Nov · 15 landings · avg 0.41 s". */
    static String recent(Context c, Ex ex, int max) {
        JSONArray sets = log(c).optJSONArray(ex.name);
        StringBuilder b = new StringBuilder();
        for (int i = sets == null ? -1 : sets.length() - 1, k = 0; i >= 0 && k < max; i--, k++) {
            JSONObject s = sets.optJSONObject(i);
            if (s == null) continue;
            if (b.length() > 0) b.append('\n');
            String d = s.optString("d");
            try {
                d = LocalDate.parse(d).format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH));
            } catch (Exception ignored) {
            }
            int n = s.optInt("n");
            b.append(d).append(" · ").append(n).append(n == 1 ? " landing" : " landings");
            if (s.optInt("avgRep") > 0) b.append(" · avg ").append(secs(s.optInt("avgRep")));
            if (s.optInt("h") > 0) b.append(" · ").append(s.optInt("h") / 10).append(" cm");
        }
        return b.toString();
    }

    // ---- the list on the Train tab ----

    static View buildList(MainActivity a) {
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout body = Ui.col(a);
        body.addView(modes(a), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 12));

        int today = contactsOn(a, LocalDate.now());
        LinearLayout card = Ui.card(a, Ui.BLACK, null);
        card.addView(Ui.titled(a, today + " / " + CONTACT_CAP,
                today >= CONTACT_CAP ? "Enough foot contacts for today. Quality drops after this."
                        : "foot contacts today. Do plyos fresh, right after the warm-up.", Ui.CREAM, Ui.MUTED),
                Ui.lp(0, Ui.WRAP, 1));
        body.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP));
        body.addView(Ui.text(a, "Tap a drill to track it. Phone on the floor nearby: the microphone hears every "
                        + "landing and logs the time from one landing to the next for each rep. Phone in a tight pocket "
                        + "instead: it measures each jump's height, ground contact time and RSI.", 13, Ui.CREAM, Ui.regular),
                Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 10, 0, 4));

        for (String[] type : TYPES) {
            body.addView(Ui.label(a, type[0], Ui.OCHRE), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 18, 0, 2));
            body.addView(Ui.text(a, type[1], 12, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 2));
            for (Ex ex : DRILLS) {
                if (!ex.type.equals(type[0])) continue;
                LinearLayout row = Ui.card(a, Ui.BLACK, v -> {
                    Store.setSetting(a, "plyoEx", ex.name);
                    phase = IDLE;
                    a.open(MainActivity.PLYO);
                });
                LinearLayout texts = Ui.col(a);
                texts.addView(Ui.text(a, ex.name, 16, Ui.CREAM, Ui.bold));
                int done = setsOn(a, ex.name, LocalDate.now());
                String sub = ex.dose + (done > 0 ? " · " + done + " done today" : "");
                texts.addView(Ui.text(a, sub, 12, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 3, 0, 0));
                String best = bests(a, ex);
                if (!best.isEmpty()) {
                    texts.addView(Ui.text(a, best, 12, Ui.JADE, Ui.regular), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 3, 0, 0));
                }
                row.addView(texts, Ui.lp(0, Ui.WRAP, 1));
                body.addView(row, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
            }
        }
        return Ui.page(a, Ui.header(a, "plyometrics", "PLYO", null), body);
    }

    // ---- the tracker screen ----

    static final int IDLE = 0, COUNTDOWN = 1, RUNNING = 2, SUMMARY = 3;
    static int phase = IDLE;
    private static long countdownEnd;
    private static Tracker tracker;
    private static Result result;

    static final class Result {
        int contacts, bestCm10, bestRsi100, avgGct, rhythm, dist;
        boolean motion;
        /** Microphone: landing-to-landing time of each rep in ms (rests left out), and first to last landing. */
        long[] reps = new long[0];
        int avgRep, bestRep, steadyPct, spanMs;
    }

    static Result summarize(JumpDetector d, Ex ex) {
        Result r = new Result();
        r.contacts = d.contacts();
        r.motion = d.usingMotion();
        r.rhythm = (int) d.thudRhythmMs();
        long gctSum = 0;
        int gctN = 0;
        for (JumpDetector.Jump j : d.jumps()) {
            boolean counts = ex.metric == HEIGHT || (ex.metric == REACTIVE && j.contactMs > 0);
            if (counts) r.bestCm10 = Math.max(r.bestCm10, (int) Math.round(j.heightCm() * 10));
            if (ex.metric == REACTIVE && j.contactMs > 0) {
                gctSum += j.contactMs;
                gctN++;
                r.bestRsi100 = Math.max(r.bestRsi100, (int) Math.round(j.rsi() * 100));
            }
        }
        r.avgGct = gctN == 0 ? 0 : (int) (gctSum / gctN);
        if (!r.motion) {
            long[] gaps = d.thudGapsMs();
            int k = 0;
            for (long g : gaps) if (g <= JumpDetector.MAX_REP_MS) k++;
            r.reps = new long[k];
            k = 0;
            for (long g : gaps) if (g <= JumpDetector.MAX_REP_MS) r.reps[k++] = g;
            if (k > 0) {
                long sum = 0, best = Long.MAX_VALUE;
                for (long g : r.reps) {
                    sum += g;
                    best = Math.min(best, g);
                }
                double mean = sum / (double) k;
                r.avgRep = (int) Math.round(mean);
                r.bestRep = (int) best;
                if (k >= 3) {
                    double var = 0;
                    for (long g : r.reps) var += (g - mean) * (g - mean);
                    r.steadyPct = (int) Math.round(100 * Math.sqrt(var / k) / mean);
                }
            }
            r.spanMs = (int) d.thudSpanMs();
        }
        return r;
    }

    /** One line per landing, newest last. */
    static String repLines(JumpDetector d, Ex ex, int max) {
        StringBuilder b = new StringBuilder();
        if (!d.usingMotion()) {
            int n = d.thudCount();
            if (n == 0) return "listening for landings";
            long[] gaps = d.thudGapsMs();
            for (int i = Math.max(0, n - max); i < n; i++) {
                if (b.length() > 0) b.append('\n');
                b.append('#').append(i + 1).append("   ");
                if (i == 0) b.append("first landing");
                else if (gaps[i - 1] <= JumpDetector.MAX_REP_MS) b.append(secs(gaps[i - 1]));
                else b.append("after a ").append(String.format(Locale.ROOT, "%.0f s", gaps[i - 1] / 1000.0)).append(" rest");
            }
            return b.toString();
        }
        List<JumpDetector.Jump> js = d.jumps();
        for (int i = Math.max(0, js.size() - max); i < js.size(); i++) {
            JumpDetector.Jump j = js.get(i);
            if (b.length() > 0) b.append('\n');
            b.append('#').append(i + 1).append("   ");
            boolean reactive = ex.metric == REACTIVE && j.contactMs > 0;
            if (ex.metric == HEIGHT || reactive) {
                b.append(String.format(Locale.ROOT, "%.0f cm", j.heightCm()));
            }
            if (reactive) {
                b.append(String.format(Locale.ROOT, " · %d ms · RSI %.2f", j.contactMs, j.rsi()));
            } else if (ex.metric == REACTIVE) {
                b.append("first contact");
            } else if (ex.metric == COUNT) {
                b.append("landed");
            }
        }
        return b.toString();
    }

    static View buildTrack(MainActivity a) {
        final Ex ex = find(Store.setting(a, "plyoEx", DRILLS[0].name));
        final Ex drill = ex == null ? DRILLS[0] : ex;
        if (phase == RUNNING && tracker == null) phase = IDLE;   // came back after the app was killed

        LinearLayout page = Ui.col(a);
        page.setBackground(new Ui.Ground());
        Ui.pad(page, 20, 16, 20, 28);

        LinearLayout top = Ui.row(a);
        top.addView(Ui.iconButton(a, Ui.I_LEFT, Ui.CREAM, true, "Back to Train", v -> leave(a)),
                Ui.lp(Ui.dp(46), Ui.dp(46)));
        TextView label = Ui.label(a, drill.type.toLowerCase(Locale.ROOT), Ui.JADE);
        label.setGravity(Gravity.CENTER);
        top.addView(label, Ui.lp(0, Ui.WRAP, 1));
        top.addView(new View(a), Ui.lp(Ui.dp(46), Ui.dp(46)));
        page.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP));

        TextView name = Ui.display(a, drill.name.toUpperCase(Locale.ROOT), drill.name.length() > 16 ? 30 : 40, Ui.CREAM);
        name.setGravity(Gravity.CENTER);
        page.addView(name, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 10, 0, 0));
        int done = setsOn(a, drill.name, LocalDate.now());
        TextView dose = Ui.text(a, drill.dose + (done > 0 ? " · " + done + " done today" : ""), 13, Ui.MUTED, Ui.regular);
        dose.setGravity(Gravity.CENTER);
        page.addView(dose, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 4, 0, 0));

        LinearLayout middle = Ui.col(a);
        middle.setGravity(Gravity.CENTER);
        final TextView big = Ui.display(a, "", 96, Ui.CREAM);
        big.setFontFeatureSettings("tnum");
        big.setGravity(Gravity.CENTER);
        middle.addView(big, Ui.lp(Ui.MATCH, Ui.WRAP));
        final TextView sub = Ui.text(a, "", 14, Ui.MUTED, Ui.regular);
        sub.setGravity(Gravity.CENTER);
        middle.addView(sub, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 2, 0, 0));
        final TextView lines = Ui.text(a, "", 15, Ui.CREAM, Ui.regular);
        lines.setGravity(Gravity.CENTER);
        lines.setFontFeatureSettings("tnum");
        middle.addView(lines, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 18, 0, 0));
        page.addView(middle, Ui.lp(Ui.MATCH, 0, 1));

        if (phase == SUMMARY && result != null) {
            a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            big.setText(String.valueOf(result.contacts));
            sub.setText(result.contacts == 1 ? "foot contact" : "foot contacts");
            lines.setText(summaryText(drill, result));
            if (drill.distance) {
                page.addView(Ui.ghost(a, result.dist > 0 ? String.format(Locale.ROOT, "Best distance %.2f m · change", result.dist / 100.0)
                        : "Add your best distance", Ui.CREAM, true, v -> Ui.pickNumber(a, "Best distance, cm", 0, 400,
                        result.dist > 0 ? result.dist : lastDist(a, drill), value -> {
                            result.dist = value;
                            a.refresh();
                        })), Ui.gap(Ui.lp(Ui.MATCH, Ui.dp(52)), 0, 0, 0, 8));
            }
            LinearLayout buttons = Ui.row(a);
            buttons.addView(Ui.ghost(a, "Discard", Ui.MUTED, false, v -> {
                result = null;
                phase = IDLE;
                a.refresh();
            }), Ui.lp(0, Ui.dp(56), 1));
            TextView save = Ui.button(a, "SAVE SET", Ui.JADE, Ui.BLACK, v -> {
                save(a, drill, result);
                result = null;
                phase = IDLE;
                a.refresh();
            });
            save.setTypeface(Ui.bebas);
            save.setTextSize(24);
            buttons.addView(save, Ui.gap(Ui.lp(0, Ui.dp(56), 2), 8, 0, 0, 0));
            page.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP));
            return page;
        }

        if (phase == COUNTDOWN || phase == RUNNING) {
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            TextView stop = Ui.ghost(a, "Hold to stop", Ui.CREAM, false,
                    v -> Toast.makeText(a, "Press and hold to stop.", Toast.LENGTH_SHORT).show());
            stop.setOnLongClickListener(v -> {
                if (phase == RUNNING) finish(a, drill, false);
                else {
                    phase = IDLE;
                    a.refresh();
                }
                return true;
            });
            page.addView(stop, Ui.lp(Ui.MATCH, Ui.dp(60)));
            tick(a, drill, big, sub, lines);
            return page;
        }

        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        big.setText(String.valueOf(contactsOn(a, LocalDate.now())));
        big.setTextSize(64);
        sub.setText("foot contacts today, of " + CONTACT_CAP);
        String best = bests(a, drill);
        String last = recent(a, drill, 3);
        lines.setText(drill.cue + "\n\n" + placement(drill, floor(a)) + (best.isEmpty() ? "" : "\n\n" + best)
                + (last.isEmpty() ? "" : "\n\nlast sets\n" + last));
        lines.setTextSize(14);
        page.addView(places(a), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 10));
        TextView start = Ui.button(a, "START", Ui.JADE, Ui.BLACK, v -> begin(a));
        start.setTypeface(Ui.bebas);
        start.setTextSize(28);
        start.setLetterSpacing(0.06f);
        page.addView(start, Ui.lp(Ui.MATCH, Ui.dp(60)));
        return page;
    }

    private static String placement(Ex ex, boolean floor) {
        if (floor) {
            return "Tap start, set the phone on the floor screen up, a metre or two from where you land, and get in "
                    + "place within 5 s. The microphone times every landing: the gap between two landings is your rep "
                    + "time. It stops by itself after your last rep, or hold to stop."
                    + (ex.metric == HEIGHT ? " Jump height needs the phone in a pocket." : "");
        }
        String what = ex.metric == HEIGHT ? "your jump height"
                : ex.metric == REACTIVE ? "height, ground contact time and RSI" : "every landing";
        return "Tap start, then put the phone in a tight pocket within 5 s. It tracks " + what
                + " and stops by itself after your last rep.";
    }

    private static String summaryText(Ex ex, Result r) {
        StringBuilder b = new StringBuilder();
        if (!r.motion) {
            if (r.contacts == 0) {
                return "Nothing heard. Put the phone on the floor within a metre or two of where you land, in a quiet "
                        + "room, and land on a hard floor rather than a mat.";
            }
            if (r.reps.length > 0) {
                b.append("rep time, landing to landing\n");
                for (int i = 0; i < r.reps.length; i++) {
                    if (i > 0) b.append(i % 5 == 0 ? "\n" : "   ");
                    b.append(String.format(Locale.ROOT, "%.2f", r.reps[i] / 1000.0));
                }
                b.append("\n\navg ").append(secs(r.avgRep)).append(" · fastest ").append(secs(r.bestRep));
                if (r.reps.length >= 3) b.append(" · ±").append(r.steadyPct).append('%');
            } else {
                b.append("Landings counted.");
            }
            if (r.spanMs > 0) b.append(String.format(Locale.ROOT, "\nset time %.1f s", r.spanMs / 1000.0));
            return b.toString();
        }
        if (r.bestCm10 > 0) b.append(String.format(Locale.ROOT, "best %.1f cm", r.bestCm10 / 10.0));
        if (r.avgGct > 0) b.append(b.length() > 0 ? "\n" : "").append("ground contact ").append(r.avgGct).append(" ms avg");
        if (r.bestRsi100 > 0) b.append(b.length() > 0 ? "\n" : "").append(String.format(Locale.ROOT, "RSI best %.2f", r.bestRsi100 / 100.0));
        if (b.length() == 0) b.append("Every landing counted by the motion sensor.");
        return b.toString();
    }

    private static int lastDist(Context c, Ex ex) {
        JSONArray sets = log(c).optJSONArray(ex.name);
        for (int i = sets == null ? -1 : sets.length() - 1; i >= 0; i--) {
            int d = sets.optJSONObject(i) == null ? 0 : sets.optJSONObject(i).optInt("dist");
            if (d > 0) return d;
        }
        return 200;
    }

    private static void save(Context c, Ex ex, Result r) {
        if (r == null) return;
        JSONObject s = new JSONObject();
        Store.put(s, "d", LocalDate.now().toString());
        Store.put(s, "n", r.contacts);
        if (r.bestCm10 > 0) Store.put(s, "h", r.bestCm10);
        if (r.bestRsi100 > 0) Store.put(s, "rsi", r.bestRsi100);
        if (r.avgGct > 0) Store.put(s, "gct", r.avgGct);
        if (r.dist > 0) Store.put(s, "dist", r.dist);
        if (r.reps.length > 0) {
            JSONArray reps = new JSONArray();
            for (long v : r.reps) reps.put(v);
            Store.put(s, "reps", reps);
            Store.put(s, "avgRep", r.avgRep);
            Store.put(s, "bestRep", r.bestRep);
        }
        if (r.spanMs > 0) Store.put(s, "span", r.spanMs);
        Store.put(s, "src", r.motion ? "motion" : "mic");
        Store.arr(log(c), ex.name).put(s);
        Store.save(c);
    }

    // ---- running a set ----

    private static void begin(MainActivity a) {
        // On the floor the microphone is the only sensor, so ask every time; in a pocket ask once.
        if (!Sleep.micAllowed(a) && (floor(a) || !Store.root(a).optBoolean("askedPlyoMic", false))) {
            Store.put(Store.root(a), "askedPlyoMic", true);
            Store.save(a);
            a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        phase = COUNTDOWN;
        countdownEnd = SystemClock.elapsedRealtime() + 5000;
        a.refresh();
    }

    /** Back from the microphone question: start either way; without it the motion sensor still works. */
    static void afterPermission(MainActivity a) {
        if (a.current != MainActivity.PLYO) return;
        if (floor(a) && !Sleep.micAllowed(a)) {
            Toast.makeText(a, "On the floor it listens for your landings, so it needs the microphone. Allow it in "
                    + "Settings, or switch to the pocket.", Toast.LENGTH_LONG).show();
            return;
        }
        phase = COUNTDOWN;
        countdownEnd = SystemClock.elapsedRealtime() + 5000;
    }

    private static void finish(MainActivity a, Ex ex, boolean auto) {
        if (tracker != null) {
            tracker.stop();
            result = summarize(tracker.detector, ex);
            tracker = null;
        } else {
            result = new Result();
        }
        phase = SUMMARY;
        if (auto) beep(ToneGenerator.TONE_PROP_ACK, 400);
        a.refresh();
    }

    /** Leaves the tracker screen; a set in progress is dropped. */
    static void leave(MainActivity a) {
        if (tracker != null) tracker.stop();
        tracker = null;
        result = null;
        phase = IDLE;
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        a.open(MainActivity.TRAIN);
    }

    private static void beep(int tone, int ms) {
        try {
            ToneGenerator t = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            t.startTone(tone, ms);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(t::release, ms + 300);
        } catch (Exception ignored) {
        }
    }

    private static void tick(MainActivity a, Ex ex, TextView big, TextView sub, TextView lines) {
        a.handler.post(new Runnable() {
            boolean seen;
            int waits;
            long lastBeepSec = -1;

            @Override
            public void run() {
                if (big.isAttachedToWindow()) seen = true;
                else {
                    if (seen || ++waits > 20) {
                        // Screen left some other way (tab bar, recreate): don't keep the sensors running.
                        if (seen && a.current != MainActivity.PLYO && tracker != null) {
                            tracker.stop();
                            tracker = null;
                            phase = IDLE;
                        }
                        return;
                    }
                    a.handler.postDelayed(this, 100);
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                if (phase == COUNTDOWN) {
                    long left = countdownEnd - now;
                    if (left > 0) {
                        long s = (left + 999) / 1000;
                        big.setText(String.valueOf(s));
                        sub.setText(floor(a) ? "phone on the floor" : "phone in your pocket");
                        lines.setText(ex.cue);
                        if (s <= 3 && s != lastBeepSec) {
                            lastBeepSec = s;
                            beep(ToneGenerator.TONE_PROP_BEEP, 120);
                        }
                    } else {
                        // The go beep plays first, then the microphone opens, so it doesn't count the beep.
                        beep(ToneGenerator.TONE_PROP_BEEP2, 350);
                        phase = RUNNING;
                        big.setText("GO");
                        sub.setText("");
                        a.handler.postDelayed(() -> {
                            if (phase == RUNNING && tracker == null) tracker = Tracker.start(a, !floor(a));
                        }, 450);
                    }
                } else if (phase == RUNNING && tracker != null) {
                    JumpDetector d = tracker.detector;
                    int n = d.contacts();
                    big.setText(String.valueOf(n));
                    sub.setText((n == 1 ? "contact" : "contacts") + " · " + tracker.sources(d));
                    lines.setText(repLines(d, ex, 6));
                    long last = d.lastContactNs();
                    long idleMs = (SystemClock.elapsedRealtimeNanos() - (last > 0 ? last : tracker.startedNs)) / 1_000_000L;
                    // Continuous drills end 5 s after the last landing; drills with a reset between reps wait 15 s.
                    long quiet = ex.metric == REACTIVE && !"DEEP".equals(ex.type) ? 5000 : 15000;
                    if (n > 0 && idleMs > quiet) {
                        finish(a, ex, true);
                        return;
                    }
                    if (idleMs > 90_000) {
                        finish(a, ex, true);
                        return;
                    }
                } else if (phase != RUNNING) {
                    return;
                }
                a.handler.postDelayed(this, 100);
            }
        });
    }

    /** Any touch on the app's screen while a set runs (from MainActivity): handling the phone, not a jump. */
    static void touched() {
        Tracker t = tracker;
        if (t != null) t.touched();
    }

    /**
     * The accelerometer at the fastest rate the phone allows, the proximity sensor (pocket) and the microphone in
     * 2 ms frames. Each set's raw readings are also written to files/plyo-trace so the detector can be tuned.
     */
    static final class Tracker implements SensorEventListener {
        final JumpDetector detector = new JumpDetector();
        final long startedNs = SystemClock.elapsedRealtimeNanos();
        private final Context app;
        private final boolean floor;
        private final SensorManager sm;
        private final boolean hasAccel;
        private final float proxRange;
        private volatile boolean running = true;
        private Thread mic;
        private volatile boolean micOn;
        private final StringBuilder trace = new StringBuilder();

        private Tracker(Context c, boolean motion) {
            app = c.getApplicationContext();
            floor = !motion;
            sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            hasAccel = acc != null && sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_FASTEST);
            Sensor prox = motion && sm != null ? sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) : null;
            proxRange = prox == null ? 0 : prox.getMaximumRange();
            if (prox != null) sm.registerListener(this, prox, SensorManager.SENSOR_DELAY_FASTEST);
            note("start," + (floor ? "floor" : "pocket") + "," + startedNs);
            if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                mic = new Thread(this::listen, "plyo-mic");
                mic.start();
            }
        }

        static Tracker start(Context c, boolean motion) {
            return new Tracker(c, motion);
        }

        String sources(JumpDetector d) {
            if (d.usingMotion()) return "motion sensor";
            if (floor) {
                if (d.thudCount() > 0) return d.floorByMotion() ? "felt through the floor" : "microphone";
                return micOn ? "listening" : hasAccel ? "floor sensor only" : "no sensors";
            }
            return hasAccel ? "motion sensor" : "no sensors";
        }

        private void note(String line) {
            synchronized (trace) {
                if (trace.length() < 8_000_000) trace.append(line).append('\n');
            }
        }

        void touched() {
            long t = SystemClock.elapsedRealtimeNanos();
            note("touch," + t);
            detector.touched(t);
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            if (!running) return;
            if (e.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                // Most phones report only "near" (0) or "far" (max range).
                boolean covered = e.values[0] < proxRange;
                note("prox," + e.timestamp + "," + e.values[0]);
                detector.proximity(e.timestamp, covered);
                return;
            }
            note("a," + e.timestamp + "," + e.values[0] + "," + e.values[1] + "," + e.values[2]);
            if (floor) detector.floorAccel(e.timestamp, e.values[0], e.values[1], e.values[2]);
            else detector.accel(e.timestamp, e.values[0], e.values[1], e.values[2]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

        private void listen() {
            int rate = 16000, frame = 32;   // 2 ms frames, so each landing is timed to about 2 ms
            AudioRecord rec = null;
            try {
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                // Unprocessed audio keeps the thuds sharp (no noise suppression); not every phone offers it.
                try {
                    rec = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));   // bytes: half a second of audio
                } catch (Exception e) {
                    rec = null;
                }
                if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    if (rec != null) rec.release();
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));   // bytes: half a second of audio
                }
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) return;
                rec.startRecording();
                micOn = true;
                // Landing times come from the number of samples read, the audio clock, so a late read can't shift them.
                long t0 = SystemClock.elapsedRealtimeNanos(), samples = 0;
                note("mic," + t0 + "," + rec.getAudioSource());
                short[] buf = new short[frame * 16];
                StringBuilder lines = new StringBuilder();
                while (running) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    lines.setLength(0);
                    for (int off = 0; off + frame <= n; off += frame) {
                        long sum = 0;
                        for (int i = off; i < off + frame; i++) sum += (long) buf[i] * buf[i];
                        samples += frame;
                        long t = t0 + samples * 1_000_000_000L / rate;
                        double rms = Math.sqrt(sum / (double) frame);
                        detector.audio(t, rms);
                        lines.append("m,").append(t).append(',').append((int) Math.round(rms)).append('\n');
                    }
                    samples += n % frame;   // a short read's leftover still took time
                    synchronized (trace) {
                        if (trace.length() < 8_000_000) trace.append(lines);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                micOn = false;
                if (rec != null) {
                    try {
                        rec.stop();
                    } catch (Exception ignored) {
                    }
                    rec.release();
                }
            }
        }

        void stop() {
            if (!running) return;
            running = false;
            if (sm != null) sm.unregisterListener(this);
            final String text;
            synchronized (trace) {
                text = trace.toString();
            }
            // Keep the last 10 sets' raw readings for tuning (adb shell run-as app.sthira ls files/plyo-trace).
            new Thread(() -> {
                try {
                    java.io.File dir = new java.io.File(app.getFilesDir(), "plyo-trace");
                    if (!dir.isDirectory() && !dir.mkdirs()) return;
                    String name = java.time.LocalDateTime.now().toString().replace(':', '-') + (floor ? "-floor" : "-pocket") + ".csv";
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, name))) {
                        out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    java.io.File[] all = dir.listFiles();
                    if (all != null && all.length > 10) {
                        java.util.Arrays.sort(all, (x, y) -> x.getName().compareTo(y.getName()));
                        for (int i = 0; i < all.length - 10; i++) all[i].delete();
                    }
                } catch (Exception ignored) {
                }
            }, "plyo-trace").start();
        }
    }
}
