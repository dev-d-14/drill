package app.sthira;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns a night of 30-second epochs into W / L / D / R, in plain Java so it can be tested off the phone.
 *
 * Model 2 (Nov 2026). The first model called every quiet stretch "deep", so a calm sleeper got 85–90% deep sleep and
 * no REM at all. Silence doesn't tell light, deep and REM sleep apart: all three are mostly still. So this one works
 * like a sleep lab's prior plus the evidence the microphone really has:
 *
 *  1. Sleep vs wake, actigraphy style (what sound and motion are actually good at). Phone use is always wake, and
 *     so is the moment before it: you woke up to reach for it.
 *  2. The sleep period is split into NREM–REM cycles of about 90 minutes. Boundaries snap to body movements and
 *     brief wakings, which in real sleep cluster at the ends of cycles; with no movement they fall at ~90 min.
 *  3. Inside each cycle the order is fixed, as in a real hypnogram: light → deep → light → REM. How much deep and REM
 *     a cycle "should" have comes from how far into the night it is (deep early, REM late; young-adult norms of
 *     roughly 13–23% deep and 20–25% REM). The evidence then moves and stretches each bout: movement rules deep
 *     out, small twitches and uneven breathing point to REM, slow regular breathing points to deep. Breathing
 *     features are ranked against the same night, so a quiet room can't inflate any stage.
 *
 * Everything is still an estimate from sound, not EEG. The point is that it can't produce impossible nights.
 */
final class SleepStager {

    static final int MODEL = 2;

    private SleepStager() {
    }

    // ---- tuning ----
    static final double WAKE_D = 1.2, WAKE_ACTIVE_SEC = 10;     // smoothed activity / seconds of movement = awake
    static final int ONSET_RUN = 20;                            // 10 min of unbroken sleep marks sleep onset
    static final int CYCLE_FIRST = 170, CYCLE_LATER = 190, CYCLE_SD = 30;   // epochs: 85 / 95 min, sd 15 min
    static final int CYCLE_MIN = 110, CYCLE_MAX = 260;           // 55–130 min
    static final int SPLIT_WAKE = 20;                            // a 10-minute waking starts a fresh cycle
    static final double EVIDENCE = 0.5;                          // neighbouring epochs aren't independent

    /** Expected share of a cycle spent in deep sleep and in REM, by the cycle's start in hours of sleep. */
    private static final double[][] PROFILE = {
            // hours, deep, rem
            {0.0, 0.38, 0.12},
            {1.5, 0.28, 0.20},
            {3.0, 0.14, 0.26},
            {4.5, 0.05, 0.30},
            {6.0, 0.02, 0.33},
    };

    static double deepShare(double hours) {
        return interp(hours, 1);
    }

    static double remShare(double hours) {
        return interp(hours, 2);
    }

    private static double interp(double h, int col) {
        if (h <= PROFILE[0][0]) return PROFILE[0][col];
        for (int i = 1; i < PROFILE.length; i++) {
            if (h <= PROFILE[i][0]) {
                double t = (h - PROFILE[i - 1][0]) / (PROFILE[i][0] - PROFILE[i - 1][0]);
                return PROFILE[i - 1][col] + t * (PROFILE[i][col] - PROFILE[i - 1][col]);
            }
        }
        return PROFILE[PROFILE.length - 1][col];
    }

    /** What the analysis hands back. Indices are epochs from the start of the recording. */
    static final class Result {
        char[] stages;
        int onset = -1, wake = -1;          // first and last sleeping epoch
        int awakenings;
        double[] activity;                  // raw activity per epoch (0 for gaps)
        double[] smooth;                    // scaled, smoothed activity
        List<Integer> pickups = new ArrayList<>();
        List<Integer> cycleStarts = new ArrayList<>();
    }

    static Result stage(SleepDsp.Epoch[] ep) {
        int n = ep.length;
        Result r = new Result();
        double[] act = new double[n];
        for (int i = 0; i < n; i++) act[i] = ep[i].noData ? 0 : ep[i].activity;
        r.activity = act;
        double[] d = smoothActivity(ep, act);
        r.smooth = d;
        char[] st = new char[n];
        r.stages = st;

        // ---- 1. wake vs sleep ----
        for (int i = 0; i < n; i++) {
            SleepDsp.Epoch e = ep[i];
            st[i] = (e.phone || e.activeSec >= WAKE_ACTIVE_SEC || d[i] >= WAKE_D) ? 'W' : 'L';
        }
        // reaching for the phone means you were already awake a moment before
        for (int i = 1; i < n; i++) {
            if (ep[i].phone && !ep[i - 1].phone && !ep[i - 1].noData && act[i - 1] > 0) st[i - 1] = 'W';
        }
        int onset = -1, run = 0;
        for (int i = 0; i < n; i++) {
            boolean calm = st[i] != 'W' && d[i] < 0.6;
            run = calm ? run + 1 : 0;
            if (run >= ONSET_RUN) {
                onset = i - ONSET_RUN + 1;
                break;
            }
        }
        if (onset < 0) {
            Arrays.fill(st, 'W');
            return r;
        }
        int wake = onset;
        for (int i = n - 1; i >= onset; i--) {
            if (st[i] != 'W') {
                wake = i;
                break;
            }
        }
        for (int i = 0; i < onset; i++) st[i] = 'W';
        for (int i = wake + 1; i < n; i++) st[i] = 'W';
        rescoreWake(st, ep, onset, wake);
        r.onset = onset;
        r.wake = wake;

        // ---- 2. cycles, within blocks of sleep separated by long wakings ----
        double[] dD = new double[n], dR = new double[n];
        evidence(ep, act, d, st, onset, wake, dD, dR);

        int blockStart = onset;
        boolean firstBlock = true;
        int i = onset;
        while (i <= wake) {
            // find the next long waking
            int j = i;
            while (j <= wake) {
                if (st[j] == 'W') {
                    int k = j;
                    while (k <= wake && st[k] == 'W') k++;
                    if (k - j >= SPLIT_WAKE) break;
                    j = k;
                } else j++;
            }
            int blockEnd = j;               // exclusive; st[j] starts a long waking or j = wake + 1
            if (blockEnd > blockStart) {
                List<Integer> b = cycles(ep, act, st, blockStart, blockEnd, firstBlock);
                for (int c = 0; c < b.size(); c++) {
                    int cs = b.get(c), ce = c + 1 < b.size() ? b.get(c + 1) : blockEnd;
                    r.cycleStarts.add(cs);
                    boolean partial = c + 1 == b.size();
                    fitCycle(st, ep, dD, dR, cs, ce, (cs - onset) / 120.0, partial, onset);
                }
                firstBlock = false;
            }
            // skip the waking
            int k = blockEnd;
            while (k <= wake && st[k] == 'W') k++;
            blockStart = k;
            i = k;
        }
        // gaps in the recording carry no evidence; they stay light rather than claim a stage
        for (int k = onset; k <= wake; k++) if (ep[k].noData && st[k] != 'W') st[k] = 'L';

        // ---- numbers that depend on the stages ----
        boolean inWake = false;
        int wakeRun = 0;
        boolean phoneInRun = false;
        for (int k = onset; k <= wake + 1 && k < n; k++) {
            boolean w = k <= wake && st[k] == 'W';
            if (w) {
                wakeRun++;
                phoneInRun |= ep[k].phone;
            } else {
                if (wakeRun >= 2 || (wakeRun >= 1 && phoneInRun)) r.awakenings++;
                wakeRun = 0;
                phoneInRun = false;
            }
        }
        boolean in = false;
        for (int k = onset; k < n; k++) {
            if (ep[k].phone && !in) r.pickups.add(k);
            in = ep[k].phone;
        }
        return r;
    }

    /** Each source scaled against its own 90th percentile, then a Cole–Kripke style window (4 back, 2 ahead). */
    static double[] smoothActivity(SleepDsp.Epoch[] ep, double[] act) {
        int n = ep.length;
        double micScale = p90(ep, act, false), motionScale = p90(ep, act, true);
        double[] a = new double[n], d = new double[n];
        for (int i = 0; i < n; i++) a[i] = ep[i].noData ? 0 : act[i] / (ep[i].motion ? motionScale : micScale);
        double[] w = {0.15, 0.15, 0.20, 0.25, 1.00, 0.25, 0.15};
        for (int i = 0; i < n; i++) {
            double v = 0;
            for (int k = -4; k <= 2; k++) if (i + k >= 0 && i + k < n) v += w[k + 4] * a[i + k];
            d[i] = v;
        }
        return d;
    }

    private static double p90(SleepDsp.Epoch[] ep, double[] act, boolean motion) {
        int k = 0;
        double[] v = new double[ep.length];
        for (int i = 0; i < ep.length; i++) if (!ep[i].noData && ep[i].motion == motion) v[k++] = act[i];
        if (k == 0) return 1;
        v = Arrays.copyOf(v, k);
        Arrays.sort(v);
        return Math.max(1.0, v[(int) Math.floor((k - 1) * 0.9)]);
    }

    /**
     * Webster-style rescoring: a single loud epoch is a brief arousal (light sleep), not a waking, unless the phone
     * was in use; after 4+ min awake the first minute back is still awake (sleep doesn't snap back instantly).
     */
    private static void rescoreWake(char[] st, SleepDsp.Epoch[] ep, int from, int to) {
        int i = from;
        while (i <= to) {
            if (st[i] != 'W') {
                i++;
                continue;
            }
            int j = i;
            boolean phone = false;
            while (j <= to && st[j] == 'W') {
                phone |= ep[j].phone;
                j++;
            }
            int len = j - i;
            if (len == 1 && !phone) st[i] = 'L';
            else if (len >= 8) for (int k = j; k < Math.min(j + 2, to + 1); k++) if (st[k] != 'W') st[k] = 'W';
            i = j + (len >= 8 ? 2 : 0);
        }
    }

    // ---- evidence: how much more (or less) each epoch looks like deep / REM than light ----

    private static void evidence(SleepDsp.Epoch[] ep, double[] act, double[] d, char[] st, int from, int to,
                                 double[] dD, double[] dR) {
        // Breathing regularity and how far the breathing rate strays from its own last ±10 minutes, each ranked
        // within this night (0..1), so the evidence moves stages around rather than inflating any of them.
        int n = ep.length;
        double[] reg = new double[n], dev = new double[n];
        Arrays.fill(reg, Double.NaN);
        Arrays.fill(dev, Double.NaN);
        for (int i = from; i <= to; i++) {
            SleepDsp.Epoch e = ep[i];
            if (st[i] == 'W' || e.noData || e.breathConf < 0.3) continue;
            if (e.breathReg >= 0) reg[i] = e.breathReg;
            if (e.breathRate > 0) {
                List<Double> around = new ArrayList<>();
                for (int k = Math.max(from, i - 20); k <= Math.min(to, i + 20); k++) {
                    if (ep[k].breathConf >= 0.3 && ep[k].breathRate > 0 && !ep[k].noData) around.add(ep[k].breathRate);
                }
                if (around.size() >= 5) {
                    double m = median(around);
                    if (m > 0) dev[i] = Math.abs(e.breathRate - m) / m;
                }
            }
        }
        double[] regRank = rank(reg, from, to), devRank = rank(dev, from, to);

        for (int i = from; i <= to; i++) {
            SleepDsp.Epoch e = ep[i];
            if (e.noData) continue;
            if (st[i] == 'W') {
                dD[i] = -3;
                dR[i] = -1.5;
                continue;
            }
            double xd = 0, xr = 0;
            // movement: deep sleep is almost motionless; REM is still except for small twitches
            if (e.major > 0) {
                xd -= 3;
                xr -= 2;
            } else if (e.medium > 0) {
                xd -= 1.2;
                xr -= 0.8;
            } else if (e.micro > 0) {
                xd -= 0.6;
                xr += Math.min(2, e.micro) * 0.3;
            }
            if (e.activeSec >= 3) xd -= 1;
            if (d[i] > 0.3) xd -= Math.min(2, (d[i] - 0.3) * 3);
            // breathing, relative to the rest of the night
            if (!Double.isNaN(regRank[i])) {
                double u = (regRank[i] - 0.5) * 2;          // -1 uneven .. +1 very regular
                xd += 0.8 * u;
                xr -= 0.6 * u;
            }
            if (!Double.isNaN(devRank[i])) {
                double v = (devRank[i] - 0.5) * 2;          // -1 steady rate .. +1 rate wandering
                xd -= 0.4 * v;
                xr += 0.5 * v;
            }
            // snoring is rarer in REM
            if (e.snoreSec >= 5) {
                xd += 0.1;
                xr -= 0.3;
            }
            dD[i] = EVIDENCE * xd;
            dR[i] = EVIDENCE * xr;
        }
    }

    private static double[] rank(double[] v, int from, int to) {
        double[] out = new double[v.length];
        Arrays.fill(out, Double.NaN);
        List<double[]> pts = new ArrayList<>();
        for (int i = from; i <= to; i++) if (!Double.isNaN(v[i])) pts.add(new double[]{v[i], i});
        if (pts.size() < 20) return out;   // too little to compare against
        pts.sort((a, b) -> Double.compare(a[0], b[0]));
        int m = pts.size();
        int i = 0;
        while (i < m) {
            int j = i;
            while (j < m && pts.get(j)[0] == pts.get(i)[0]) j++;
            double r = ((i + j - 1) / 2.0) / (m - 1);      // ties share the middle rank
            for (int k = i; k < j; k++) out[(int) pts.get(k)[1]] = r;
            i = j;
        }
        return out;
    }

    // ---- 2. cycle boundaries by dynamic programming ----

    /** Start epochs of the cycles in [from, to). The last one may be cut short by waking up. */
    private static List<Integer> cycles(SleepDsp.Epoch[] ep, double[] act, char[] st, int from, int to, boolean firstBlock) {
        int len = to - from;
        List<Integer> out = new ArrayList<>();
        out.add(from);
        if (len <= CYCLE_MIN + 40) return out;
        // how much each epoch looks like the end of a cycle: movement or a brief waking nearby
        double[] bonus = new double[len + 1];
        for (int p = 1; p < len; p++) {
            double m = 0;
            for (int k = Math.max(from, from + p - 2); k <= Math.min(to - 1, from + p + 2); k++) {
                SleepDsp.Epoch e = ep[k];
                if (e.noData) continue;
                double v = 2.0 * e.major + 1.0 * e.medium + 0.3 * e.micro + (st[k] == 'W' ? 2 : 0);
                m = Math.max(m, v);
            }
            bonus[p] = 1.5 * Math.log(1 + m);
        }
        double[] best = new double[len + 1];
        int[] prev = new int[len + 1];
        Arrays.fill(best, Double.NEGATIVE_INFINITY);
        best[0] = 0;
        for (int p = 1; p <= len; p++) {
            boolean last = p == len;
            for (int q = Math.max(0, p - CYCLE_MAX); q <= p - (last ? 1 : CYCLE_MIN); q++) {
                if (best[q] == Double.NEGATIVE_INFINITY) continue;
                int cl = p - q;
                double mu = q == 0 && firstBlock ? CYCLE_FIRST : CYCLE_LATER;
                double pen;
                if (last) pen = cl > mu ? -sq(cl - mu) / (2.0 * CYCLE_SD * CYCLE_SD) : 0;   // cut short by waking
                else pen = -sq(cl - mu) / (2.0 * CYCLE_SD * CYCLE_SD) + bonus[p];
                double v = best[q] + pen;
                if (v > best[p]) {
                    best[p] = v;
                    prev[p] = q;
                }
            }
        }
        if (best[len] == Double.NEGATIVE_INFINITY) return out;
        List<Integer> rev = new ArrayList<>();
        for (int p = len; p > 0; p = prev[p]) if (prev[p] > 0) rev.add(from + prev[p]);
        for (int k = rev.size() - 1; k >= 0; k--) out.add(rev.get(k));
        return out;
    }

    // ---- 3. fitting light → deep → light → REM inside one cycle ----

    private static void fitCycle(char[] st, SleepDsp.Epoch[] ep, double[] dD, double[] dR, int s, int e,
                                 double hours, boolean partial, int nightOnset) {
        int len = e - s;
        if (len < 20) return;
        double typical = s == nightOnset ? CYCLE_FIRST : CYCLE_LATER;
        double done = Math.min(1, len / typical);
        // a cycle cut short by the alarm may not have reached its deep or REM part yet
        double muD = deepShare(hours) * typical * (partial ? Math.min(1, done / 0.5) : 1);
        double muR = remShare(hours) * typical * (partial ? clamp((done - 0.55) / 0.45, 0, 1) : 1);
        double sdD = Math.max(10, 0.45 * muD), sdR = Math.max(10, 0.4 * muR);
        int lead = s == nightOnset ? 16 : 6;                    // some light sleep before deep
        int gap = 6;                                            // and between deep and REM
        int maxD = (int) Math.min(len - lead, muD * 2.2 + 12);
        int maxR = (int) Math.min(len - lead, muR * 2.0 + 12);
        int remEarliest = nightOnset + 130;                     // no REM in the first 65 min of the night
        int maxTail = partial ? len : 6;                        // REM ends the cycle (or the night, if cut short)

        double[] pD = new double[len + 1], pR = new double[len + 1];
        for (int k = 0; k < len; k++) {
            int i = s + k;
            pD[k + 1] = pD[k] + dD[i];
            pR[k + 1] = pR[k] + dR[i];
        }
        // bestR[k]: best REM bout starting at or after offset k (or none)
        double[] bestR = new double[len + 2];
        int[] bestRs = new int[len + 2], bestRl = new int[len + 2];
        double none = -sq(muR) / (2 * sdR * sdR);
        for (int k = len + 1; k >= 0; k--) {
            bestR[k] = none;
            bestRs[k] = -1;
            bestRl[k] = 0;
        }
        for (int y = 1; y <= Math.max(0, maxR); y++) {
            double prior = -sq(y - muR) / (2 * sdR * sdR);
            for (int t = 0; t <= Math.min(maxTail, len - y); t++) {
                int rs = len - t - y;
                if (rs < lead || s + rs < remEarliest) continue;
                double v = pR[rs + y] - pR[rs] + prior;
                if (v > bestR[rs]) {
                    bestR[rs] = v;
                    bestRs[rs] = rs;
                    bestRl[rs] = y;
                }
            }
        }
        for (int k = len; k >= 0; k--) {
            if (bestR[k + 1] > bestR[k]) {
                bestR[k] = bestR[k + 1];
                bestRs[k] = bestRs[k + 1];
                bestRl[k] = bestRl[k + 1];
            }
        }
        // choose deep start a and length x, with REM after it
        double best = -sq(muD) / (2 * sdD * sdD) + bestR[0];
        int ba = -1, bx = 0, brs = bestRs[0], bry = bestRl[0];
        for (int x = 1; x <= Math.max(0, maxD); x++) {
            double prior = -sq(x - muD) / (2 * sdD * sdD);
            for (int a = lead; a + x <= len; a++) {
                int after = Math.min(len + 1, a + x + gap);
                double v = pD[a + x] - pD[a] + prior + bestR[after];
                if (v > best) {
                    best = v;
                    ba = a;
                    bx = x;
                    brs = bestRs[after];
                    bry = bestRl[after];
                }
            }
        }
        for (int k = 0; k < len; k++) if (st[s + k] != 'W') st[s + k] = 'L';
        if (ba >= 0) for (int k = ba; k < ba + bx; k++) if (st[s + k] != 'W') st[s + k] = 'D';
        if (brs >= 0) for (int k = brs; k < brs + bry; k++) if (st[s + k] != 'W') st[s + k] = 'R';
    }

    // ---- live use: the smart alarm has no look-ahead ----

    /**
     * Whether deep sleep is plausible right now, hours after falling asleep, given how still the last few minutes
     * were. Late in the night deep sleep is rare, so stillness alone no longer reads as deep.
     */
    static boolean deepPlausible(double hoursAsleep, boolean stillLately, double breathReg) {
        if (hoursAsleep < 0) return false;
        if (!stillLately) return false;
        if (deepShare(hoursAsleep) < 0.12) return false;           // about 3.5 h in and after
        return breathReg < 0 || breathReg >= 0.7;
    }

    // ---- helpers ----

    private static double sq(double v) {
        return v * v;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double median(List<Double> v) {
        double[] s = new double[v.size()];
        for (int i = 0; i < s.length; i++) s[i] = v.get(i);
        Arrays.sort(s);
        return s[s.length / 2];
    }
}
