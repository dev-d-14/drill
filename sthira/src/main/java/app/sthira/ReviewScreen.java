package app.sthira;

import android.app.TimePickerDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** The weekly review builds itself from what was logged. Settings live at the bottom. */
final class ReviewScreen {

    private ReviewScreen() {
    }

    static View build(MainActivity a) {
        LocalDate today = LocalDate.now();
        LocalDate monday = Store.monday(today);
        boolean sunday = today.getDayOfWeek().getValue() == 7;
        LinearLayout body = Ui.col(a);

        // The week's numbers.
        int sleepSum = 0, sleepNights = 0, workouts = 0, sits = 0, journals = 0;
        for (int i = 0; i < 7; i++) {
            LocalDate d = monday.plusDays(i);
            if (d.isAfter(today)) break;
            JSONObject log = Store.dayOrEmpty(a, d), before = Store.dayOrEmpty(a, d.minusDays(1));
            JSONObject tracked = DayScreen.nightEnding(a, d);
            if (tracked != null && tracked.optLong("tst") > 0) {
                sleepSum += tracked.optLong("tst");
                sleepNights++;
            } else if (!log.optString("woke").isEmpty() && !before.optString("lights").isEmpty()) {
                sleepSum += Math.floorMod(Store.minutes(log.optString("woke")) - Store.minutes(before.optString("lights")), 1440);
                sleepNights++;
            }
            if ("done".equals(Store.trained(a, d))) workouts++;
            if (Store.meditated(a, d)) sits++;
            if (Store.journaled(a, d)) journals++;
        }
        String sleep = sleepNights == 0 ? "–" : String.format(Locale.ROOT, "%dH %02d", sleepSum / sleepNights / 60, sleepSum / sleepNights % 60);
        LinearLayout r1 = Ui.row(a), r2 = Ui.row(a);
        r1.addView(tile(a, sleep, "Sleep, average · goal " + Sleep.goalMinutes(a) / 60 + "h"), Ui.gap(Ui.lp(0, Ui.WRAP, 1), 0, 0, 4, 0));
        r1.addView(tile(a, workouts + " / " + Store.trainingDaysPerWeek(a), "Workouts"), Ui.gap(Ui.lp(0, Ui.WRAP, 1), 4, 0, 0, 0));
        r2.addView(tile(a, sits + " / 7", "Meditation days"), Ui.gap(Ui.lp(0, Ui.WRAP, 1), 0, 0, 4, 0));
        r2.addView(tile(a, journals + " / 7", "Journal days"), Ui.gap(Ui.lp(0, Ui.WRAP, 1), 4, 0, 0, 0));
        body.addView(r1, Ui.lp(Ui.MATCH, Ui.WRAP));
        body.addView(r2, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));

        // The week at a glance: 1 done, 2 missed, 3 rest, 0 still to come.
        LinearLayout grid = Ui.col(a);
        grid.setBackground(Ui.fill(Ui.BLACK, 4));
        Ui.pad(grid, 12, 12, 12, 12);
        LinearLayout names = Ui.row(a);
        names.addView(new View(a), Ui.lp(Ui.dp(64), 1));
        for (String n : new String[]{"M", "T", "W", "T", "F", "S", "S"}) {
            TextView t = Ui.text(a, n, 12, Ui.MUTED, Ui.bold);
            t.setGravity(Gravity.CENTER);
            names.addView(t, Ui.lp(0, Ui.WRAP, 1));
        }
        grid.addView(names);
        boolean twice = false;
        String twiceWhat = "";
        int worstRun = 0;
        String[] rows = {"Train", "Sit", "Journal", "Vitamin"};
        for (int r = 0; r < rows.length; r++) {
            LinearLayout row = Ui.row(a);
            row.addView(Ui.text(a, rows[r], 12, Ui.CREAM, Ui.bold), Ui.lp(Ui.dp(64), Ui.WRAP));
            int run = 0;
            for (int i = 0; i < 7; i++) {
                LocalDate d = monday.plusDays(i);
                int state;
                if (d.isAfter(today)) state = 0;
                else if (r == 0) {
                    String t = Store.trained(a, d);
                    state = "done".equals(t) ? 1 : "missed".equals(t) ? 2 : "rest".equals(t) ? 3
                            : Store.isTrainingDay(a, d) && d.isBefore(today) ? 2 : d.equals(today) && Store.isTrainingDay(a, d) ? 0 : 3;
                } else {
                    boolean did = r == 1 ? Store.meditated(a, d) : r == 2 ? Store.journaled(a, d) : Store.vitamin(a, d);
                    state = did ? 1 : d.equals(today) ? 0 : 2;
                }
                run = state == 2 ? run + 1 : state == 3 ? run : 0;
                if (run >= 2 && run > worstRun) {
                    twice = true;
                    worstRun = run;
                    twiceWhat = r == 0 ? "Training" : r == 1 ? "Meditation" : r == 2 ? "Journaling" : "The multivitamin";
                }
                FrameLayout cell = new FrameLayout(a);
                if (!d.isAfter(today)) {
                    final LocalDate day = d;
                    cell.setBackground(Ui.press(Ui.fill(0x00000000, 0), 0));
                    cell.setContentDescription("Edit " + day);
                    cell.setOnClickListener(v -> DayScreen.open(a, day, MainActivity.REVIEW));
                }
                View mark = new View(a);
                FrameLayout.LayoutParams p;
                if (state == 1) {
                    mark.setBackground(Ui.fill(Ui.JADE, 8));
                    p = new FrameLayout.LayoutParams(Ui.dp(16), Ui.dp(16));
                } else if (state == 2) {
                    mark.setBackground(Ui.stroke(Ui.OCHRE, 8, false));
                    p = new FrameLayout.LayoutParams(Ui.dp(16), Ui.dp(16));
                } else if (state == 3) {
                    mark.setBackgroundColor(Ui.MUTED);
                    p = new FrameLayout.LayoutParams(Ui.dp(10), Ui.dp(2));
                } else {
                    mark.setBackground(Ui.fill(Ui.CELL, 3));
                    p = new FrameLayout.LayoutParams(Ui.dp(6), Ui.dp(6));
                }
                p.gravity = Gravity.CENTER;
                cell.addView(mark, p);
                row.addView(cell, Ui.lp(0, Ui.dp(28), 1));
            }
            grid.addView(row, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
        }
        body.addView(grid, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 12, 0, 0));
        String times = worstRun == 2 ? "twice" : worstRun == 3 ? "three times" : worstRun + " times";
        String verdict = twice ? twiceWhat + " was missed " + times + " in a row. The next one matters most."
                : "Filled is done, ring is missed, dash is a rest day. No miss came twice in a row.";
        verdict += " Tap any day to edit what was logged.";
        body.addView(Ui.text(a, verdict, 13, Ui.CREAM, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));

        // Anything unused for three weeks gets offered for removal.
        JSONObject root = Store.root(a);
        if (root.optBoolean("hideDigest", false)) {
            body.addView(Ui.ghost(a, "Show the Digest tab again", Ui.CREAM, false, v -> {
                Store.put(root, "hideDigest", false);
                Store.put(root, "digestUsed", today.toString());
                Store.save(a);
                a.refresh();
            }), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 14, 0, 0));
        } else {
            String used = root.optString("digestUsed", root.optString("installed", today.toString()));
            long idle = 0;
            try {
                idle = ChronoUnit.DAYS.between(LocalDate.parse(used), today);
            } catch (Exception ignored) {
            }
            if (idle >= 21) {
                LinearLayout prune = Ui.row(a);
                prune.setBackground(Ui.stroke(Ui.CREAM, 4, true));
                Ui.pad(prune, 12, 10, 12, 10);
                prune.addView(Ui.text(a, "Digest unused for 3 weeks. Remove it?", 13, Ui.CREAM, Ui.regular), Ui.lp(0, Ui.WRAP, 1));
                prune.addView(Ui.button(a, "Remove", Ui.CREAM, Ui.BLACK, v -> {
                    Store.put(root, "hideDigest", true);
                    Store.save(a);
                    a.refresh();
                }));
                prune.addView(Ui.ghost(a, "Keep", Ui.CREAM, false, v -> {
                    Store.put(root, "digestUsed", today.toString());
                    Store.save(a);
                    a.refresh();
                }), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 6, 0, 0, 0));
                body.addView(prune, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 14, 0, 0));
            }
        }

        // Settings.
        body.addView(Ui.label(a, "times and timers", Ui.CREAM), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 22, 0, 2));
        body.addView(timeSetting(a, "Digest arrives", "digest", Store.digestTime(a)));
        body.addView(timeSetting(a, "Home, time to sit", "home", Store.homeTime(a)));
        body.addView(timeSetting(a, "Training", "train", Store.trainTime(a)));
        body.addView(timeSetting(a, "Multivitamin reminder", "vit", Store.vitTime(a)));
        body.addView(timeSetting(a, "Shutdown", "shut", Store.shutTime(a)));
        body.addView(numberSetting(a, "Meditation length, minutes", "medMin", Store.medMinutes(a), 1, 60));
        body.addView(numberSetting(a, "Rest between sets, seconds", "rest", Store.restSeconds(a), 15, 240));
        body.addView(Ui.text(a, "Reminders fire at the digest, home, training, multivitamin and shutdown times. Each one only comes if that thing isn't done yet; training only on plan days. At shutdown time the wake-up alarm also sets itself.", 12, Ui.CREAM, Ui.regular),
                Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));

        body.addView(Backup.section(a), Ui.lp(Ui.MATCH, Ui.WRAP));

        String label = sunday ? "sunday · built from your week" : "this week so far";
        return Ui.page(a, Ui.header(a, label, "REVIEW", null), body);
    }

    private static View tile(MainActivity a, String value, String caption) {
        LinearLayout t = Ui.col(a);
        t.setBackground(Ui.fill(Ui.BLACK, 4));
        Ui.pad(t, 12, 10, 12, 10);
        t.addView(Ui.display(a, value, 36, Ui.CREAM));
        t.addView(Ui.text(a, caption, 12, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 2, 0, 0));
        return t;
    }

    private static View settingRow(MainActivity a, String title, String value, View.OnClickListener l) {
        LinearLayout row = Ui.card(a, Ui.BLACK, l);
        row.setMinimumHeight(Ui.dp(52));
        row.addView(Ui.text(a, title, 14, Ui.CREAM, Ui.medium), Ui.lp(0, Ui.WRAP, 1));
        row.addView(Ui.display(a, value, 24, Ui.OCHRE));
        row.setLayoutParams(Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
        return row;
    }

    private static View timeSetting(MainActivity a, String title, String key, String value) {
        int m = Store.minutes(value);
        return settingRow(a, title, value, v -> new TimePickerDialog(a, (p, h, min) -> {
            Store.setSetting(a, key, Store.hhmm(h * 60 + min));
            Reminders.scheduleAll(a);
            a.refresh();
        }, m / 60, m % 60, true).show());
    }

    private static View numberSetting(MainActivity a, String title, String key, int value, int min, int max) {
        return settingRow(a, title, String.valueOf(value), v -> Ui.pickNumber(a, title, min, max, value, n -> {
            Store.setSetting(a, key, n);
            a.refresh();
        }));
    }
}
