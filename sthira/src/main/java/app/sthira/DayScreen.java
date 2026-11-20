package app.sthira;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/** Edit what was logged on any past day (or today): training, meditation, journal, multivitamin, shutdown. */
final class DayScreen {

    private DayScreen() {
    }

    static View build(MainActivity a) {
        final LocalDate d = a.editDay == null ? LocalDate.now() : a.editDay;
        final JSONObject log = Store.dayOrEmpty(a, d);

        final String[] train = {log.optString("train", "")};
        final int[] med = {log.optInt("med", 0)};
        final String[] journal = {log.optString("journal", "")};
        final String[] vit = {log.has("vit") ? (log.optBoolean("vit") ? "yes" : "no") : ""};
        final String[] shut = {log.optBoolean("shut", false) ? "yes" : ""};
        final boolean hasTimes = log.has("woke") || log.has("lights");
        final int[] woke = {Store.minutes(log.optString("woke", "06:00"))};
        final int[] lights = {Store.minutes(log.optString("lights", "23:00"))};

        LinearLayout page = Ui.col(a);
        page.setBackground(new Ui.Ground());
        Ui.pad(page, 20, 16, 20, 24);
        LinearLayout top = Ui.row(a);
        top.addView(Ui.iconButton(a, Ui.I_LEFT, Ui.CREAM, true, "Back without saving", v -> a.open(a.editFrom)),
                Ui.lp(Ui.dp(46), Ui.dp(46)));
        TextView label = Ui.label(a, "edit log", Ui.OCHRE);
        label.setGravity(Gravity.CENTER);
        top.addView(label, Ui.lp(0, Ui.WRAP, 1));
        top.addView(new View(a), Ui.lp(Ui.dp(46), Ui.dp(46)));
        page.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP));

        String title = d.equals(LocalDate.now()) ? "TODAY" : d.equals(LocalDate.now().minusDays(1)) ? "YESTERDAY"
                : d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT) + " " + d.getDayOfMonth();
        page.addView(Ui.display(a, title, 56, Ui.CREAM), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 8, 0, 0));
        page.addView(Ui.text(a, d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + d.getYear() + ". Tap an answer again to clear it.", 13, Ui.MUTED, Ui.regular),
                Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 2, 0, 14));

        LinearLayout form = Ui.col(a);
        form.addView(ShutdownScreen.question(a, "Training", new String[]{"Done", "Rest day", "Missed"},
                new String[]{"done", "rest", "missed"}, train, null));
        form.addView(ShutdownScreen.stepper(a, "Meditation, min", med, 5, false));
        if (log.optBoolean("whm", false)) {
            form.addView(Ui.text(a, "Includes a Wim Hof session.", 12, Ui.MUTED, Ui.regular),
                    Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, -6, 0, 14));
        }
        form.addView(ShutdownScreen.question(a, "Journaled on paper", new String[]{"Yes", "No"},
                new String[]{"yes", "no"}, journal, null));
        form.addView(ShutdownScreen.question(a, "Multivitamin", new String[]{"Took it", "Skipped"},
                new String[]{"yes", "no"}, vit, null));
        form.addView(ShutdownScreen.question(a, "Shutdown", new String[]{"Done"}, new String[]{"yes"}, shut, null));
        if (hasTimes) {
            form.addView(ShutdownScreen.stepper(a, "Woke up at", woke));
            form.addView(ShutdownScreen.stepper(a, "Lights out at", lights));
        }
        JSONObject night = nightEnding(a, d);
        if (night != null) {
            TextView s = Ui.text(a, "Sleep tracked: " + Sleep.hm(night.optLong("tst")) + " asleep, score "
                    + night.optInt("score") + ". Open Sleep for the details.", 13, Ui.CREAM, Ui.regular);
            form.addView(s, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 4, 0, 8));
        }
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(form, new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        page.addView(scroll, Ui.lp(Ui.MATCH, 0, 1));

        TextView save = Ui.button(a, "SAVE", Ui.OCHRE, Ui.BLACK, v -> {
            JSONObject day = Store.day(a, d);
            set(day, "train", train[0]);
            if (med[0] > 0) {
                Store.put(day, "med", med[0]);
            } else {
                day.remove("med");
                day.remove("medAt");
                day.remove("whm");
            }
            set(day, "journal", journal[0]);
            if (vit[0].isEmpty()) {
                day.remove("vit");
                day.remove("vitAt");
            } else {
                Store.put(day, "vit", "yes".equals(vit[0]));
                if (!"yes".equals(vit[0])) day.remove("vitAt");
            }
            if ("yes".equals(shut[0])) Store.put(day, "shut", true);
            else day.remove("shut");
            if (hasTimes) {
                Store.put(day, "woke", Store.hhmm(woke[0]));
                Store.put(day, "lights", Store.hhmm(lights[0]));
            }
            Store.save(a);
            a.open(a.editFrom);
        });
        save.setTypeface(Ui.bebas);
        save.setTextSize(26);
        page.addView(save, Ui.lp(Ui.MATCH, Ui.dp(60)));
        return page;
    }

    private static void set(JSONObject day, String key, String value) {
        if (value.isEmpty()) day.remove(key);
        else Store.put(day, key, value);
    }

    /** The tracked night that ended on this date, if any. */
    static JSONObject nightEnding(MainActivity a, LocalDate d) {
        org.json.JSONArray all = Sleep.nights(a);
        for (int i = all.length() - 1; i >= 0; i--) {
            JSONObject n = all.optJSONObject(i);
            if (n != null && d.toString().equals(n.optString("date"))) return n;
        }
        return null;
    }

    /** Opens the editor for a day, coming back to `from` afterwards. */
    static void open(MainActivity a, LocalDate d, int from) {
        a.editDay = d;
        a.editFrom = from;
        a.open(MainActivity.DAY);
    }
}
