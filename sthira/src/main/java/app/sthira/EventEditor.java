package app.sthira;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.util.Locale;

/** The dialog that adds, changes or deletes a calendar event. */
final class EventEditor {

    private static final int[] REMINDERS = {-1, 0, 10, 30, 60};
    private static final String[] REMINDER_NAMES = {"No reminder", "At the start", "10 min before", "30 min before", "1 hour before"};

    private EventEditor() {
    }

    static void create(MainActivity a, LocalDate day) {
        if (!Cal.allowed(a)) {
            a.askCalendar();
            return;
        }
        show(a, null, day);
    }

    static void edit(MainActivity a, Cal.Ev e) {
        if (e.repeating || !e.first.equals(e.last)) {
            Toast.makeText(a, "Repeating and multi-day events are edited in Google Calendar for now.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        show(a, e, e.first);
    }

    private static String dateText(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + ", " + d.getDayOfMonth() + " "
                + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getYear();
    }

    private static void show(MainActivity a, Cal.Ev e, LocalDate date) {
        final LocalDate[] day = {date};
        final int[] start = new int[1], end = new int[1], rem = {2};
        final boolean[] allDay = {e != null && e.allDay};
        if (e != null && !e.allDay) {
            ZoneId z = ZoneId.systemDefault();
            LocalTime s = Instant.ofEpochMilli(e.begin).atZone(z).toLocalTime();
            LocalTime f = Instant.ofEpochMilli(e.end).atZone(z).toLocalTime();
            start[0] = s.getHour() * 60 + s.getMinute();
            end[0] = Math.max(start[0] + 5, f.getHour() * 60 + f.getMinute());
        } else {
            int now = LocalTime.now().getHour();
            start[0] = date.equals(LocalDate.now()) ? Math.min(22, now + 1) * 60 : 9 * 60;
            end[0] = start[0] + 60;
        }
        if (e != null) {
            int current = Cal.reminder(a, e.id);
            rem[0] = 0;
            for (int i = 0; i < REMINDERS.length; i++) if (REMINDERS[i] == current) rem[0] = i;
        }

        LinearLayout form = Ui.col(a);
        Ui.pad(form, 20, 12, 20, 0);
        EditText title = new EditText(a);
        title.setHint("Title");
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (e != null) title.setText(e.title);
        form.addView(title, Ui.lp(Ui.MATCH, Ui.WRAP));

        TextView dateBtn = Ui.ghost(a, dateText(day[0]), Ui.CREAM, false, null);
        dateBtn.setOnClickListener(v -> new DatePickerDialog(a, (p, y, m, d) -> {
            day[0] = LocalDate.of(y, m + 1, d);
            dateBtn.setText(dateText(day[0]));
        }, day[0].getYear(), day[0].getMonthValue() - 1, day[0].getDayOfMonth()).show());
        form.addView(dateBtn, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 10, 0, 0));

        LinearLayout times = Ui.row(a);
        TextView startBtn = Ui.ghost(a, "From " + Store.hhmm(start[0]), Ui.CREAM, false, null);
        TextView endBtn = Ui.ghost(a, "To " + Store.hhmm(end[0]), Ui.CREAM, false, null);
        startBtn.setOnClickListener(v -> new TimePickerDialog(a, (p, h, m) -> {
            int length = Math.max(15, end[0] - start[0]);
            start[0] = h * 60 + m;
            end[0] = Math.min(1439, start[0] + length);
            startBtn.setText("From " + Store.hhmm(start[0]));
            endBtn.setText("To " + Store.hhmm(end[0]));
        }, start[0] / 60, start[0] % 60, true).show());
        endBtn.setOnClickListener(v -> new TimePickerDialog(a, (p, h, m) -> {
            end[0] = h * 60 + m;
            endBtn.setText("To " + Store.hhmm(end[0]));
        }, end[0] / 60, end[0] % 60, true).show());
        times.addView(startBtn, Ui.gap(Ui.lp(0, Ui.WRAP, 1), 0, 0, 4, 0));
        times.addView(endBtn, Ui.gap(Ui.lp(0, Ui.WRAP, 1), 4, 0, 0, 0));
        times.setVisibility(allDay[0] ? LinearLayout.GONE : LinearLayout.VISIBLE);
        form.addView(times, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));

        CheckBox all = new CheckBox(a);
        all.setText("All day");
        all.setChecked(allDay[0]);
        all.setOnCheckedChangeListener((b, on) -> {
            allDay[0] = on;
            times.setVisibility(on ? LinearLayout.GONE : LinearLayout.VISIBLE);
        });
        form.addView(all, Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 6, 0, 0));

        TextView remBtn = Ui.ghost(a, REMINDER_NAMES[rem[0]], Ui.CREAM, false, null);
        remBtn.setOnClickListener(v -> {
            rem[0] = (rem[0] + 1) % REMINDERS.length;
            remBtn.setText(REMINDER_NAMES[rem[0]]);
        });
        form.addView(remBtn, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(e == null ? "New event" : "Edit event")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dlg, w) -> {
                    String name = title.getText().toString().trim();
                    if (name.isEmpty()) name = "(no title)";
                    long begin, finish;
                    if (allDay[0]) {
                        begin = day[0].atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                        finish = day[0].plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                    } else {
                        ZoneId z = ZoneId.systemDefault();
                        int to = end[0] > start[0] ? end[0] : Math.min(1439, start[0] + 60);
                        begin = day[0].atTime(start[0] / 60, start[0] % 60).atZone(z).toInstant().toEpochMilli();
                        finish = day[0].atTime(to / 60, to % 60).atZone(z).toInstant().toEpochMilli();
                    }
                    boolean ok = e == null
                            ? Cal.insert(a, name, begin, finish, allDay[0], REMINDERS[rem[0]])
                            : Cal.update(a, e.id, name, begin, finish, allDay[0], REMINDERS[rem[0]]);
                    if (!ok) {
                        Toast.makeText(a, "Couldn't save. Check that a calendar account is set up on this phone.",
                                Toast.LENGTH_LONG).show();
                    }
                    a.refresh();
                    AgendaWidget.updateAll(a);
                });
        if (e != null) {
            b.setNeutralButton("Delete", (dlg, w) -> Ui.confirm(a, "Delete this event?",
                    "\"" + e.title + "\" will be removed from your calendar. This can't be undone here.", "Delete", () -> {
                        if (!Cal.delete(a, e.id)) {
                            Toast.makeText(a, "Couldn't delete the event.", Toast.LENGTH_LONG).show();
                        }
                        a.refresh();
                        AgendaWidget.updateAll(a);
                    }));
        }
        b.show();
    }
}
