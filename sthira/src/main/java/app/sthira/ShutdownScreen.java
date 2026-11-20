package app.sthira;

import android.app.NotificationManager;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;

/** The night check-in: a few taps, then the phone goes on the charger. */
final class ShutdownScreen {

    private ShutdownScreen() {
    }

    static View build(MainActivity a) {
        final LocalDate today = LocalDate.now();
        final JSONObject log = Store.dayOrEmpty(a, today);
        final String[] train = {Store.sessionDone(a) ? "done" : log.optString("train",
                Store.isTrainingDay(a, today) ? "" : "rest")};
        final String[] med = {Store.meditated(a, today) ? "yes" : log.optBoolean("shut") ? "no" : ""};
        final String[] journal = {log.optString("journal", "")};
        final String[] vit = {Store.vitamin(a, today) ? "yes" : ""};
        final int[] woke = {Store.minutes(log.optString("woke", Store.setting(a, "lastWoke", "06:00")))};
        final int[] lights = {Store.minutes(log.optString("lights", Store.hhmm(Store.minutes(Store.shutTime(a)) + 30)))};

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
        page.addView(Ui.text(a, "A few taps. Then the phone goes on the charger.", 14, Ui.MUTED, Ui.regular),
                Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 2, 0, 0));

        LinearLayout form = Ui.col(a);
        form.setGravity(Gravity.CENTER_VERTICAL);
        form.addView(question(a, "Trained today?", new String[]{"Yes", "Rest day", "Missed"},
                new String[]{"done", "rest", "missed"}, train, Store.sessionDone(a) ? "logged in Train" : null));
        form.addView(question(a, "Meditated?", new String[]{"Yes", "No"}, new String[]{"yes", "no"}, med,
                Store.meditated(a, today) ? "filled in by the timer, " + log.optString("medAt") : null));
        form.addView(question(a, "Journaled on paper?", new String[]{"Yes", "Doing it now", "No"},
                new String[]{"yes", "now", "no"}, journal, null));
        form.addView(question(a, "Multivitamin?", new String[]{"Took it", "Skipped"}, new String[]{"yes", "no"}, vit,
                Store.vitamin(a, today) && !log.optString("vitAt").isEmpty() ? "taken at " + log.optString("vitAt") : null));
        form.addView(stepper(a, "Woke up at", woke));
        form.addView(stepper(a, "Lights out at", lights));
        page.addView(form, Ui.lp(Ui.MATCH, 0, 1));

        TextView done = Ui.button(a, "DONE. GOOD NIGHT.", Ui.OCHRE, Ui.BLACK, v -> {
            JSONObject d = Store.day(a, today);
            if (!train[0].isEmpty()) Store.put(d, "train", train[0]);
            if ("yes".equals(med[0]) && d.optInt("med", 0) == 0) Store.put(d, "med", Store.medMinutes(a));
            if ("no".equals(med[0])) Store.put(d, "med", 0);
            if (!journal[0].isEmpty()) Store.put(d, "journal", "no".equals(journal[0]) ? "no" : "yes");
            if ("yes".equals(vit[0]) && !d.optBoolean("vit", false)) Store.put(d, "vit", true);
            if ("no".equals(vit[0])) Store.put(d, "vit", false);
            Store.put(d, "woke", Store.hhmm(woke[0]));
            Store.put(d, "lights", Store.hhmm(lights[0]));
            Store.put(d, "shut", true);
            Store.put(Store.obj(Store.root(a), "settings"), "lastWoke", Store.hhmm(woke[0]));
            Store.save(a);
            NotificationManager nm = (NotificationManager) a.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Reminders.SHUTDOWN);
            a.open(MainActivity.TODAY);
        });
        done.setTypeface(Ui.bebas);
        done.setTextSize(26);
        done.setLetterSpacing(0.06f);
        page.addView(done, Ui.lp(Ui.MATCH, Ui.dp(60)));
        return page;
    }

    /** A question with two or three answers; the chosen one fills in. */
    private static View question(MainActivity a, String title, String[] names, String[] values, String[] state, String note) {
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
                state[0] = values[index];
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
    private static View stepper(MainActivity a, String title, int[] minutes) {
        LinearLayout row = Ui.row(a);
        row.addView(Ui.text(a, title, 15, Ui.CREAM, Ui.bold), Ui.lp(0, Ui.WRAP, 1));
        final TextView value = Ui.display(a, Store.hhmm(minutes[0]), 40, Ui.CREAM);
        value.setGravity(Gravity.CENTER);
        row.addView(Ui.iconButton(a, Ui.I_MINUS, Ui.CREAM, true, title + ", 15 minutes earlier", v -> {
            minutes[0] = Math.floorMod(minutes[0] - 15, 1440);
            value.setText(Store.hhmm(minutes[0]));
        }), Ui.lp(Ui.dp(48), Ui.dp(48)));
        row.addView(value, Ui.lp(Ui.dp(104), Ui.WRAP));
        row.addView(Ui.iconButton(a, Ui.I_PLUS, Ui.CREAM, true, title + ", 15 minutes later", v -> {
            minutes[0] = Math.floorMod(minutes[0] + 15, 1440);
            value.setText(Store.hhmm(minutes[0]));
        }), Ui.lp(Ui.dp(48), Ui.dp(48)));
        row.setLayoutParams(Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 12));
        return row;
    }
}
