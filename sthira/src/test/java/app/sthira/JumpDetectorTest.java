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

    /** Jumps in the pocket count; taking the phone out (proximity uncovered) throws away the fake "jump" just before. */
    @Test
    public void pocketIgnoresTakingThePhoneOut() {
        JumpDetector d = new JumpDetector();
        long t = 1_000_000_000L;
        d.proximity(t, true);                       // in the pocket
        t = hold(d, t, 1.0, 2.0);                   // settle
        t = jump(d, t, 0.5, 0.5);
        t = jump(d, t, 0.5, 0.8);
        t = hold(d, t, 1.0, 2.0);
        assertEquals(2, d.jumps().size());
        t = hold(d, t, 0.2, 0.2);                   // pulled out: brief low reading
        t = hold(d, t, 2.0, 0.05);                  // and a jolt, looks like a jump
        d.proximity(t, false);                      // out of the pocket
        t = jump(d, t, 0.4, 0.3);                   // waving it around in the hand
        t = jump(d, t, 0.3, 0.3);
        assertEquals(2, d.jumps().size());
        d.proximity(t, true);                       // back in
        t = hold(d, t, 1.0, 2.0);
        jump(d, t, 0.5, 0.5);
        assertEquals(3, d.jumps().size());
    }

    /** Without a proximity reading, a touch on the screen drops the handling just before it and pauses briefly. */
    @Test
    public void touchDropsHandling() {
        JumpDetector d = new JumpDetector();
        long t = hold(d, 1_000_000_000L, 1.0, 1.0);
        t = jump(d, t, 0.5, 0.5);
        t = hold(d, t, 1.0, 2.0);
        t = jump(d, t, 0.3, 0.2);                   // picking it up
        d.touched(t);
        t = jump(d, t, 0.3, 0.2);                   // fiddling right after the touch
        assertEquals(1, d.jumps().size());
    }

    /** In a pocket the fabric brushes the screen: touches while covered change nothing. */
    @Test
    public void touchesInPocketAreIgnored() {
        JumpDetector d = new JumpDetector();
        long t = 1_000_000_000L;
        d.proximity(t, true);
        t = hold(d, t, 1.0, 2.0);
        t = jump(d, t, 0.5, 0.5);
        d.touched(t);
        t = jump(d, t, 0.5, 0.3);
        assertEquals(2, d.jumps().size());
    }

    /** Landings across the room: only a few times the room's level, far below a tap on the phone, still count. */
    @Test
    public void floorHearsQuietLandings() {
        JumpDetector d = new JumpDetector();
        long t = 1_000_000_000L, frame = 2_000_000L;
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < 500; i++, t += frame) d.audio(t, 30 + rnd.nextInt(15));
        for (int k = 0; k < 10; k++) {
            for (int i = 0; i < 3; i++, t += frame) d.audio(t, 260);
            for (int i = 0; i < 20; i++, t += frame) d.audio(t, 260 * Math.exp(-i / 5.0) + 35);
            for (int i = 0; i < 177; i++, t += frame) d.audio(t, 30 + rnd.nextInt(15));   // 400 ms per rep
        }
        assertEquals(10, d.thudCount());
        for (long g : d.thudGapsMs()) assertEquals(400, g, 3);
    }

    /** Phone on a wooden floor, microphone quiet: the floor jolts carry the set; picking it up is not a landing. */
    @Test
    public void floorFeelsLandingsThroughTheFloor() {
        JumpDetector d = new JumpDetector();
        long t = 1_000_000_000L, step = 2_500_000L;   // 400 Hz
        java.util.Random rnd = new java.util.Random(5);
        for (int i = 0; i < 800; i++, t += step) d.floorAccel(t, 0, 0, (float) ((1 + (rnd.nextDouble() - 0.5) * 0.004) * JumpDetector.G));
        for (int k = 0; k < 8; k++) {
            for (int i = 0; i < 8; i++, t += step) d.floorAccel(t, 0, 0, (float) ((1 + 0.05 * Math.sin(i * 1.3) * Math.exp(-i / 3.0)) * JumpDetector.G));
            for (int i = 0; i < 152; i++, t += step) d.floorAccel(t, 0, 0, (float) ((1 + (rnd.nextDouble() - 0.5) * 0.004) * JumpDetector.G));
        }
        assertEquals(8, d.thudCount());
        assertTrue(d.floorByMotion());
        for (long g : d.thudGapsMs()) assertEquals(400, g, 5);
        // two seconds later it is picked up: a big swing, not a landing
        for (int i = 0; i < 800; i++, t += step) d.floorAccel(t, 0, 0, (float) ((1 + (rnd.nextDouble() - 0.5) * 0.004) * JumpDetector.G));
        for (int i = 0; i < 40; i++, t += step) d.floorAccel(t, 3f, 2f, (float) (1.8 * JumpDetector.G));
        assertEquals(8, d.thudCount());
    }
}
