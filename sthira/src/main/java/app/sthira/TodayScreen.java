package app.sthira;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The first tab: one day, with calendar events and the day's habits in the order they happen. */
final class TodayScreen {

    private static final int PENDING = 0, DONE = 1, NEXT = 2, MISSED = 3;

    private static final class Item {
        final int minute;
        final View view;

        Item(int minute, View view) {
            this.minute = minute;
            this.view = view;
        }
    }

    private TodayScreen() {
    }

    static View build(MainActivity a) {
        LocalDate today = LocalDate.now();
        LocalDate d = a.day;
        boolean isToday = d.equals(today);
        String label = d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " · " + d.getDayOfMonth()
                + " " + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        String title = isToday ? "TODAY" : d.equals(today.plusDays(1)) ? "TOMORROW"
                : d.equals(today.minusDays(1)) ? "YESTERDAY"
                : d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT) + " " + d.getDayOfMonth();
        View calendar = Ui.iconButton(a, Ui.I_CAL, Ui.CREAM, true, "Open the month calendar", v -> {
            a.calDay = d;
            a.calMonth = YearMonth.from(d);
            a.open(MainActivity.CAL);
        });

        LinearLayout body = Ui.col(a);
        body.addView(weekStrip(a), Ui.lp(Ui.MATCH, Ui.WRAP));

        List<Item> items = new ArrayList<>();
        if (!Cal.allowed(a)) {
            LinearLayout connect = Ui.card(a, Ui.CREAM, v -> a.askCalendar());
            connect.setBackground(Ui.press(Ui.stroke(Ui.CREAM, 4, false), 4));
            LinearLayout texts = Ui.col(a);
            texts.addView(Ui.text(a, "Connect your calendar", 15, Ui.CREAM, Ui.bold));
            texts.addView(Ui.text(a, "Tap to show your events here", 13, Ui.MUTED, Ui.regular),
                    Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 3, 0, 0));
            connect.addView(texts, Ui.lp(0, Ui.WRAP, 1));
            items.add(new Item(-2, connect));
        }
        for (Cal.Ev e : Cal.on(a, d)) items.add(new Item(e.startMinute(), eventCard(a, e)));

        if (isToday) addHabits(a, items);
        // Everything lives in one file on the phone; after a few days of use, ask once a day until a backup exists.
        if (isToday && Backup.target(a) == null) {
            long used = 0;
            try {
                used = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(Store.root(a).optString("installed")), today);
            } catch (Exception ignored) {
            }
            if (used >= 3) {
                items.add(new Item(-1, habit(a, "BACK UP YOUR DATA", used + " days of logs live only on this phone. One tap, then weekly.",
                        PENDING, "SET UP", v -> Backup.choose(a))));
            }
        }
        else if (d.isBefore(today)) items.add(new Item(1500, pastSummary(a, d)));
        else if (Store.isTrainingDay(a, d)) {
            LinearLayout planned = Ui.card(a, Ui.BLACK, null);
            planned.addView(Ui.titled(a, "TRAINING DAY", "Planned in " + Store.activePlan(a).optString("name"),
                    Ui.CREAM, Ui.MUTED), Ui.lp(0, Ui.WRAP, 1));
            items.add(new Item(Store.minutes(Store.trainTime(a)), planned));
        }

        items.sort((x, y) -> Integer.compare(x.minute, y.minute));
        for (Item it : items) body.addView(it.view, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        if (items.isEmpty()) {
            body.addView(Ui.text(a, "Nothing on this day.", 14, Ui.CREAM, Ui.regular),
                    Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 16, 0, 0));
        }

        View spacer = new View(a);
        body.addView(spacer, Ui.lp(Ui.MATCH, 0, 1));
        body.addView(Ui.ghost(a, "+ New event", Ui.CREAM, true, v -> EventEditor.create(a, d)),
                Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 14, 0, 0));
        return Ui.page(a, Ui.header(a, label, title, calendar), body);
    }

    private static View weekStrip(MainActivity a) {
        LinearLayout strip = Ui.row(a);
        LocalDate monday = Store.monday(LocalDate.now());
        for (int i = 0; i < 7; i++) {
            LocalDate d = monday.plusDays(i);
            boolean on = d.equals(a.day);
            LinearLayout cell = Ui.col(a);
            cell.setGravity(Gravity.CENTER);
            cell.setMinimumHeight(Ui.dp(52));
            cell.setBackground(Ui.press(Ui.fill(on ? Ui.OCHRE : Ui.BLACK, 4), 4));
            int fg = on ? Ui.BLACK : Ui.CREAM;
            TextView name = Ui.text(a, d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    .toUpperCase(Locale.ROOT), 10, fg, Ui.bold);
            name.setLetterSpacing(0.1f);
            cell.addView(name, Ui.lp(Ui.WRAP, Ui.WRAP));
            cell.addView(Ui.text(a, String.valueOf(d.getDayOfMonth()), 17, fg, Ui.bebas),
                    Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 4, 0, 0));
            cell.setOnClickListener(v -> {
                a.day = d;
                a.refresh();
            });
            strip.addView(cell, Ui.gap(Ui.lp(0, Ui.WRAP, 1), i == 0 ? 0 : 3, 0, i == 6 ? 0 : 3, 6));
        }
        return strip;
    }

    private static View eventCard(MainActivity a, Cal.Ev e) {
        // Events are outlined in cream, so they read apart from the solid habit cards.
        LinearLayout card = Ui.card(a, Ui.CREAM, v -> EventEditor.edit(a, e));
        card.setBackground(Ui.press(Ui.stroke(Ui.CREAM, 4, false), 4));
        card.setMinimumHeight(Ui.dp(56));
        LinearLayout texts = Ui.col(a);
        texts.addView(Ui.text(a, e.title, 15, Ui.CREAM, Ui.bold));
        texts.addView(Ui.text(a, e.timeText(), 13, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 3, 0, 0));
        card.addView(texts, Ui.lp(0, Ui.WRAP, 1));
        card.addView(Ui.label(a, "cal", Ui.MUTED));
        return card;
    }

    private static void addHabits(MainActivity a, List<Item> items) {
        LocalDate today = LocalDate.now();
        JSONObject log = Store.dayOrEmpty(a, today);
        boolean hideDigest = Store.root(a).optBoolean("hideDigest", false);
        boolean digestDone = log.optBoolean("digest", false);
        boolean medDone = Store.meditated(a, today);
        String trained = Store.trained(a, today);
        boolean trainDue = Store.isTrainingDay(a, today) || Store.sessionDone(a);
        boolean trainDone = !trained.isEmpty();
        boolean shutDone = Store.shutDown(a, today);

        // The first habit not yet done, in the order of the day, is the one to highlight.
        int[] minutes = {Store.minutes(Store.digestTime(a)), Store.minutes(Store.homeTime(a)),
                Store.minutes(Store.trainTime(a)), Store.minutes(Store.shutTime(a))};
        boolean[] pending = {!hideDigest && !digestDone, !medDone, trainDue && !trainDone, !shutDone};

        // Never miss twice: a habit missed yesterday and not done yet today goes to the front.
        LocalDate y = today.minusDays(1);
        boolean counts = !y.isBefore(LocalDate.parse(Store.root(a).optString("installed", today.toString())));
        String yTrain = Store.trained(a, y);
        boolean[] missedY = {false,
                counts && !Store.meditated(a, y),
                counts && Store.isTrainingDay(a, y) && !"done".equals(yTrain) && !"rest".equals(yTrain),
                counts && !Store.shutDown(a, y)};
        boolean vitMissedY = counts && !Store.vitamin(a, y);
        final String again = "Missed yesterday. Don't miss twice.";
        int next = -1;
        for (int i = 0; i < 4; i++) {
            if (pending[i] && missedY[i] && (next < 0 || minutes[i] < minutes[next])) next = i;
        }
        if (next < 0) {
            for (int i = 0; i < 4; i++) {
                if (pending[i] && (next < 0 || minutes[i] < minutes[next])) next = i;
            }
        }

        boolean vit = Store.vitamin(a, today);
        String vitAt = log.optString("vitAt");
        String vitSub = vit ? (vitAt.isEmpty() ? "Taken today" : "Taken at " + vitAt) + " · tap to undo"
                : vitMissedY ? again : "Morning or evening. Tap when you take it.";
        items.add(new Item(0, habit(a, "MULTIVITAMIN", vitSub, vit ? DONE : PENDING, "TOOK IT", v -> {
            Store.setVitamin(a, !vit);
            a.refresh();
        })));
        if (!hideDigest) {
            items.add(new Item(minutes[0], habit(a, "DIGEST", digestDone ? "Read today" : "Today's list, a few minutes",
                    digestDone ? DONE : next == 0 ? NEXT : PENDING, "READ", v -> a.open(MainActivity.DIGEST))));
        }
        String medAt = log.optString("medAt");
        String medSub = medDone ? (medAt.isEmpty() ? "Done" : "Done at " + medAt) + " · " + log.optInt("med") + " min"
                : missedY[1] ? again : Store.medMinutes(a) + " minutes, when you get home";
        items.add(new Item(minutes[1], habit(a, "MEDITATE", medSub, medDone ? DONE : next == 1 ? NEXT : PENDING,
                "START", v -> a.open(MainActivity.MEDITATE))));
        if (trainDue) {
            JSONObject w = Store.nextWorkout(a);
            String name = Store.sessionDone(a) ? Store.session(a).optString("name") : w == null ? "" : w.optString("name");
            int count = w == null || w.optJSONArray("ex") == null ? 0 : w.optJSONArray("ex").length();
            String sub = "done".equals(trained) ? "Logged" : "rest".equals(trained) ? "Marked as a rest day"
                    : "missed".equals(trained) ? "Marked as missed" : missedY[2] ? again : count + " exercises, about 30 minutes";
            items.add(new Item(minutes[2], habit(a, "TRAIN · " + name.toUpperCase(Locale.ROOT), sub,
                    "missed".equals(trained) ? MISSED : trainDone ? DONE : next == 2 ? NEXT : PENDING, "OPEN",
                    v -> a.open(MainActivity.TRAIN))));
        }
        boolean lateEnough = LocalTime.now().toSecondOfDay() / 60 >= minutes[3] - 120;
        items.add(new Item(minutes[3], habit(a, "SHUTDOWN", shutDone ? "Done. Phone on the charger."
                        : missedY[3] ? again : "A few taps, then the charger",
                shutDone ? DONE : next == 3 ? NEXT : PENDING, lateEnough ? "OPEN" : "LATER",
                v -> a.open(MainActivity.SHUTDOWN))));

        // Sleep: last night's result in the morning, the alarm and Start in the evening.
        JSONObject night = Sleep.lastNight(a);
        if (night != null && today.toString().equals(night.optString("date")) && night.optLong("tib") >= 60) {
            String woke = night.has("firedAt") ? " · woke " + Store.hhmm(Sleep.minuteOf(night.optLong("firedAt"))) : "";
            items.add(new Item(1, habit(a, "SLEPT " + Sleep.hm(night.optLong("tst")).toUpperCase(Locale.ROOT),
                    "Score " + night.optInt("score") + woke + " · tap for the night", DONE, "",
                    v -> a.open(MainActivity.SLEEP))));
        }
        JSONObject now = Sleep.active(a);
        String sleepSub;
        if (now != null) {
            sleepSub = now.optLong("target") > 0 ? "Tracking · alarm " + Store.hhmm(Sleep.minuteOf(now.optLong("target")))
                    : "Tracking, no alarm";
        } else if (Sleep.MODE_TRACK.equals(Sleep.mode(a))) {
            sleepSub = "Track the night, no alarm";
        } else {
            // Say plainly whether an alarm is actually armed right now.
            sleepSub = Sleep.alarmArmed(a)
                    ? "Alarm set for " + Sleep.alarmTime(a) + " · Start to track and wake smart"
                    : "Alarm " + Sleep.alarmTime(a) + " · sets itself at " + Store.shutTime(a);
        }
        items.add(new Item(minutes[3] + 1, habit(a, now != null ? "SLEEPING" : "SLEEP", sleepSub, PENDING,
                now != null ? "OPEN" : lateEnough ? "START" : "SET", v -> a.open(MainActivity.SLEEP))));
    }

    private static View habit(MainActivity a, String title, String sub, int state, String action, View.OnClickListener l) {
        boolean next = state == NEXT;
        LinearLayout card = Ui.card(a, next ? Ui.OCHRE : Ui.BLACK, l);
        LinearLayout texts = Ui.col(a);
        if (next) {
            card.setMinimumHeight(Ui.dp(80));
            texts.addView(Ui.label(a, "up next", Ui.BLACK), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, 0, 3));
        }
        texts.addView(Ui.titled(a, title, sub, next ? Ui.BLACK : Ui.CREAM, next ? Ui.BLACK : Ui.MUTED));
        card.addView(texts, Ui.lp(0, Ui.WRAP, 1));
        if (state == DONE) {
            FrameLayout dot = new FrameLayout(a);
            dot.setBackground(Ui.fill(Ui.JADE, 16));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(Ui.dp(18), Ui.dp(18));
            p.gravity = Gravity.CENTER;
            dot.addView(new Ui.Icon(a, Ui.I_CHECK, Ui.BLACK), p);
            card.addView(dot, Ui.lp(Ui.dp(32), Ui.dp(32)));
        } else if (state == MISSED) {
            card.addView(Ui.label(a, "missed", Ui.MUTED));
        } else if (next) {
            card.addView(new Ui.Icon(a, Ui.I_RIGHT, Ui.BLACK), Ui.lp(Ui.dp(22), Ui.dp(22)));
        } else {
            card.addView(Ui.label(a, action, "LATER".equals(action) ? Ui.MUTED : Ui.OCHRE));
        }
        return card;
    }

    private static View pastSummary(MainActivity a, LocalDate d) {
        LinearLayout card = Ui.card(a, Ui.BLACK, v -> DayScreen.open(a, d, MainActivity.TODAY));
        LinearLayout texts = Ui.col(a);
        texts.addView(Ui.display(a, "WHAT YOU LOGGED", 24, Ui.CREAM));
        String trained = Store.trained(a, d);
        String[] lines = {
                Store.meditated(a, d) ? "Meditated, " + Store.dayOrEmpty(a, d).optInt("med") + " min" : "No meditation logged",
                trained.isEmpty() ? "No training logged" : "Training: " + trained,
                Store.journaled(a, d) ? "Journaled on paper" : "No journal logged",
                Store.shutDown(a, d) ? "Shutdown done" : "No shutdown logged",
                Store.vitamin(a, d) ? "Multivitamin taken" : "No multivitamin logged"};
        for (String s : lines) {
            TextView t = Ui.text(a, s, 13, Ui.MUTED, Ui.regular);
            texts.addView(t, Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 4, 0, 0));
        }
        card.addView(texts, Ui.lp(0, Ui.WRAP, 1));
        card.addView(Ui.label(a, "edit", Ui.OCHRE));
        return card;
    }
}
