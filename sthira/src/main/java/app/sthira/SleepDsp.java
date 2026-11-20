package app.sthira;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The listening half of the sleep tracker, in plain Java so it can be tested off the phone.
 *
 * 16 kHz mono audio arrives in 50 ms frames. Each frame gets a loudness, three band energies and a
 * "flatness" (fabric rustle is broadband noise; voices, music and snores are tonal). A slowly moving
 * noise floor is kept per band, so a fan or AC just raises the floor. Broadband sound well above the
 * floor is movement. Every 30 seconds the frames are summed into an epoch.
 *
 * All thresholds live in the constants at the top.
 */
final class SleepDsp {

    // ---- tuning ----
    static final int RATE = 16000, FRAME = 800, FFT = 1024;      // 50 ms frames
    static final int FRAMES_PER_EPOCH = 600;                      // 30 s
    static final double ACTIVE_DB = 6, FLAT_MIN = 0.25;            // movement frame: 6 dB over floor and broadband
    static final int MERGE_GAP = 6, MIN_EVENT = 3;                 // join gaps up to 300 ms; events from 150 ms
    static final double MAJOR_SEC = 2.0, MAJOR_DB = 10, MICRO_SEC = 1.0, MICRO_DB = 12;
    static final double THUD_DB = 35, THUD_SEC = 0.3;              // door slams and knocks are not the sleeper
    static final double SNORE_DB = 8, SNORE_FLAT_MAX = 0.2;       // loud, tonal, low-mid band
    static final int FLOOR_WINDOW = 6000;                         // 5 minutes of frames
    static final double FLOOR_PCT = 0.50;                  // the median
    static final double BREATH_CONF = 0.5, BREATH_EVENT_SEC = 2.5, BREATH_EVENT_DB = 12;

    static final int MICRO = 0, MEDIUM = 1, MAJOR = 2;

    /** One finished 30-second block. */
    static final class Epoch {
        long startMs;
        double activeSec;
        int events, micro, medium, major;
        double maxPeak, meanAbove, snoreSec;
        double breathRate = -1, breathReg = -1, breathConf = 0;
        boolean noData, phone;
        double activity;     // A(t) from the research spec
        char online = 'L';

        String csv() {
            return String.format(java.util.Locale.ROOT, "%d,%.1f,%d,%d,%d,%d,%.1f,%.1f,%.1f,%.1f,%.2f,%.2f,%d,%d,%.2f,%c",
                    startMs, activeSec, events, micro, medium, major, maxPeak, meanAbove, snoreSec,
                    breathRate, breathReg, breathConf, noData ? 1 : 0, phone ? 1 : 0, activity, online);
        }

        static Epoch parse(String line) {
            try {
                String[] p = line.split(",");
                Epoch e = new Epoch();
                e.startMs = Long.parseLong(p[0]);
                e.activeSec = Double.parseDouble(p[1]);
                e.events = Integer.parseInt(p[2]);
                e.micro = Integer.parseInt(p[3]);
                e.medium = Integer.parseInt(p[4]);
                e.major = Integer.parseInt(p[5]);
                e.maxPeak = Double.parseDouble(p[6]);
                e.meanAbove = Double.parseDouble(p[7]);
                e.snoreSec = Double.parseDouble(p[8]);
                e.breathRate = Double.parseDouble(p[9]);
                e.breathReg = Double.parseDouble(p[10]);
                e.breathConf = Double.parseDouble(p[11]);
                e.noData = "1".equals(p[12]);
                e.phone = "1".equals(p[13]);
                e.activity = Double.parseDouble(p[14]);
                e.online = p[15].charAt(0);
                return e;
            } catch (Exception ex) {
                return null;
            }
        }

        static double activity(double activeSec, int major, int micro) {
            return activeSec + 2.0 * major + 0.3 * micro;
        }
    }

    /** A movement event, kept for a few minutes for the smart alarm. */
    static final class Event {
        final long endMs;
        final double seconds, peak;
        final int kind;

        Event(long endMs, double seconds, double peak, int kind) {
            this.endMs = endMs;
            this.seconds = seconds;
            this.peak = peak;
            this.kind = kind;
        }
    }

    interface EpochSink {
        void epoch(Epoch e);
    }

    // ---- sliding percentile over the last 5 minutes, 0.5 dB bins ----

    static final class Floor {
        private static final double LO = -140, STEP = 0.5;
        private static final int BINS = 400;
        private final int[] hist = new int[BINS];
        private final short[] ring = new short[FLOOR_WINDOW];
        private int count, head;
        private double cached = Double.NaN;
        private int sinceQuery;

        void add(double db) {
            int bin = (int) Math.round((db - LO) / STEP);
            bin = Math.max(0, Math.min(BINS - 1, bin));
            if (count == FLOOR_WINDOW) {
                hist[ring[head]]--;
            } else {
                count++;
            }
            ring[head] = (short) bin;
            hist[bin]++;
            head = (head + 1) % FLOOR_WINDOW;
            if (++sinceQuery >= 10) cached = Double.NaN;
        }

        double value() {
            if (count == 0) return Double.NaN;
            if (!Double.isNaN(cached)) return cached;
            int target = (int) Math.max(1, Math.round(count * FLOOR_PCT)), seen = 0;
            for (int i = 0; i < BINS; i++) {
                seen += hist[i];
                if (seen >= target) {
                    cached = LO + i * STEP;
                    sinceQuery = 0;
                    return cached;
                }
            }
            return LO + (BINS - 1) * STEP;
        }

        int size() {
            return count;
        }
    }

    // ---- state ----

    private final double[] window = new double[FFT], re = new double[FFT], im = new double[FFT];
    private final double[] hann = new double[FRAME];
    private final Floor floorRms = new Floor(), floorHi = new Floor(), floorMid = new Floor();
    private final EpochSink sink;

    private int frameInEpoch;
    private long epochStartMs;
    private Epoch cur;
    private double aboveSum;
    private int aboveN;

    // the open event
    private int evFrames, evGap = -1;
    private double evPeak, evSum;
    private int evN;

    // snore: onsets of loud tonal bursts
    private boolean inBurst;
    private int burstFrames;
    private final ArrayDeque<Long> burstOnsets = new ArrayDeque<>();
    private long snoringUntil;

    // breathing envelope, 20 Hz, last 60 s
    private final double[] env = new double[1200];
    private int envHead, envCount;
    private int majorHoldFrames;

    final ArrayDeque<Event> recent = new ArrayDeque<>();
    final List<Double> activities = new ArrayList<>();
    final List<Character> stages = new ArrayList<>();
    private int lowRun;
    volatile double level;           // last frame's dB over floor, for the night screen's dot
    private int zeroFrames, activeRun;
    private double breathConfNow;
    private boolean phoneFlag;

    SleepDsp(EpochSink sink) {
        this.sink = sink;
        for (int i = 0; i < FRAME; i++) hann[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FRAME - 1));
    }

    void markPhoneUse() {
        phoneFlag = true;
    }

    /** Feeds one frame of 16-bit samples captured at wall time nowMs. */
    void frame(short[] pcm, int n, long nowMs) {
        if (cur == null) startEpoch(nowMs);

        boolean silent = true;
        double mean = 0;
        for (int i = 0; i < n; i++) {
            mean += pcm[i];
            if (pcm[i] != 0) silent = false;
        }
        mean /= Math.max(1, n);
        zeroFrames = silent ? zeroFrames + 1 : 0;

        Arrays.fill(re, 0);
        Arrays.fill(im, 0);
        for (int i = 0; i < Math.min(n, FRAME); i++) re[i] = (pcm[i] - mean) / 32768.0 * hann[i];
        fft(re, im);

        double hz = RATE / (double) FFT;
        double total = 0, low = 0, mid = 0, hi = 0, breath = 0, logSum = 0, linSum = 0;
        int flatN = 0;
        for (int k = 1; k < FFT / 2; k++) {
            double f = k * hz, p = re[k] * re[k] + im[k] * im[k];
            if (f < 60) continue;
            if (f <= 7000) total += p;
            if (f < 250) low += p;
            else if (f < 2000) mid += p;
            else if (f < 6000) hi += p;
            if (f >= 100 && f <= 1500) breath += p;
            if (f >= 250 && f <= 6000) {
                logSum += Math.log(p + 1e-14);
                linSum += p;
                flatN++;
            }
        }
        double flat = flatN == 0 || linSum <= 0 ? 0 : Math.exp(logSum / flatN) / (linSum / flatN);
        double rmsDb = db(total), hiDb = db(hi), midDb = db(mid);

        double fr = floorRms.value(), fh = floorHi.value(), fm = floorMid.value();
        boolean warm = floorRms.size() >= 200;   // 10 s before anything counts
        double aRms = warm ? rmsDb - fr : 0, aHi = warm ? hiDb - fh : 0, aMid = warm ? midDb - fm : 0;
        double above = Math.max(aRms, aHi);
        level = above;

        boolean active = warm && zeroFrames == 0 && above >= ACTIVE_DB && flat >= FLAT_MIN;
        boolean tonalLoud = warm && aMid >= SNORE_DB && flat < SNORE_FLAT_MAX;
        // Twenty seconds of nonstop "movement" is a fan or rain that just started: learn it as background.
        activeRun = active ? activeRun + 1 : 0;
        boolean steady = activeRun > 400;
        if (steady) active = false;

        if ((!active && !tonalLoud && zeroFrames == 0) || steady) {
            floorRms.add(rmsDb);
            floorHi.add(hiDb);
            floorMid.add(midDb);
        }
        if (warm && !tonalLoud) {
            aboveSum += Math.max(0, above);
            aboveN++;
        }

        // movement events
        if (active) {
            if (evGap < 0) {
                evFrames = 0;
                evPeak = 0;
                evSum = 0;
                evN = 0;
            }
            evFrames += evGap > 0 ? evGap + 1 : 1;
            evGap = 0;
            evPeak = Math.max(evPeak, above);
            evSum += above;
            evN++;
        } else if (evGap >= 0) {
            evGap++;
            if (evGap > MERGE_GAP) closeEvent(nowMs);
        }

        // snore bursts
        if (tonalLoud) {
            if (!inBurst) {
                inBurst = true;
                burstFrames = 0;
                burstOnsets.addLast(nowMs);
                while (!burstOnsets.isEmpty() && nowMs - burstOnsets.peekFirst() > 30_000) burstOnsets.pollFirst();
                if (periodic()) snoringUntil = nowMs + 20_000;
            }
            burstFrames++;
        } else if (inBurst) {
            inBurst = false;
        }
        if (tonalLoud && nowMs < snoringUntil) cur.snoreSec += FRAME / (double) RATE;

        // breathing envelope (amplitude), with big movements blanked out
        if (majorHoldFrames > 0) majorHoldFrames--;
        env[envHead] = majorHoldFrames > 0 ? Double.NaN : Math.sqrt(breath);
        envHead = (envHead + 1) % env.length;
        envCount = Math.min(env.length, envCount + 1);

        if (zeroFrames > 0) cur.noData = zeroFrames >= 600 || cur.noData;

        if (++frameInEpoch >= FRAMES_PER_EPOCH) endEpoch(nowMs);
        while (!recent.isEmpty() && nowMs - recent.peekFirst().endMs > 10 * 60_000) recent.pollFirst();
    }

    private boolean periodic() {
        if (burstOnsets.size() < 3) return false;
        Long[] t = burstOnsets.toArray(new Long[0]);
        int good = 0;
        for (int i = 1; i < t.length; i++) {
            long d = t[i] - t[i - 1];
            if (d >= 2000 && d <= 7000) good++;
        }
        return good >= 2;
    }

    private void closeEvent(long nowMs) {
        int frames = evFrames;
        evGap = -1;
        if (frames < MIN_EVENT) return;
        double sec = frames * FRAME / (double) RATE;
        if (evPeak > THUD_DB && sec < THUD_SEC) return;          // a knock or slam
        // While steady breathing is clearly audible, short soft swells in rhythm are breaths, not movement.
        if (breathConfNow >= BREATH_CONF && sec < BREATH_EVENT_SEC && evPeak < BREATH_EVENT_DB) return;
        int kind = sec >= MAJOR_SEC && evPeak >= MAJOR_DB ? MAJOR
                : sec < MICRO_SEC && evPeak < MICRO_DB ? MICRO : MEDIUM;
        cur.events++;
        cur.activeSec += sec;
        if (kind == MAJOR) {
            cur.major++;
            majorHoldFrames = 40;
        } else if (kind == MICRO) cur.micro++;
        else cur.medium++;
        cur.maxPeak = Math.max(cur.maxPeak, evPeak);
        recent.addLast(new Event(nowMs, sec, evPeak, kind));
    }

    private void startEpoch(long nowMs) {
        cur = new Epoch();
        epochStartMs = nowMs;
        cur.startMs = nowMs;
        frameInEpoch = 0;
        aboveSum = 0;
        aboveN = 0;
    }

    private void endEpoch(long nowMs) {
        if (evGap >= 0 && evFrames > 0 && evGap > 0) closeEvent(nowMs);
        Epoch e = cur;
        e.meanAbove = aboveN == 0 ? 0 : aboveSum / aboveN;
        e.phone = phoneFlag;
        phoneFlag = false;
        breathing(e);
        breathConfNow = e.breathConf;
        e.activity = Epoch.activity(e.activeSec, e.major, e.micro);
        activities.add(e.activity);
        e.online = onlineStage(e);
        stages.add(e.online);
        startEpoch(nowMs);
        sink.epoch(e);
    }

    // ---- breathing: autocorrelation of the 100–1500 Hz envelope over the last minute ----

    private void breathing(Epoch e) {
        if (envCount < 600) return;
        int n = envCount;
        double[] x = new double[n];
        int valid = 0;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double v = env[(envHead - n + i + env.length * 2) % env.length];
            x[i] = v;
            if (!Double.isNaN(v)) {
                sum += v;
                valid++;
            }
        }
        if (valid < n * 0.6) return;
        double m = sum / valid;
        // light smoothing (0.25 s) after removing the mean
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            int c = 0;
            for (int k = Math.max(0, i - 2); k <= Math.min(n - 1, i + 2); k++) {
                if (!Double.isNaN(x[k])) {
                    s += x[k] - m;
                    c++;
                }
            }
            y[i] = c == 0 ? 0 : s / c;
        }
        double r0 = 0;
        for (double v : y) r0 += v * v;
        if (r0 <= 0) return;
        int bestLag = -1;
        double best = 0;
        for (int lag = 40; lag <= 150; lag++) {        // 2 to 7.5 seconds a breath
            double r = 0;
            for (int i = lag; i < n; i++) r += y[i] * y[i - lag];
            r /= r0 * (n - lag) / (double) n;
            if (r > best) {
                best = r;
                bestLag = lag;
            }
        }
        e.breathConf = best;
        if (bestLag < 0 || best < 0.3) return;
        e.breathRate = 60.0 / (bestLag * FRAME / (double) RATE);
        // regularity: spread of peak-to-peak intervals
        List<Integer> peaks = new ArrayList<>();
        int minSep = (int) (bestLag * 0.6);
        for (int i = 1; i < n - 1; i++) {
            if (y[i] > 0 && y[i] >= y[i - 1] && y[i] > y[i + 1]
                    && (peaks.isEmpty() || i - peaks.get(peaks.size() - 1) >= minSep)) peaks.add(i);
        }
        if (peaks.size() < 4) return;
        double mean = 0, var = 0;
        int k = peaks.size() - 1;
        for (int i = 1; i < peaks.size(); i++) mean += peaks.get(i) - peaks.get(i - 1);
        mean /= k;
        for (int i = 1; i < peaks.size(); i++) {
            double d = peaks.get(i) - peaks.get(i - 1) - mean;
            var += d * d;
        }
        double cv = Math.sqrt(var / k) / mean;
        e.breathReg = Math.max(0, Math.min(1, 1 - cv));
    }

    // ---- real-time stage, used only by the smart alarm ----

    /** P90 of the night so far, at least 1. */
    double scale() {
        int n = activities.size();
        if (n == 0) return 1;
        double[] s = new double[n];
        for (int i = 0; i < n; i++) s[i] = activities.get(i);
        Arrays.sort(s);
        return Math.max(1.0, s[(int) Math.floor((n - 1) * 0.9)]);
    }

    /** Smoothed activity with no look-ahead, on the same scale as the offline D. */
    double onlineD() {
        int n = activities.size();
        if (n == 0) return 0;
        double[] w = {1.0, 0.25, 0.20, 0.15, 0.15};
        double sc = scale(), d = 0, used = 0;
        for (int i = 0; i < w.length && n - 1 - i >= 0; i++) {
            d += w[i] * activities.get(n - 1 - i) / sc;
            used += w[i];
        }
        return d * 1.95 / used;
    }

    private char onlineStage(Epoch e) {
        double d = onlineD();
        if (e.phone || e.activeSec >= 10 || d >= 1.2) {
            lowRun = 0;
            return 'W';
        }
        lowRun = d < 0.15 ? lowRun + 1 : 0;
        return lowRun >= 6 && activities.size() > 30 ? 'D' : 'L';
    }

    long epochStart() {
        return epochStartMs;
    }

    // ---- helpers ----

    static double db(double p) {
        return 10 * Math.log10(p + 1e-14);
    }

    /** In-place radix-2 FFT. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}
