package app.sthira;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

/**
 * Synthetic nights for the staging model. The first one is the night that exposed the old model: a still sleeper,
 * 7 h 50 min, two phone pickups, which came out as 6 h 55 min deep, no REM and no awakenings.
 */
public class SleepStagerTest {

    private static final long T0 = 1_763_750_000_000L;

    private static SleepDsp.Epoch[] night(int epochs) {
        SleepDsp.Epoch[] ep = new SleepDsp.Epoch[epochs];
        for (int i = 0; i < epochs; i++) {
            ep[i] = new SleepDsp.Epoch();
            ep[i].startMs = T0 + i * 30_000L;
        }
        return ep;
    }

    private static void move(SleepDsp.Epoch e, int micro, int medium, int major, double activeSec) {
        e.micro += micro;
        e.medium += medium;
        e.major += major;
        e.events += micro + medium + major;
        e.activeSec += activeSec;
        e.activity = SleepDsp.Epoch.activity(e.activeSec, e.major, e.micro);
    }

    private static int count(char[] st, char c, int from, int to) {
        int n = 0;
        for (int i = from; i <= to && i < st.length; i++) if (st[i] == c) n++;
        return n;
    }

    private static int sleep(SleepStager.Result r) {
        int n = 0;
        for (int i = r.onset; i <= r.wake; i++) if (r.stages[i] != 'W') n++;
        return n;
    }

    /** 00:22 to 08:15, almost no sound, a few twitches late in the night, phone at 05:06 and 07:06. */
    private static SleepDsp.Epoch[] stillNight() {
        SleepDsp.Epoch[] ep = night(946);
        for (int i = 0; i < 4; i++) move(ep[i], 0, 2, 1, 12);          // settling in
        Random rnd = new Random(7);
        for (int i = 540; i < 946; i++) if (rnd.nextDouble() < 0.05) move(ep[i], 1, 0, 0, 0.5);
        int p1 = (int) ((5 * 60 + 6 - (22)) * 2), p2 = (int) ((7 * 60 + 6 - 22) * 2);
        for (int p : new int[]{p1, p2}) {
            move(ep[p - 1], 0, 1, 0, 2);
            ep[p].phone = true;
            move(ep[p], 0, 1, 1, 6);
        }
        return ep;
    }

    @Test
    public void stillSleeperGetsARealisticNight() {
        SleepStager.Result r = SleepStager.stage(stillNight());
        int tst = sleep(r);
        assertTrue("fell asleep", r.onset >= 0 && r.onset < 20);
        double deep = 100.0 * count(r.stages, 'D', r.onset, r.wake) / tst;
        double rem = 100.0 * count(r.stages, 'R', r.onset, r.wake) / tst;
        assertTrue("deep " + deep + "%", deep >= 10 && deep <= 30);
        assertTrue("rem " + rem + "%", rem >= 15 && rem <= 35);
        assertEquals("both pickups are awakenings", 2, r.awakenings);
        assertEquals(2, r.pickups.size());
        assertTrue("cycles " + r.cycleStarts.size(), r.cycleStarts.size() >= 4 && r.cycleStarts.size() <= 6);
        // deep sits early, REM late, and no REM in the first 50 minutes
        int half = r.onset + (r.wake - r.onset) / 2;
        int deepEarly = count(r.stages, 'D', r.onset, half), deepAll = count(r.stages, 'D', r.onset, r.wake);
        int remLate = count(r.stages, 'R', half, r.wake), remAll = count(r.stages, 'R', r.onset, r.wake);
        assertTrue("deep early " + deepEarly + "/" + deepAll, deepEarly >= 0.7 * deepAll);
        assertTrue("rem late " + remLate + "/" + remAll, remLate >= 0.55 * remAll);
        assertEquals(0, count(r.stages, 'R', r.onset, r.onset + 129));
        System.out.println("still night: " + hyp(r.stages) + "  deep " + Math.round(deep) + "% rem " + Math.round(rem) + "%");
    }

    @Test
    public void movementKeepsDeepOut() {
        SleepDsp.Epoch[] ep = stillNight();
        // restless first half: medium movements every ~4 minutes
        for (int i = 30; i < 470; i += 8) move(ep[i], 0, 1, 0, 1.5);
        SleepStager.Result r = SleepStager.stage(ep);
        SleepStager.Result calm = SleepStager.stage(stillNight());
        int deepRestless = count(r.stages, 'D', r.onset, r.wake), deepCalm = count(calm.stages, 'D', calm.onset, calm.wake);
        assertTrue("restless " + deepRestless + " < calm " + deepCalm, deepRestless < deepCalm);
        for (int i = r.onset; i <= r.wake; i++) {
            if (ep[i].medium > 0 || ep[i].major > 0) assertFalse("no deep on a movement at " + i, r.stages[i] == 'D');
        }
    }

    @Test
    public void breathingPlacesDeepAndRem() {
        SleepDsp.Epoch[] ep = stillNight();
        Random rnd = new Random(3);
        for (int i = 0; i < ep.length; i++) {
            ep[i].breathConf = 0.6;
            ep[i].breathRate = 14 + rnd.nextGaussian() * 0.3;
            ep[i].breathReg = 0.72 + rnd.nextGaussian() * 0.02;
        }
        // slow, very regular breathing 35–80 min in; uneven, wandering breathing 150–170 min in
        for (int i = 70; i < 160; i++) {
            ep[i].breathReg = 0.93;
            ep[i].breathRate = 12.5 + rnd.nextGaussian() * 0.1;
        }
        for (int i = 300; i < 340; i++) {
            ep[i].breathReg = 0.45 + rnd.nextGaussian() * 0.05;
            ep[i].breathRate = 14 + (i % 2 == 0 ? 3 : -2.5);
        }
        SleepStager.Result r = SleepStager.stage(ep);
        int deepIn = count(r.stages, 'D', 70, 159), remIn = count(r.stages, 'R', 300, 339);
        assertTrue("deep in the regular stretch " + deepIn + "/90", deepIn >= 60);
        assertTrue("rem in the uneven stretch " + remIn + "/40", remIn >= 25);
        System.out.println("breathing night: " + hyp(r.stages));
    }

    @Test
    public void noSleepIsAllWake() {
        SleepDsp.Epoch[] ep = night(200);
        for (int i = 0; i < 200; i++) move(ep[i], 0, 3, 1, 15);
        SleepStager.Result r = SleepStager.stage(ep);
        assertEquals(-1, r.onset);
        assertEquals(200, count(r.stages, 'W', 0, 199));
    }

    @Test
    public void gapsAreNeverDeepOrRem() {
        SleepDsp.Epoch[] ep = stillNight();
        for (int i = 100; i < 200; i++) ep[i].noData = true;
        SleepStager.Result r = SleepStager.stage(ep);
        assertEquals(0, count(r.stages, 'D', 100, 199) + count(r.stages, 'R', 100, 199));
    }

    @Test
    public void shortNapHasNoRemTooEarly() {
        SleepDsp.Epoch[] ep = night(120);   // one hour
        SleepStager.Result r = SleepStager.stage(ep);
        assertEquals(0, count(r.stages, 'R', 0, 119));
    }

    @Test
    public void lateStillnessIsNotDeepForTheAlarm() {
        assertTrue(SleepStager.deepPlausible(1.0, true, -1));
        assertFalse(SleepStager.deepPlausible(5.5, true, -1));     // the smart alarm window of an 8 h night
        assertFalse(SleepStager.deepPlausible(1.0, false, -1));
        assertFalse(SleepStager.deepPlausible(1.0, true, 0.4));
    }

    @Test
    public void liveTrackerStopsCallingLateStillnessDeep() {
        final StringBuilder seen = new StringBuilder();
        SleepDsp dsp = new SleepDsp(e -> seen.append(e.online));
        short[] quiet = new short[SleepDsp.FRAME];
        Random rnd = new Random(1);
        long t = T0;
        // 6 hours of a faint, steady room hum
        for (int f = 0; f < 6 * 3600 * 20; f++) {
            for (int k = 0; k < quiet.length; k++) quiet[k] = (short) (rnd.nextGaussian() * 30);
            dsp.frame(quiet, quiet.length, t);
            t += 50;
        }
        String s = seen.toString();
        int n = s.length();
        assertTrue("some deep early on", s.substring(0, 300).indexOf('D') >= 0);
        assertEquals("none after 4 h", -1, s.substring(4 * 120, n).indexOf('D'));
    }

    private static String hyp(char[] st) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < st.length; i += 10) b.append(st[i]);
        return b.toString();
    }
}
