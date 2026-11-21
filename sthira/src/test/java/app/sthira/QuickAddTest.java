package app.sthira;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.Test;

/** Runs on GitHub Actions. Saturday 21 Nov 2026, 13:15. */
public class QuickAddTest {

    private static final LocalDate SAT = LocalDate.of(2026, 11, 21);
    private static final LocalTime NOW = LocalTime.of(13, 15);

    private static QuickAdd.Parsed p(String s) {
        return QuickAdd.parse(s, SAT, NOW);
    }

    @Test
    public void basics() {
        QuickAdd.Parsed g = p("gym tomorrow 7pm");
        assertEquals("Gym", g.title);
        assertEquals(SAT.plusDays(1), g.date);
        assertEquals(19 * 60, g.start);

        QuickAdd.Parsed c = p("call mom 21 nov 6:30pm for 30 min");
        assertEquals("Call mom", c.title);
        assertEquals(SAT, c.date);
        assertEquals(18 * 60 + 30, c.start);
        assertEquals(30, c.minutes);
    }

    @Test
    public void tomIsAName() {
        QuickAdd.Parsed d = p("dinner with tom friday");
        assertEquals("Dinner with tom", d.title);
        assertEquals(LocalDate.of(2026, 11, 27), d.date);
    }

    @Test
    public void ranges() {
        QuickAdd.Parsed l = p("lunch 1-2pm");
        assertEquals("Lunch", l.title);
        assertEquals(13 * 60, l.start);
        assertEquals(60, l.minutes);

        QuickAdd.Parsed b = p("brunch 11-1pm sun");
        assertEquals(11 * 60, b.start);
        assertEquals(120, b.minutes);
        assertEquals(LocalDate.of(2026, 11, 22), b.date);

        QuickAdd.Parsed t = p("standup 14:00-14:15 mon");
        assertEquals(14 * 60, t.start);
        assertEquals(15, t.minutes);

        QuickAdd.Parsed x = p("study 2pm-3");
        assertEquals(14 * 60, x.start);
        assertEquals(60, x.minutes);

        QuickAdd.Parsed n = p("party 10pm-1am");
        assertEquals(22 * 60, n.start);
        assertEquals(180, n.minutes);
    }

    @Test
    public void dateStillParsesWithDash() {
        QuickAdd.Parsed e = p("exam 10-12");
        assertEquals(LocalDate.of(2026, 12, 10), e.date);
        assertTrue(e.allDay());
    }

    @Test
    public void tonightWithTime() {
        QuickAdd.Parsed m = p("movie 8pm tonight");
        assertEquals("Movie", m.title);
        assertEquals(SAT, m.date);
        assertEquals(20 * 60, m.start);
    }

    @Test
    public void nextWeekday() {
        assertEquals(LocalDate.of(2026, 11, 27), p("review next fri").date);
        assertEquals(LocalDate.of(2026, 11, 23), p("call next mon").date);
        assertEquals(LocalDate.of(2026, 11, 27), p("review fri").date);
    }

    @Test
    public void satAndSunAsWords() {
        QuickAdd.Parsed s = p("SAT prep fri 6pm");
        assertEquals("SAT prep", s.title);
        assertEquals(LocalDate.of(2026, 11, 27), s.date);

        QuickAdd.Parsed g = p("gym sun 7am");
        assertEquals(LocalDate.of(2026, 11, 22), g.date);

        assertEquals(LocalDate.of(2026, 11, 22), p("hike on sun").date);
    }
}
