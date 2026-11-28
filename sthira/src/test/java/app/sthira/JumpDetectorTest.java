package app.sthira;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/** Synthetic accelerometer and microphone traces at phone-like sample rates. Runs on GitHub Actions. */
public class JumpDetectorTest {

    private static final long STEP = 5_000_000L;   // 200 Hz, the rate every Android phone gives without extra permission

    /** Feeds g-levels for a duration and returns the time after it. */
    private static long hold(JumpDetector d, long t, double g, double seconds) {
        long end = t + Math.round(seconds * 1e9);
        for (; t < end; t += STEP) d.accel(t, 0, 0, (float) (g * JumpDetector.G));
        return t;
    }

    /** Standing, push-off, flight, landing spike. */
    private static long jump(JumpDetector d, long t, double flight, double contact) {
        t = hold(d, t, 1.0, contact);
        t = hold(d, t, 1.9, 0.12);
        t = hold(d, t, 0.05, flight);
        t = hold(d, t, 3.5, 0.03);
        return t;
    }

    @Test
    public void countermovementJumpHeight() {
        JumpDetector d = new JumpDetector();
        long t = hold(d, 1_000_000_000L, 1.0, 1.0);
        t = jump(d, t, 0.55, 0.3);
        hold(d, t, 1.0, 1.0);
        List<JumpDetector.Jump> j = d.jumps();
        assertEquals(1, j.size());
        // 0.55 s flight is about 37 cm; sampling at 200 Hz keeps it within a couple of cm.
        assertEquals(37.1, j.get(0).heightCm(), 2.5);
        assertTrue(d.usingMotion());
        assertEquals(1, d.contacts());
    }

    @Test
    public void pogosGiveContactTimeAndRsi() {
        JumpDetector d = new JumpDetector();
        long t = hold(d, 1_000_000_000L, 1.0, 1.0);
        for (int i = 0; i < 10; i++) {
            t = hold(d, t, 2.5, 0.18);         // short stiff contact
            t = hold(d, t, 0.05, 0.30);        // flight
            t = hold(d, t, 3.5, 0.02);         // impact
        }
        hold(d, t, 1.0, 1.0);
        List<JumpDetector.Jump> j = d.jumps();
        assertEquals(10, j.size());
        assertEquals(-1, j.get(0).contactMs);
        for (int i = 1; i < j.size(); i++) {
            assertEquals(200, j.get(i).contactMs, 15);   // 180 ms loaded + the impact samples
            assertTrue(j.get(i).rsi() > 0.4 && j.get(i).rsi() < 0.7);
        }
    }

    @Test
    public void ignoresShortDipsAndDrops() {
        JumpDetector d = new JumpDetector();
        long t = hold(d, 1_000_000_000L, 1.0, 1.0);
        t = hold(d, t, 0.4, 0.02);              // a wobble, not a flight
        t = hold(d, t, 1.0, 0.5);
        t = hold(d, t, 0.02, 1.4);              // phone dropped on a bed
        t = hold(d, t, 2.0, 0.05);
        hold(d, t, 1.0, 0.5);
        assertEquals(0, d.jumps().size());
        assertFalse(d.usingMotion());
    }

    @Test
    public void microphoneCountsThudsWhenPhoneIsStill() {
        JumpDetector d = new JumpDetector();
        long t = 1_000_000_000L, frame = 10_000_000L;   // 10 ms frames
        for (int i = 0; i < 100; i++, t += frame) d.audio(t, 150 + (i % 7) * 10);   // quiet room
        for (int hop = 0; hop < 8; hop++) {
            for (int i = 0; i < 3; i++, t += frame) d.audio(t, 6000);          // landing
            for (int i = 0; i < 42; i++, t += frame) d.audio(t, 160);          // between landings
        }
        // Phone lying still: motion sees nothing.
        for (long s = 1_000_000_000L; s < t; s += STEP) d.accel(s, 0, 0, (float) JumpDetector.G);
        assertEquals(8, d.thudCount());
        assertEquals(8, d.contacts());
        assertFalse(d.usingMotion());
        assertEquals(450, d.thudRhythmMs(), 5);
    }

    /** 2 ms frames, like the floor mode feeds: a thud with a ringing tail, sharp onset, varied rep times and a rest. */
    @Test
    public void floorModeTimesEachLanding() {
        JumpDetector d = new JumpDetector();
        long t = 1_000_000_000L, frame = 2_000_000L;
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 500; i++, t += frame) d.audio(t, 120 + rnd.nextInt(80));   // 1 s of quiet room
        long[] reps = {420, 390, 405, 380, 2300 + 4000, 410, 400};   // one 6.3 s rest in the middle
        long[] onsets = new long[reps.length + 1];
        for (int k = 0; k <= reps.length; k++) {
            onsets[k] = t;
            // thud: loud for 8 ms, then a tail decaying over ~80 ms that wobbles around the threshold
            for (int i = 0; i < 4; i++, t += frame) d.audio(t, 9000);
            for (int i = 0; i < 40; i++, t += frame) d.audio(t, 9000 * Math.exp(-i / 12.0) * (i % 3 == 0 ? 0.4 : 1));
            if (k == reps.length) break;
            long end = onsets[k] + reps[k] * 1_000_000L;
            for (; t < end; t += frame) d.audio(t, 120 + rnd.nextInt(80));
        }
        for (int i = 0; i < 500; i++, t += frame) d.audio(t, 120 + rnd.nextInt(80));

        assertEquals(reps.length + 1, d.thudCount());
        long[] gaps = d.thudGapsMs();
        for (int i = 0; i < reps.length; i++) assertEquals(reps[i], gaps[i], 3);
        assertTrue(gaps[4] > JumpDetector.MAX_REP_MS);   // the rest is not a rep
        assertEquals((onsets[reps.length] - onsets[0]) / 1_000_000L, d.thudSpanMs(), 3);
    }

    /** Talking-level noise well under a landing does not count. */
    @Test
    public void floorModeIgnoresQuietNoise() {
        JumpDetector d = new JumpDetector();
        long t = 1_000_000_000L, frame = 2_000_000L;
        for (int i = 0; i < 500; i++, t += frame) d.audio(t, 150);
        for (int i = 0; i < 1000; i++, t += frame) d.audio(t, 150 + 400 * Math.abs(Math.sin(i / 9.0)));
        assertEquals(0, d.thudCount());
    }
}
