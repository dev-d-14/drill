package app.sthira;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns phone sensor readings into jumps. Plain Java so it can be unit tested.
 *
 * Motion (phone in a tight pocket or the waistband): while you are in the air the phone falls with you, so the
 * accelerometer reads close to zero g. Takeoff is where the reading drops through {@link #LOW_G}, landing is the
 * impact spike back up through {@link #HIGH_G}. Flight time gives jump height (h = g t² / 8), and the time from one
 * landing to the next takeoff is the ground contact time. Height over contact time is the reactive strength index.
 *
 * Microphone (phone on the floor or a bench nearby): every landing makes a thud well above the room's noise floor,
 * so the microphone counts foot contacts and the rhythm between them. It cannot see flight time on its own, so it
 * is the fallback when the motion sensor saw no jumps.
 */
final class JumpDetector {

    static final double LOW_G = 0.55, HIGH_G = 1.3;
    static final double MIN_FLIGHT_S = 0.08, MAX_FLIGHT_S = 1.0, MIN_LOW_S = 0.05, MAX_CONTACT_S = 1.5;
    static final double G = 9.80665;

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
    private final List<Long> thuds = new ArrayList<>();

    // motion state
    private long lastNs = -1;
    private double lastG = 1;
    private boolean low;           // reading is under LOW_G
    private long lowStartNs;       // interpolated crossing down
    private long lastLandNs = -1;

    // microphone state
    private double floor = -1;
    private boolean loud;
    private long lastThudNs = -1;
    static final double THUD_FACTOR = 6, THUD_MIN_RMS = 700, THUD_GAP_S = 0.12;

    /** One accelerometer reading, in m/s² including gravity, with its timestamp in nanoseconds. */
    synchronized void accel(long tNs, float x, float y, float z) {
        double g = Math.sqrt((double) x * x + (double) y * y + (double) z * z) / G;
        if (lastNs < 0) {
            lastNs = tNs;
            lastG = g;
            return;
        }
        if (tNs <= lastNs) return;
        if (!low && lastG >= LOW_G && g < LOW_G) {
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

    /** One short microphone frame's RMS level (16-bit samples), with the time the frame ended. */
    synchronized void audio(long tNs, double rms) {
        if (floor < 0) {
            floor = rms;
            return;
        }
        double threshold = Math.max(floor * THUD_FACTOR, THUD_MIN_RMS);
        if (!loud && rms > threshold) {
            loud = true;
            if (lastThudNs < 0 || (tNs - lastThudNs) / 1e9 >= THUD_GAP_S) {
                thuds.add(tNs);
                lastThudNs = tNs;
            }
        } else if (loud && rms < threshold * 0.5) {
            loud = false;
        }
        // The noise floor follows the room slowly and is not pulled up by the thuds themselves.
        if (!loud) floor = floor * 0.98 + Math.min(rms, floor * 3) * 0.02;
    }

    synchronized List<Jump> jumps() {
        return new ArrayList<>(jumps);
    }

    synchronized int thudCount() {
        return thuds.size();
    }

    /** True when the motion sensor is carrying the set; otherwise only the microphone's count is usable. */
    synchronized boolean usingMotion() {
        return !jumps.isEmpty();
    }

    /** Foot contacts so far: landings seen by motion, or thuds heard by the microphone. */
    synchronized int contacts() {
        return jumps.isEmpty() ? thuds.size() : jumps.size();
    }

    /** When the last contact happened, in sensor nanoseconds, or -1. */
    synchronized long lastContactNs() {
        long m = jumps.isEmpty() ? -1 : jumps.get(jumps.size() - 1).landNs;
        long t = thuds.isEmpty() ? -1 : thuds.get(thuds.size() - 1);
        return Math.max(m, t);
    }

    /** Average time between thuds in ms (rhythm of a repeated jump), or 0. */
    synchronized long thudRhythmMs() {
        if (thuds.size() < 2) return 0;
        long sum = 0;
        int n = 0;
        for (int i = 1; i < thuds.size(); i++) {
            long gap = (thuds.get(i) - thuds.get(i - 1)) / 1_000_000L;
            if (gap <= MAX_CONTACT_S * 1000 + MAX_FLIGHT_S * 1000) {
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
