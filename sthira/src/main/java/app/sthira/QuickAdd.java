package app.sthira;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns "gym tomorrow 7pm", "DBMS exam fri 10am", "call mom 21 nov 6:30pm for 30 min" into an event.
 * Plain Java so it can be tested off the phone. Whatever isn't a date, time or duration is the title.
 */
final class QuickAdd {

    static final class Parsed {
        String title = "";
        LocalDate date;
        int start = -1;          // minutes after midnight, -1 for all day
        int minutes = 60;
        boolean dateGiven, timeGiven;

        boolean allDay() {
            return start < 0;
        }
    }

    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
    private static final String MONTH_RE = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";
    private static final String DAY_RE = "(mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)";

    private QuickAdd() {
    }

    static Parsed parse(String input, LocalDate today, LocalTime now) {
        Parsed p = new Parsed();
        String s = " " + input.trim().replaceAll("\\s+", " ") + " ";
        String low = s.toLowerCase(Locale.ROOT);

        // duration: "for 2h", "for 90 min", "for 1.5 hours", "2 hrs"
        Matcher m = Pattern.compile(" (?:for )?(\\d+(?:\\.\\d+)?) ?(h|hr|hrs|hour|hours|m|min|mins|minutes) ").matcher(low);
        if (m.find()) {
            double v = Double.parseDouble(m.group(1));
            boolean hours = m.group(2).startsWith("h");
            p.minutes = (int) Math.max(5, Math.min(24 * 60, Math.round(hours ? v * 60 : v)));
            s = cut(s, m.start(), m.end());
            low = s.toLowerCase(Locale.ROOT);
        }

        // range: "1-2pm", "10:30-11:45am", "6pm to 8pm", "14:00-15:30". Sets the start and the length.
        m = Pattern.compile(" (?:from |at )?(\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm)? ?(?:-|–|to) ?(\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm)? ")
                .matcher(low);
        while (m.find()) {
            String ap1 = m.group(3), ap2 = m.group(6);
            boolean clock24 = m.group(2) != null && m.group(5) != null;
            if (ap1 == null && ap2 == null && !clock24) continue;   // "1-2" alone is a date, not a time
            int h1 = Integer.parseInt(m.group(1)), m1 = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            int h2 = Integer.parseInt(m.group(4)), m2 = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
            if (m1 > 59 || m2 > 59) continue;
            int start, end;
            if (ap2 != null) {
                end = (h2 % 12) + ("pm".equals(ap2) ? 12 : 0);
                if (ap1 != null) start = (h1 % 12) + ("pm".equals(ap1) ? 12 : 0);
                else {
                    // "11-1pm" starts at 11am; "1-2pm" starts at 1pm
                    start = (h1 % 12) + ("pm".equals(ap2) ? 12 : 0);
                    if (start * 60 + m1 > end * 60 + m2) start -= 12;
                }
            } else if (ap1 != null) {
                // "2pm-3" ends at 3pm
                start = (h1 % 12) + ("pm".equals(ap1) ? 12 : 0);
                end = (h2 % 12) + ("pm".equals(ap1) ? 12 : 0);
                if (end * 60 + m2 <= start * 60 + m1 && end < 12) end += 12;
            } else {
                start = h1;
                end = h2;
            }
            if (start < 0 || start > 23 || end > 23) continue;
            int from = start * 60 + m1, to = end * 60 + m2;
            if (to <= from) to += 24 * 60;
            p.start = from;
            p.minutes = Math.max(5, Math.min(24 * 60, to - from));
            p.timeGiven = true;
            s = cut(s, m.start(), m.end());
            low = s.toLowerCase(Locale.ROOT);
            break;
        }

        // time: 7pm, 7 pm, 7:30pm, 19:00, noon, at 7
        if (!p.timeGiven) m = Pattern.compile(" (?:at |@ ?)?(\\d{1,2})(?:[:.](\\d{2}))? ?(am|pm) ").matcher(low);
        if (!p.timeGiven && m.find()) {
            int h = Integer.parseInt(m.group(1)) % 12, min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            if ("pm".equals(m.group(3))) h += 12;
            if (h < 24 && min < 60) {
                p.start = h * 60 + min;
                p.timeGiven = true;
                s = cut(s, m.start(), m.end());
                low = s.toLowerCase(Locale.ROOT);
            }
        }
        if (!p.timeGiven) {
            m = Pattern.compile(" (?:at |@ ?)?([01]?\\d|2[0-3])[:.]([0-5]\\d) ").matcher(low);
            if (m.find()) {
                p.start = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
                p.timeGiven = true;
                s = cut(s, m.start(), m.end());
                low = s.toLowerCase(Locale.ROOT);
            }
        }
        if (!p.timeGiven) {
            m = Pattern.compile(" (noon|midnight|morning|afternoon|evening|tonight) ").matcher(low);
            if (m.find()) {
                String w = m.group(1);
                p.start = "noon".equals(w) ? 720 : "midnight".equals(w) ? 0 : "morning".equals(w) ? 540
                        : "afternoon".equals(w) ? 900 : "evening".equals(w) ? 1080 : 1260;
                p.timeGiven = true;
                if ("tonight".equals(w)) {
                    p.date = today;
                    p.dateGiven = true;
                }
                s = cut(s, m.start(), m.end());
                low = s.toLowerCase(Locale.ROOT);
            }
        }
        if (!p.timeGiven) {
            // "at 7": 1–7 read as evening, 8–11 as morning, 12 as noon
            m = Pattern.compile(" (?:at|@) ?(\\d{1,2}) ").matcher(low);
            if (m.find()) {
                int h = Integer.parseInt(m.group(1));
                if (h >= 1 && h <= 7) h += 12;
                if (h < 24) {
                    p.start = h * 60;
                    p.timeGiven = true;
                    s = cut(s, m.start(), m.end());
                    low = s.toLowerCase(Locale.ROOT);
                }
            }
        }

        // A clock time was given, so "tonight" / "this evening" only says which day: today. Don't leave it in the title.
        if (p.timeGiven) {
            m = Pattern.compile(" (tonight|this (?:morning|afternoon|evening)) ").matcher(low);
            if (m.find()) {
                if (!p.dateGiven) {
                    p.date = today;
                    p.dateGiven = true;
                }
                s = cut(s, m.start(), m.end());
                low = s.toLowerCase(Locale.ROOT);
            }
        }

        // date: today / tomorrow / day after tomorrow
        if (!p.dateGiven) {
            m = Pattern.compile(" (day after tomorrow|today|tomorrow|tmrw|tmr) ").matcher(low);
            if (m.find()) {
                String w = m.group(1);
                p.date = w.startsWith("day") ? today.plusDays(2) : "today".equals(w) ? today : today.plusDays(1);
                p.dateGiven = true;
                s = cut(s, m.start(), m.end());
                low = s.toLowerCase(Locale.ROOT);
            }
        }
        // "21 nov", "21st november", "nov 21"
        if (!p.dateGiven) {
            m = Pattern.compile(" (?:on )?(\\d{1,2})(?:st|nd|rd|th)? (?:of )?" + MONTH_RE + "(?: (\\d{4}))? ").matcher(low);
            Matcher m2 = Pattern.compile(" (?:on )?" + MONTH_RE + " (\\d{1,2})(?:st|nd|rd|th)?(?: (\\d{4}))? ").matcher(low);
            Integer day = null, month = null, year = null;
            int a = -1, b = -1;
            if (m.find()) {
                day = Integer.parseInt(m.group(1));
                month = month(m.group(2));
                if (m.group(3) != null) year = Integer.parseInt(m.group(3));
                a = m.start();
                b = m.end();
            } else if (m2.find()) {
                month = month(m2.group(1));
                day = Integer.parseInt(m2.group(2));
                if (m2.group(3) != null) year = Integer.parseInt(m2.group(3));
                a = m2.start();
                b = m2.end();
            }
            if (day != null) {
                LocalDate d = date(today, day, month, year);
                if (d != null) {
                    p.date = d;
                    p.dateGiven = true;
                    s = cut(s, a, b);
                    low = s.toLowerCase(Locale.ROOT);
                }
            }
        }
        // "21/11", "21-11", "21/11/2026"
        if (!p.dateGiven) {
            m = Pattern.compile(" (?:on )?(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))? ").matcher(low);
            if (m.find()) {
                Integer year = m.group(3) == null ? null : Integer.parseInt(m.group(3));
                if (year != null && year < 100) year += 2000;
                LocalDate d = date(today, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), year);
                if (d != null) {
                    p.date = d;
                    p.dateGiven = true;
                    s = cut(s, m.start(), m.end());
                    low = s.toLowerCase(Locale.ROOT);
                }
            }
        }
        // weekday: "fri", "on friday", "next fri"
        if (!p.dateGiven) {
            m = Pattern.compile(" (on |next |this )?" + DAY_RE + " ").matcher(low);
            boolean found = false;
            while (m.find()) {
                // "sat" and "sun" are also words ("SAT prep", "watch the sun"): take them as days only after
                // on/next/this or as the last word, e.g. "gym sun".
                String word = m.group(2);
                boolean ambiguous = word.equals("sat") || word.equals("sun");
                if (ambiguous && m.group(1) == null && m.end() < low.length() - 1) continue;
                found = true;
                break;
            }
            if (found) {
                DayOfWeek dow = weekday(m.group(2));
                int ahead = (dow.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
                if (ahead == 0 && p.timeGiven && p.start <= now.getHour() * 60 + now.getMinute()) ahead = 7;
                if (m.group(1) != null && m.group(1).startsWith("next")) {
                    // "next fri" is that day in next week (weeks start Monday)
                    LocalDate nextMonday = today.plusDays(8 - today.getDayOfWeek().getValue());
                    ahead = (int) java.time.temporal.ChronoUnit.DAYS.between(today, nextMonday.plusDays(dow.getValue() - 1));
                }
                p.date = today.plusDays(ahead);
                p.dateGiven = true;
                s = cut(s, m.start(), m.end());
                low = s.toLowerCase(Locale.ROOT);
            }
        }
        if (!p.dateGiven) {
            p.date = today;
            // a time that has already gone today means tomorrow
            if (p.timeGiven && p.start <= now.getHour() * 60 + now.getMinute()) p.date = today.plusDays(1);
        }

        String title = s.replaceAll("\\s+", " ").trim();
        title = title.replaceAll("(?i)^(add|new|event|remind me to|remind me)\\s+", "");
        title = title.replaceAll("(?i)\\s+(at|on|from|by|for)$", "").trim();
        if (title.isEmpty()) title = "Event";
        p.title = Character.toUpperCase(title.charAt(0)) + title.substring(1);
        return p;
    }

    private static String cut(String s, int a, int b) {
        return s.substring(0, a) + " " + s.substring(b);
    }

    private static int month(String name) {
        String k = name.substring(0, 3);
        for (int i = 0; i < 12; i++) if (MONTHS[i].equals(k)) return i + 1;
        return 1;
    }

    private static DayOfWeek weekday(String name) {
        switch (name.substring(0, 2)) {
            case "mo":
                return DayOfWeek.MONDAY;
            case "tu":
                return DayOfWeek.TUESDAY;
            case "we":
                return DayOfWeek.WEDNESDAY;
            case "th":
                return DayOfWeek.THURSDAY;
            case "fr":
                return DayOfWeek.FRIDAY;
            case "sa":
                return DayOfWeek.SATURDAY;
            default:
                return DayOfWeek.SUNDAY;
        }
    }

    /** A day and month without a year means the next time that date comes round. */
    private static LocalDate date(LocalDate today, int day, Integer month, Integer year) {
        try {
            if (month == null || month < 1 || month > 12) return null;
            int y = year != null ? year : today.getYear();
            LocalDate d = LocalDate.of(y, Month.of(month), day);
            if (year == null && d.isBefore(today)) d = d.plusYears(1);
            return d;
        } catch (Exception e) {
            return null;
        }
    }
}
