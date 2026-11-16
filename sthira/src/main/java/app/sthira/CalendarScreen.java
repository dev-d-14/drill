package app.sthira;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/** The month view: a grid on a black panel, the chosen day's events under it. */
final class CalendarScreen {

    private CalendarScreen() {
    }

    static View build(MainActivity a) {
        YearMonth month = a.calMonth;
        LocalDate today = LocalDate.now();

        LinearLayout arrows = Ui.row(a);
        arrows.addView(Ui.iconButton(a, Ui.I_LEFT, Ui.CREAM, true, "Previous month", v -> {
            a.calMonth = month.minusMonths(1);
            a.calDay = a.calMonth.atDay(1);
            a.refresh();
        }), Ui.lp(Ui.dp(46), Ui.dp(46)));
        arrows.addView(Ui.iconButton(a, Ui.I_RIGHT, Ui.CREAM, true, "Next month", v -> {
            a.calMonth = month.plusMonths(1);
            a.calDay = a.calMonth.atDay(1);
            a.refresh();
        }), Ui.gap(Ui.lp(Ui.dp(46), Ui.dp(46)), 6, 0, 0, 0));
        View header = Ui.header(a, month.getYear() + " · synced with your phone",
                month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toUpperCase(Locale.ROOT), arrows);

        LinearLayout body = Ui.col(a);
        if (!Cal.allowed(a)) {
            body.addView(Ui.button(a, "Connect your calendar", Ui.CREAM, Ui.BLACK, v -> a.askCalendar()),
                    Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 10));
        }

        // Which days of this month have events.
        boolean[] busy = new boolean[32];
        for (Cal.Ev e : Cal.between(a, month.atDay(1), month.atEndOfMonth())) {
            for (LocalDate d = e.first; !d.isAfter(e.last); d = d.plusDays(1)) {
                if (YearMonth.from(d).equals(month)) busy[d.getDayOfMonth()] = true;
            }
        }

        LinearLayout panel = Ui.col(a);
        panel.setBackground(Ui.fill(Ui.BLACK, 4));
        Ui.pad(panel, 8, 10, 8, 10);
        LinearLayout names = Ui.row(a);
        for (String n : new String[]{"M", "T", "W", "T", "F", "S", "S"}) {
            TextView t = Ui.text(a, n, 12, Ui.OCHRE, Ui.bold);
            t.setGravity(Gravity.CENTER);
            names.addView(t, Ui.lp(0, Ui.WRAP, 1));
        }
        panel.addView(names, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 6));

        int offset = month.atDay(1).getDayOfWeek().getValue() - 1;
        int days = month.lengthOfMonth();
        int cellH = Ui.dp(44);
        for (int rowStart = -offset + 1; rowStart <= days; rowStart += 7) {
            LinearLayout row = Ui.row(a);
            for (int i = 0; i < 7; i++) {
                int n = rowStart + i;
                if (n < 1 || n > days) {
                    row.addView(new View(a), Ui.gap(Ui.lp(0, cellH, 1), 2, 2, 2, 2));
                    continue;
                }
                LocalDate d = month.atDay(n);
                boolean isToday = d.equals(today), chosen = d.equals(a.calDay);
                TextView cell = Ui.text(a, String.valueOf(n), 16, isToday ? Ui.BLACK : Ui.CREAM, isToday ? Ui.bold : Ui.medium);
                cell.setGravity(Gravity.CENTER);
                Drawable base = Ui.fill(isToday ? Ui.OCHRE : Ui.CELL, 4);
                int bar = Store.allDone(a, d) ? Ui.JADE : busy[n] ? Ui.CREAM : 0;
                Drawable[] layers = bar == 0 ? new Drawable[]{base}
                        : new Drawable[]{base, Ui.fill(isToday ? Ui.BLACK : bar, 2)};
                LayerDrawable bg = new LayerDrawable(layers);
                if (bar != 0) bg.setLayerInset(1, Ui.dp(9), cellH - Ui.dp(7), Ui.dp(9), Ui.dp(3));
                if (chosen && !isToday) {
                    cell.setBackground(Ui.press(new LayerDrawable(new Drawable[]{bg, Ui.stroke(Ui.CREAM, 4, false)}), 4));
                } else {
                    cell.setBackground(Ui.press(bg, 4));
                }
                String what = Store.allDone(a, d) ? ", every habit done" : busy[n] ? ", has events" : "";
                cell.setContentDescription(n + what);
                cell.setOnClickListener(v -> {
                    a.calDay = d;
                    a.refresh();
                });
                row.addView(cell, Ui.gap(Ui.lp(0, cellH, 1), 2, 2, 2, 2));
            }
            panel.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        panel.addView(Ui.text(a, "White bar: events that day. Green bar: every habit done.", 12, Ui.MUTED, Ui.regular),
                Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 2, 8, 0, 0));
        body.addView(panel, Ui.lp(Ui.MATCH, Ui.WRAP));

        // The chosen day.
        LocalDate d = a.calDay;
        String dayName = d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getDayOfMonth();
        body.addView(Ui.label(a, d.equals(today) ? dayName + " · today" : dayName, Ui.CREAM),
                Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 14, 0, 0));
        List<Cal.Ev> events = Cal.on(a, d);
        for (Cal.Ev e : events) {
            LinearLayout card = Ui.card(a, Ui.CREAM, v -> EventEditor.edit(a, e));
            card.setMinimumHeight(Ui.dp(52));
            LinearLayout texts = Ui.col(a);
            texts.addView(Ui.text(a, e.title, 15, Ui.BLACK, Ui.bold));
            texts.addView(Ui.text(a, e.timeText(), 13, 0xFF4A3520, Ui.regular), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 3, 0, 0));
            card.addView(texts, Ui.lp(0, Ui.WRAP, 1));
            body.addView(card, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        }
        if (!d.isBefore(today) && Store.isTrainingDay(a, d)) {
            JSONObject plan = Store.activePlan(a);
            LinearLayout card = Ui.card(a, Ui.BLACK, null);
            card.setMinimumHeight(Ui.dp(52));
            card.addView(Ui.text(a, "Training day · " + (plan == null ? "" : plan.optString("name")), 15, Ui.CREAM, Ui.bold));
            body.addView(card, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        } else if (events.isEmpty()) {
            body.addView(Ui.text(a, "No events.", 14, Ui.CREAM, Ui.regular), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 8, 0, 0));
        }

        body.addView(new View(a), Ui.lp(Ui.MATCH, 0, 1));
        LinearLayout actions = Ui.row(a);
        actions.addView(Ui.button(a, "+ New event", Ui.OCHRE, Ui.BLACK, v -> EventEditor.create(a, d)),
                Ui.gap(Ui.lp(0, Ui.WRAP, 1), 0, 0, 4, 0));
        actions.addView(Ui.ghost(a, "Open day", Ui.CREAM, false, v -> {
            a.day = d;
            a.open(MainActivity.TODAY);
        }), Ui.gap(Ui.lp(0, Ui.WRAP, 1), 4, 0, 0, 0));
        body.addView(actions, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 14, 0, 0));
        return Ui.page(a, header, body);
    }
}
