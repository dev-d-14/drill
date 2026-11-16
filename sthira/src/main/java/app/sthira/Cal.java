package app.sthira;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Instances;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the phone's own calendar, so Google Calendar stays the store underneath
 * and events keep syncing the way they already do.
 */
final class Cal {

    static final class Ev {
        long id;
        String title = "";
        long begin, end;
        boolean allDay, repeating;
        LocalDate first, last; // local days this event touches, both inclusive

        String timeText() {
            if (allDay) return "All day";
            ZoneId z = ZoneId.systemDefault();
            LocalTime a = Instant.ofEpochMilli(begin).atZone(z).toLocalTime();
            LocalTime b = Instant.ofEpochMilli(end).atZone(z).toLocalTime();
            return Store.hhmm(a.getHour() * 60 + a.getMinute()) + " to " + Store.hhmm(b.getHour() * 60 + b.getMinute());
        }

        int startMinute() {
            if (allDay) return -1;
            LocalTime a = Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).toLocalTime();
            return a.getHour() * 60 + a.getMinute();
        }
    }

    private Cal() {
    }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /** Every event instance that touches a day in [from, to], sorted by start. */
    static List<Ev> between(Context c, LocalDate from, LocalDate to) {
        List<Ev> out = new ArrayList<>();
        if (!allowed(c)) return out;
        ZoneId z = ZoneId.systemDefault();
        long start = from.minusDays(1).atStartOfDay(z).toInstant().toEpochMilli();
        long end = to.plusDays(2).atStartOfDay(z).toInstant().toEpochMilli();
        Uri.Builder b = Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, start);
        ContentUris.appendId(b, end);
        String[] cols = {Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END,
                Instances.ALL_DAY, Instances.RRULE};
        try (Cursor cur = c.getContentResolver().query(b.build(), cols, Instances.VISIBLE + "=1", null,
                Instances.BEGIN + " ASC")) {
            while (cur != null && cur.moveToNext()) {
                Ev e = new Ev();
                e.id = cur.getLong(0);
                e.title = cur.getString(1) == null ? "(no title)" : cur.getString(1);
                e.begin = cur.getLong(2);
                e.end = cur.getLong(3);
                e.allDay = cur.getInt(4) != 0;
                String rule = cur.getString(5);
                e.repeating = rule != null && !rule.isEmpty();
                if (e.allDay) {
                    // All-day events are stored as UTC midnights with an exclusive end.
                    e.first = Instant.ofEpochMilli(e.begin).atZone(ZoneOffset.UTC).toLocalDate();
                    LocalDate endDay = Instant.ofEpochMilli(e.end).atZone(ZoneOffset.UTC).toLocalDate();
                    e.last = endDay.isAfter(e.first) ? endDay.minusDays(1) : e.first;
                } else {
                    e.first = Instant.ofEpochMilli(e.begin).atZone(z).toLocalDate();
                    e.last = Instant.ofEpochMilli(Math.max(e.begin, e.end - 1)).atZone(z).toLocalDate();
                }
                if (e.last.isBefore(from) || e.first.isAfter(to)) continue;
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static List<Ev> on(Context c, LocalDate day) {
        List<Ev> all = between(c, day, day);
        List<Ev> out = new ArrayList<>();
        for (Ev e : all) if (e.allDay) out.add(e);
        for (Ev e : all) if (!e.allDay) out.add(e);
        return out;
    }

    /** The calendar new events go into: the primary writable one, else any writable one, else -1. */
    static long defaultCalendar(Context c) {
        long fallback = -1;
        String[] cols = {Calendars._ID, Calendars.IS_PRIMARY, Calendars.CALENDAR_ACCESS_LEVEL};
        try (Cursor cur = c.getContentResolver().query(Calendars.CONTENT_URI, cols,
                Calendars.VISIBLE + "=1", null, null)) {
            while (cur != null && cur.moveToNext()) {
                if (cur.getInt(2) < Calendars.CAL_ACCESS_CONTRIBUTOR) continue;
                if (cur.getInt(1) == 1) return cur.getLong(0);
                if (fallback < 0) fallback = cur.getLong(0);
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static ContentValues values(String title, long begin, long end, boolean allDay) {
        ContentValues v = new ContentValues();
        v.put(Events.TITLE, title);
        v.put(Events.DTSTART, begin);
        v.put(Events.DTEND, end);
        v.put(Events.ALL_DAY, allDay ? 1 : 0);
        v.put(Events.EVENT_TIMEZONE, allDay ? "UTC" : ZoneId.systemDefault().getId());
        return v;
    }

    private static void setReminder(ContentResolver cr, long eventId, int minutes) {
        cr.delete(CalendarContract.Reminders.CONTENT_URI,
                CalendarContract.Reminders.EVENT_ID + "=?", new String[]{String.valueOf(eventId)});
        if (minutes < 0) return;
        ContentValues r = new ContentValues();
        r.put(CalendarContract.Reminders.EVENT_ID, eventId);
        r.put(CalendarContract.Reminders.MINUTES, minutes);
        r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
        cr.insert(CalendarContract.Reminders.CONTENT_URI, r);
    }

    /** @return true when the event was saved. */
    static boolean insert(Context c, String title, long begin, long end, boolean allDay, int reminderMinutes) {
        try {
            long calendar = defaultCalendar(c);
            if (calendar < 0) return false;
            ContentValues v = values(title, begin, end, allDay);
            v.put(Events.CALENDAR_ID, calendar);
            Uri u = c.getContentResolver().insert(Events.CONTENT_URI, v);
            if (u == null) return false;
            setReminder(c.getContentResolver(), ContentUris.parseId(u), reminderMinutes);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean update(Context c, long id, String title, long begin, long end, boolean allDay, int reminderMinutes) {
        try {
            Uri u = ContentUris.withAppendedId(Events.CONTENT_URI, id);
            int n = c.getContentResolver().update(u, values(title, begin, end, allDay), null, null);
            if (n > 0) setReminder(c.getContentResolver(), id, reminderMinutes);
            return n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean delete(Context c, long id) {
        try {
            return c.getContentResolver().delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** The first reminder on an event in minutes, or -1 when it has none. */
    static int reminder(Context c, long eventId) {
        try (Cursor cur = c.getContentResolver().query(CalendarContract.Reminders.CONTENT_URI,
                new String[]{CalendarContract.Reminders.MINUTES},
                CalendarContract.Reminders.EVENT_ID + "=?", new String[]{String.valueOf(eventId)}, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getInt(0);
        } catch (Exception ignored) {
        }
        return -1;
    }
}
