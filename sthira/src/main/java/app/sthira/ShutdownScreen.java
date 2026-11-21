package app.sthira;

import android.app.NotificationManager;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The night check-in. It only asks what the app doesn't already know: training, meditation and the
 * multivitamin are skipped when they were logged during the day, and the sleep tracker replaces the
 * wake-up and lights-out times. Then it can start tonight's sleep tracking in the same tap.
 */
final class ShutdownScreen {

    private ShutdownScreen() {
    }

    static View build(MainActivity a) {
        final LocalDate today = LocalDate.now();
        final JSONObject log = Store.dayOrEmpty(a, today);
        final boolean trainKnown = Store.sessionDone(a) || !Store.isTrainingDay(a, today);
        final boolean medKnown = Store.meditated(a, today);
        final boolean vitKnown = Store.vitamin(a, today);
        final JSONObject night = Sleep.lastNight(a);
        final boolean wokeKnown = night != null && !night.optBoolean("short") && today.toString().equals(night.optString("date"));

        final String[] train = {log.optString("train", "")};
        final String[] med = {""};
        final String[] journal = {log.optString("journal", "")};
        final String[] vit = {""};
        final String guess = Store.wokeGuess(a, today);
        final int[] woke = {Store.minutes(log.optString("woke",
                guess != null ? guess : Store.setting(a, "lastWoke", "06:00")))};

        LinearLayout page = Ui.col(a);
        page.setBackground(new Ui.Ground());
        Ui.pad(page, 20, 16, 20, 24);
        LinearLayout top = Ui.row(a);
        top.addView(Ui.iconButton(a, Ui.I_LEFT, Ui.CREAM, true, "Back to Today", v -> a.open(MainActivity.TODAY)),
                Ui.lp(Ui.dp(46), Ui.dp(46)));
        TextView label = Ui.label(a, Store.shutTime(a), Ui.OCHRE);
        label.setGravity(Gravity.CENTER);
        top.addView(label, Ui.lp(0, Ui.WRAP, 1));
        top.addView(new View(a), Ui.lp(Ui.dp(46), Ui.dp(46)));
        page.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP));
        page.addView(Ui.display(a, "SHUTDOWN", 56, Ui.CREAM), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 8, 0, 0));

        // What the day already told us, in one line instead of questions.
        List<String> known = new ArrayList<>();
        if (Store.sessionDone(a)) known.add("training");
        else if (!Store.isTrainingDay(a, today)) known.add("rest day");
        if (medKnown) known.add("meditation " + log.optInt("med") + " min");
        if (vitKnown) known.add("multivitamin");
        if (wokeKnown && night.has("wake")) known.add("woke " + Store.hhmm(Sleep.minuteOf(night.optLong("wake"))));
        String already = known.isEmpty() ? "Nothing logged yet today." : "Already logged: " + String.join(" · ", known) + ".";
        page.addView(Ui.text(a, already, 13, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 4, 0, 14));

        LinearLayout form = Ui.col(a);
        if (!trainKnown) {
            form.addView(question(a, "Trained today?", new String[]{"Yes", "Rest day", "Missed"},
                    new String[]{"done", "rest", "missed"}, train, null));
        }
        if (!medKnown) {
            form.addView(question(a, "Meditated?", new String[]{"Yes", "No"}, new String[]{"yes", "no"}, med, null));
        }
        form.addView(question(a, "Journaled on paper?", new String[]{"Yes", "Doing it now", "No"},
                new String[]{"yes", "now", "no"}, journal, null));
        if (!vitKnown) {
            form.addView(question(a, "Multivitamin?", new String[]{"Took it", "Skipped"}, new String[]{"yes", "no"}, vit, null));
        }
        if (!wokeKnown) {
            String from = !log.optString("woke").isEmpty() ? "" : guess == null ? " (last time's answer, check it)"
                    : guess.equals(log.optString("alarmOff")) ? " (when the alarm was stopped)" : " (first look at the phone)";
            form.addView(stepper(a, "Woke up at" + from, woke));
        }
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(form, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        page.addView(scroll, Ui.lp(Ui.MATCH, 0, 1));

        Runnable save = () -> {
            JSONObject d = Store.day(a, today);
            if (!trainKnown && !train[0].isEmpty()) Store.put(d, "train", train[0]);
            if (!trainKnown && train[0].isEmpty() && !Store.isTrainingDay(a, today)) Store.put(d, "train", "rest");
            if ("yes".equals(med[0]) && d.optInt("med", 0) == 0) Store.put(d, "med", Store.medMinutes(a));
            if ("no".equals(med[0])) Store.put(d, "med", 0);
            if (!journal[0].isEmpty()) Store.put(d, "journal", "no".equals(journal[0]) ? "no" : "yes");
            if ("yes".equals(vit[0])) Store.put(d, "vit", true);
            if ("no".equals(vit[0])) Store.put(d, "vit", false);
            if (!wokeKnown) {
                Store.put(d, "woke", Store.hhmm(woke[0]));
                Store.put(Store.obj(Store.root(a), "settings"), "lastWoke", Store.hhmm(woke[0]));
            }
            LocalTime t = LocalTime.now();
            Store.put(d, "lights", Store.hhmm(t.getHour() * 60 + t.getMinute()));
            Store.put(d, "shut", true);
            Store.save(a);
            Sleep.armBackup(a);
            NotificationManager nm = (NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Reminders.SHUTDOWN);
        };

        if (Sleep.active(a) == null) {
            String alarm = Sleep.MODE_TRACK.equals(Sleep.mode(a)) ? "no alarm" : "alarm " + Sleep.alarmTime(a);
            TextView go = Ui.button(a, "GOOD NIGHT · START SLEEP", Ui.OCHRE, Ui.BLACK, v -> {
                save.run();
                a.open(MainActivity.SLEEP);
                Sleep.start(a);
            });
            go.setTypeface(Ui.bebas);
            go.setTextSize(24);
            go.setLetterSpacing(0.04f);
            page.addView(go, Ui.lp(Ui.MATCH, Ui.dp(60)));
            TextView sub = Ui.text(a, "Starts sleep tracking, " + alarm + ".", 12, Ui.MUTED, Ui.regular);
            sub.setGravity(Gravity.CENTER);
            page.addView(sub, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
            page.addView(Ui.ghost(a, "Done, without tracking", Ui.CREAM, false, v -> {
                save.run();
                a.open(MainActivity.TODAY);
            }), Ui.gap(Ui.lp(Ui.MATCH, Ui.dp(52)), 0, 8, 0, 0));
        } else {
            TextView done = Ui.button(a, "DONE. GOOD NIGHT.", Ui.OCHRE, Ui.BLACK, v -> {
                save.run();
                a.open(MainActivity.TODAY);
            });
            done.setTypeface(Ui.bebas);
            done.setTextSize(26);
            page.addView(done, Ui.lp(Ui.MATCH, Ui.dp(60)));
        }
        return page;
    }

    /** A question with two or three answers; the chosen one fills in. Tapping it again clears it. */
    static View question(MainActivity a, String title, String[] names, String[] values, String[] state, String note) {
        LinearLayout box = Ui.col(a);
        LinearLayout head = Ui.row(a);
        head.addView(Ui.text(a, title, 15, Ui.CREAM, Ui.bold), Ui.lp(0, Ui.WRAP, 1));
        if (note != null) head.addView(Ui.text(a, note, 12, Ui.JADE, Ui.regular));
        box.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP));
        LinearLayout row = Ui.row(a);
        final TextView[] chips = new TextView[names.length];
        final Runnable paint = () -> {
            for (int i = 0; i < chips.length; i++) {
                boolean on = values[i].equals(state[0]);
                chips[i].setTextColor(on ? Ui.BLACK : Ui.CREAM);
                chips[i].setBackground(Ui.press(on ? Ui.fill(Ui.JADE, 4) : Ui.stroke(Ui.MUTED, 4, false), 4));
            }
        };
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            chips[i] = Ui.text(a, names[i], 14, Ui.CREAM, Ui.bold);
            chips[i].setGravity(Gravity.CENTER);
            chips[i].setOnClickListener(v -> {
                state[0] = values[index].equals(state[0]) ? "" : values[index];
                paint.run();
            });
            row.addView(chips[i], Ui.gap(Ui.lp(0, Ui.dp(48), 1), i == 0 ? 0 : 4, 0, i == names.length - 1 ? 0 : 4, 0));
        }
        paint.run();
        box.addView(row, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        box.setLayoutParams(Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 18));
        return box;
    }

    /** A time with minus and plus buttons that move it 15 minutes. */
    static View stepper(MainActivity a, String title, int[] minutes) {
        return stepper(a, title, minutes, 15, true);
    }

    /** A value with minus and plus buttons: a clock time when `clock`, otherwise a plain number from 0. */
    static View stepper(MainActivity a, String title, int[] value, int step, boolean clock) {
        LinearLayout row = Ui.row(a);
        row.addView(Ui.text(a, title, 15, Ui.CREAM, Ui.bold), Ui.lp(0, Ui.WRAP, 1));
        final TextView shown = Ui.display(a, clock ? Store.hhmm(value[0]) : String.valueOf(value[0]), 40, Ui.CREAM);
        shown.setGravity(Gravity.CENTER);
        row.addView(Ui.iconButton(a, Ui.I_MINUS, Ui.CREAM, true, title + ", less", v -> {
            value[0] = clock ? Math.floorMod(value[0] - step, 1440) : Math.max(0, value[0] - step);
            shown.setText(clock ? Store.hhmm(value[0]) : String.valueOf(value[0]));
        }), Ui.lp(Ui.dp(48), Ui.dp(48)));
        row.addView(shown, Ui.lp(Ui.dp(104), Ui.WRAP));
        row.addView(Ui.iconButton(a, Ui.I_PLUS, Ui.CREAM, true, title + ", more", v -> {
            value[0] = clock ? Math.floorMod(value[0] + step, 1440) : Math.min(240, value[0] + step);
            shown.setText(clock ? Store.hhmm(value[0]) : String.valueOf(value[0]));
        }), Ui.lp(Ui.dp(48), Ui.dp(48)));
        row.setLayoutParams(Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 12));
        return row;
    }
}
