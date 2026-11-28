package app.sthira;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns phone sensor readings into jumps. Plain Java so it can be unit tested.
 *
 * Pocket (phone in a tight pocket or the waistband): while you are in the air the phone falls with you, so the
 * accelerometer reads close to zero g. Takeoff is where the reading drops through {@link #LOW_G}, landing is the
 * impact spike back up through {@link #HIGH_G}. Flight time gives jump height (h = g t² / 8), and the time from one
 * landing to the next takeoff is the ground contact time. Height over contact time is the reactive strength index.
 * Handling the phone looks like a jump too, so motion only counts while the proximity sensor says the phone is
 * covered, and taking it out or touching the screen throws away the last moments and pauses detection briefly.
 *
 * Floor (phone lying on the floor nearby): every landing is heard by the microphone as a sudden sound well above
 * the room's level, and felt by the accelerometer as a small jolt through the floor. Each source keeps its own list
 * of landing times (their clocks differ by a few ms) and the set uses the one that caught more. The gap from one
 * landing to the next is the rep time (ground contact plus flight); take-off makes no sound, so the floor mode
 * cannot split that into flight and contact.
 */
final class JumpDetector {

    static final double LOW_G = 0.55, HIGH_G = 1.3;
    static final double MIN_FLIGHT_S = 0.08, MAX_FLIGHT_S = 1.0, MIN_LOW_S = 0.05, MAX_CONTACT_S = 1.5;
    static final double G = 9.80665;
    /** After the phone is handled (touched, taken out of the pocket), motion before and after this long is ignored. */
    static final double HANDLED_S = 1.5;

    /** One flight and the landing that ended it. */
    static final class Jump {
        final long takeoffNs, landNs;
        /** Ground time before this takeoff, or -1 when you were standing (first jump, or a step-off). */
        final long contactMs;

        Jump(long takeoffNs, long landNs, long contactMs) {
            this.takeoffNs = takeoffNs;
            this.landNs = landNs;
            this.contactMs = contactMs;
        }

        double flightS() {
            return (landNs - takeoffNs) / 1e9;
        }

        double heightCm() {
            double t = flightS();
            return G * t * t / 8.0 * 100.0;
        }

        /** Jump height in metres over contact time in seconds; 0 when there was no contact before it. */
        double rsi() {
            return contactMs > 0 ? heightCm() / 100.0 / (contactMs / 1000.0) : 0;
        }
    }

    private final List<Jump> jumps = new ArrayList<>();
    private final List<Long> thuds = new ArrayList<>();   // heard by the microphone
    private final List<Long> jolts = new ArrayList<>();   // felt by the accelerometer lying on the floor

    // pocket motion state
    private long lastNs = -1;
    private double lastG = 1;
    private boolean low;           // reading is under LOW_G
    private long lowStartNs;       // interpolated crossing down
    private long lastLandNs = -1;
    private long ignoreUntilNs = -1;
    private boolean seenNear;      // the proximity sensor has reported "covered" at least once
    private boolean near;

    // microphone state
    static final double MIC_FLOOR_K = 3.5, MIC_RISE_K = 3, MIC_MIN_RMS = 80, THUD_GAP_S = 0.12;
    static final double ENV_TAU_S = 0.03, FLOOR_TAU_S = 0.5, MAX_LOUD_S = 0.4, RISE_WINDOW_S = 0.03;
    private double floor = -1;
    private double env;            // peak-hold envelope, so a thud's ringing tail is one thud, not several
    private boolean loud;
    private long loudStartNs;
    private long lastAudioNs = -1;
    private long micIgnoreUntilNs = -1;
    private final double[] recent = new double[64];
    private final long[] recentNs = new long[64];
    private int recentN, recentAt;

    // floor accelerometer state
    static final double JOLT_K = 6, JOLT_MIN_G = 0.01, JOLT_MAX_G = 0.6;
    private double base = -1, noise = 0.003;
    private long lastFloorNs = -1, lastJoltNs = -1;

    /** Landing-to-landing gaps longer than this are rests or resets between reps, not one rep. */
    static final long MAX_REP_MS = 2500;

    // ---- pocket ----

    /** One accelerometer reading, in m/s² including gravity, with its timestamp in nanoseconds. */
    synchronized void accel(long tNs, float x, float y, float z) {
        double g = Math.sqrt((double) x * x + (double) y * y + (double) z * z) / G;
        if (lastNs < 0) {
            lastNs = tNs;
            lastG = g;
            return;
        }
        if (tNs <= lastNs) return;
        boolean blocked = tNs < ignoreUntilNs || (seenNear && !near);
        if (blocked) {
            low = false;
        } else if (!low && lastG >= LOW_G && g < LOW_G) {
            low = true;
            lowStartNs = cross(lastNs, lastG, tNs, g, LOW_G);
        } else if (low && g > HIGH_G) {
            low = false;
            long landNs = cross(lastNs, lastG, tNs, g, HIGH_G);
            double flight = (landNs - lowStartNs) / 1e9;
            if (flight >= MIN_FLIGHT_S && flight <= MAX_FLIGHT_S) {
                long contact = -1;
                if (lastLandNs > 0) {
                    long c = (lowStartNs - lastLandNs) / 1_000_000L;
                    if (c > 0 && c <= MAX_CONTACT_S * 1000) contact = c;
                }
                jumps.add(new Jump(lowStartNs, landNs, contact));
                lastLandNs = landNs;
            } else if (flight > MAX_FLIGHT_S) {
                lastLandNs = -1;   // the phone was dropped or thrown, not a jump
            }
        } else if (low && g >= LOW_G && (tNs - lowStartNs) / 1e9 < MIN_LOW_S) {
            low = false;           // a brief dip while landing or turning, not a flight
        }
        lastNs = tNs;
        lastG = g;
    }

    /** Proximity sensor: covered (in a pocket) or not. Only once it has said "covered" does it gate the motion. */
    synchronized void proximity(long tNs, boolean covered) {
        if (covered) {
            if (!near) ignoreUntilNs = Math.max(ignoreUntilNs, tNs + (long) (HANDLED_S * 1e9));   // settling in
            seenNear = true;
            near = true;
        } else {
            if (near) handled(tNs);   // coming out of the pocket
            near = false;
        }
    }

    /** The screen was touched. In a pocket the fabric touches it all the time, so that only counts when uncovered. */
    synchronized void touched(long tNs) {
        if (near) return;
        handled(tNs);
        // A tap on the phone is loud to its own microphone and shakes it: not a landing.
        micIgnoreUntilNs = Math.max(micIgnoreUntilNs, tNs + 300_000_000L);
        dropAfter(thuds, tNs - 300_000_000L);
        dropAfter(jolts, tNs - 300_000_000L);
    }

    /** The phone was picked up or fiddled with: drop the jumps of the last moments and pause for a moment. */
    private void handled(long tNs) {
        long from = tNs - (long) (HANDLED_S * 1e9);
        for (int i = jumps.size() - 1; i >= 0 && jumps.get(i).landNs >= from; i--) jumps.remove(i);
        ignoreUntilNs = Math.max(ignoreUntilNs, tNs + (long) (HANDLED_S * 1e9));
        lastLandNs = -1;
        low = false;
    }

    private static void dropAfter(List<Long> list, long fromNs) {
        for (int i = list.size() - 1; i >= 0 && list.get(i) >= fromNs; i--) list.remove(i);
    }

    // ---- floor ----

    /**
     * One short microphone frame's RMS level (16-bit samples) with its time. Frames of a few ms give each landing a
     * timestamp to within a few ms; the time should come from the count of samples read, not the wall clock.
     * A landing is a sudden sound: well over the room's level, and well over the last few tens of ms.
     */
    synchronized void audio(long tNs, double rms) {
        if (floor < 0) {
            floor = Math.max(rms, 1);
            lastAudioNs = tNs;
            remember(tNs, rms);
            return;
        }
        double dt = Math.max(0, (tNs - lastAudioNs) / 1e9);
        lastAudioNs = tNs;
        env = Math.max(rms, env * Math.exp(-dt / ENV_TAU_S));
        double threshold = Math.max(floor * MIC_FLOOR_K, MIC_MIN_RMS);
        if (!loud && rms > threshold && rms > MIC_RISE_K * recentMean(tNs)) {
            loud = true;
            loudStartNs = tNs;
            if (tNs >= micIgnoreUntilNs && (thuds.isEmpty() || (tNs - thuds.get(thuds.size() - 1)) / 1e9 >= THUD_GAP_S)) {
                thuds.add(tNs);
            }
        } else if (loud && (env < threshold * 0.5 || (tNs - loudStartNs) / 1e9 > MAX_LOUD_S)) {
            loud = false;
            if ((tNs - loudStartNs) / 1e9 > MAX_LOUD_S) floor = Math.max(floor, rms / 2);   // the room got louder
        }
        // The noise floor follows the room slowly (about half a second) and is not pulled up by the thuds themselves.
        if (!loud) {
            double a = 1 - Math.exp(-dt / FLOOR_TAU_S);
            floor = Math.max(1, floor + (Math.min(rms, floor * 3) - floor) * a);
        }
        remember(tNs, rms);
    }

    private void remember(long tNs, double rms) {
        recent[recentAt] = rms;
        recentNs[recentAt] = tNs;
        recentAt = (recentAt + 1) % recent.length;
        recentN = Math.min(recentN + 1, recent.length);
    }

    /** Mean level over the last {@link #RISE_WINDOW_S}, before this frame. */
    private double recentMean(long tNs) {
        double sum = 0;
        int n = 0;
        for (int k = 1; k <= recentN; k++) {
            int i = (recentAt - k + recent.length) % recent.length;
            if ((tNs - recentNs[i]) / 1e9 > RISE_WINDOW_S) break;
            sum += recent[i];
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    /**
     * One accelerometer reading while the phone lies on the floor. A landing nearby shakes the floor: a short jolt
     * away from the phone's resting reading, several times its usual jitter. A big jolt is the phone being handled.
     */
    synchronized void floorAccel(long tNs, float x, float y, float z) {
        double g = Math.sqrt((double) x * x + (double) y * y + (double) z * z) / G;
        if (base < 0) {
            base = g;
            lastFloorNs = tNs;
            return;
        }
        double dt = Math.max(0, (tNs - lastFloorNs) / 1e9);
        lastFloorNs = tNs;
        double dev = Math.abs(g - base);
        double trigger = Math.max(noise * JOLT_K, JOLT_MIN_G);
        if (dev > JOLT_MAX_G) {
            // picked up or knocked: not a landing, and the jolts just before were the hand reaching it
            dropAfter(jolts, tNs - 500_000_000L);
            dropAfter(thuds, tNs - 500_000_000L);
            micIgnoreUntilNs = Math.max(micIgnoreUntilNs, tNs + 1_000_000_000L);
            lastJoltNs = tNs + 1_000_000_000L;
        } else if (dev > trigger && tNs >= micIgnoreUntilNs
                && (lastJoltNs < 0 || (tNs - lastJoltNs) / 1e9 >= THUD_GAP_S)) {
            jolts.add(tNs);
            lastJoltNs = tNs;
        }
        // resting reading and jitter follow slowly; the jolts themselves barely move them
        double a = 1 - Math.exp(-dt / 1.0);
        base += (g - base) * a;
        if (dev < trigger) noise += (dev - noise) * a;
        noise = Math.max(noise, 0.0005);
    }

    /** The floor landings of this set: whichever of microphone and floor jolts caught more. */
    private List<Long> floorLandings() {
        return jolts.size() > thuds.size() ? jolts : thuds;
    }

    synchronized boolean floorByMotion() {
        return jolts.size() > thuds.size();
    }

    // ---- results ----

    synchronized List<Jump> jumps() {
        return new ArrayList<>(jumps);
    }

    synchronized int thudCount() {
        return floorLandings().size();
    }

    /** True when the pocket motion is carrying the set; otherwise the floor landings are used. */
    synchronized boolean usingMotion() {
        return !jumps.isEmpty();
    }

    /** Foot contacts so far: landings seen by pocket motion, or the floor landings. */
    synchronized int contacts() {
        return jumps.isEmpty() ? floorLandings().size() : jumps.size();
    }

    /** When the last contact happened, in sensor nanoseconds, or -1. */
    synchronized long lastContactNs() {
        long m = jumps.isEmpty() ? -1 : jumps.get(jumps.size() - 1).landNs;
        List<Long> f = floorLandings();
        long t = f.isEmpty() ? -1 : f.get(f.size() - 1);
        return Math.max(m, t);
    }

    /** Time from each floor landing to the next, in ms, oldest first. Includes rests; see {@link #MAX_REP_MS}. */
    synchronized long[] thudGapsMs() {
        List<Long> f = floorLandings();
        long[] g = new long[Math.max(0, f.size() - 1)];
        for (int i = 1; i < f.size(); i++) g[i - 1] = (f.get(i) - f.get(i - 1)) / 1_000_000L;
        return g;
    }

    /** First to last floor landing, in ms, or 0. */
    synchronized long thudSpanMs() {
        List<Long> f = floorLandings();
        return f.size() < 2 ? 0 : (f.get(f.size() - 1) - f.get(0)) / 1_000_000L;
    }

    /** Average time between floor landings in ms, rests left out, or 0. */
    synchronized long thudRhythmMs() {
        long sum = 0;
        int n = 0;
        for (long gap : thudGapsMs()) {
            if (gap <= MAX_REP_MS) {
                sum += gap;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Where a straight line between two readings crosses a level. */
    private static long cross(long t0, double g0, long t1, double g1, double level) {
        if (g1 == g0) return t1;
        double f = (level - g0) / (g1 - g0);
        f = Math.max(0, Math.min(1, f));
        return t0 + Math.round((t1 - t0) * f);
    }
}
