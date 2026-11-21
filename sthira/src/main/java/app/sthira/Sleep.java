package app.sthira;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The sleep tracker's bookkeeping: settings, starting and stopping a night, the hard alarm, and the
 * morning analysis that turns the night's 30-second epochs into stages, numbers and a score.
 *
 * Reliability rule: the alarm at the set time is an AlarmManager alarm clock armed when the night
 * starts. It never depends on the tracking service being alive; the smart alarm can only move it earlier.
 */
final class Sleep {

    static final int REQ_MIC = 5;
    static final int NOTE_TRACK = 20, NOTE_RING = 21, NOTE_INFO = 22;
    static final String MODE_SMART = "smart", MODE_EXACT = "exact", MODE_TRACK = "track";

    private Sleep() {
    }

    // ---- settings ----

    static String alarmTime(Context c) {
        return Store.setting(c, "sleepAlarm", "06:30");
    }

    static int window(Context c) {
        return Store.setting(c, "sleepWindow", 30);
    }

    static String mode(Context c) {
        return Store.setting(c, "sleepMode", MODE_SMART);
    }

    static int goalMinutes(Context c) {
        return Store.setting(c, "sleepGoal", 480);
    }

    static boolean showRem(Context c) {
        return Store.setting(c, "sleepRem", 1) == 1;
    }

    /** The next time the alarm clock time comes round from now. */
    static long nextTarget(Context c) {
        int m = Store.minutes(alarmTime(c));
        LocalDateTime at = LocalDate.now().atTime(m / 60, m % 60);
        if (!at.isAfter(LocalDateTime.now().plusMinutes(1))) at = at.plusDays(1);
        return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** True when the alarm clock is already armed for a time still to come. */
    static boolean alarmArmed(Context c) {
        JSONObject a = Store.root(c).optJSONObject("sleepAlarm");
        return a != null && a.optLong("next") > System.currentTimeMillis();
    }

    /**
     * The safety net: arms the plain alarm for the next set time even if Start is never tapped.
     * Called at shutdown time and when the check-in is saved. Track-only mode means no alarm, so it does nothing.
     * Starting the tracker later re-arms the same alarm (same PendingIntent), so nothing doubles up.
     */
    static void armBackup(Context c) {
        if (MODE_TRACK.equals(mode(c)) || active(c) != null || alarmArmed(c)) return;
        long t = nextTarget(c);
        SleepAlarm.arm(c, t, t);
    }

    // ---- the active night ----

    static JSONObject active(Context c) {
        return Store.root(c).optJSONObject("sleepNow");
    }

    static JSONArray nights(Context c) {
        return Store.arr(Store.root(c), "sleepNights");
    }

    static JSONObject lastNight(Context c) {
        JSONArray n = nights(c);
        return n.length() == 0 ? null : n.optJSONObject(n.length() - 1);
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "sleep");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File epochFile(Context c, long id) {
        return new File(dir(c), id + ".csv");
    }

    static boolean micAllowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** "Start sleep" tapped. Asks for the microphone first if needed. */
    static void start(MainActivity a) {
        if (!micAllowed(a)) {
            List<String> ask = new ArrayList<>();
            ask.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT >= 33
                    && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ask.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            a.requestPermissions(ask.toArray(new String[0]), REQ_MIC);
            return;
        }
        begin(a);
    }

    static void afterPermissions(MainActivity a) {
        if (micAllowed(a)) {
            begin(a);
        } else {
            // No microphone: still keep the promise of waking up on time.
            if (!MODE_TRACK.equals(mode(a))) {
                long t = nextTarget(a);
                SleepAlarm.arm(a, t, t);
                Toast.makeText(a, "No microphone, so no tracking. Plain alarm set for " + alarmTime(a) + ".",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private static void begin(MainActivity a) {
        if (active(a) != null) return;
        long now = System.currentTimeMillis();
        String mode = mode(a);
        long target = MODE_TRACK.equals(mode) ? 0 : nextTarget(a);
        JSONObject s = new JSONObject();
        Store.put(s, "id", now);
        Store.put(s, "start", now);
        Store.put(s, "mode", mode);
        Store.put(s, "target", target);
        Store.put(s, "window", MODE_SMART.equals(mode) ? window(a) : 0);
        Store.put(s, "charging", charging(a));
        Store.put(Store.root(a), "sleepNow", s);
        Store.save(a);
        if (target > 0) SleepAlarm.arm(a, target, target);
        Intent svc = new Intent(a, SleepService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(svc);
            else a.startService(svc);
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't start tracking. The alarm is still set.", Toast.LENGTH_LONG).show();
        }
        a.refresh();
    }

    /** Stops tonight's tracking now (woke before the alarm, or the alarm was stopped). */
    static void stopNow(Context c, boolean cancelAlarm) {
        if (cancelAlarm) SleepAlarm.cancel(c);
        SleepService svc = SleepService.instance;
        if (svc != null) {
            svc.finishNight(System.currentTimeMillis());
        } else if (active(c) != null) {
            finishNight(c, System.currentTimeMillis(), false);
        }
    }

    /** On app open: a night whose service died without finishing gets wrapped up with what was recorded. */
    static void checkStale(Context c) {
        JSONObject s = active(c);
        if (s == null || SleepService.instance != null) return;
        File f = epochFile(c, s.optLong("id"));
        long last = f.exists() ? f.lastModified() : s.optLong("start");
        if (System.currentTimeMillis() - last > 10 * 60_000L) finishNight(c, Math.max(last, s.optLong("start")), true);
    }

    /** After a reboot: the alarm is re-armed; the microphone can't restart on its own. */
    static void onBoot(Context c) {
        JSONObject s = active(c);
        JSONObject a = Store.root(c).optJSONObject("sleepAlarm");
        if (a != null && a.optLong("next") > System.currentTimeMillis()) {
            SleepAlarm.arm(c, a.optLong("next"), a.optLong("target"));
        }
        if (s != null && SleepService.instance == null) {
            File f = epochFile(c, s.optLong("id"));
            long last = f.exists() ? f.lastModified() : s.optLong("start");
            finishNight(c, Math.max(last, s.optLong("start")), true);
            if (a != null && a.optLong("next") > System.currentTimeMillis()) {
                notifyInfo(c, "Sleep tracking stopped after a restart",
                        "Your alarm is still set for " + Store.hhmm(minuteOf(a.optLong("next"))) + ".");
            }
        }
    }

    static int minuteOf(long ms) {
        LocalTime t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime();
        return t.getHour() * 60 + t.getMinute();
    }

    static boolean charging(Context c) {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            return bm != null && bm.isCharging();
        } catch (Exception e) {
            return false;
        }
    }

    static int battery(Context c) {
        try {
            android.os.BatteryManager bm = (android.os.BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            return bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) {
            return -1;
        }
    }

    // ---- checks shown on the setup screen ----

    static boolean batteryUnrestricted(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** OnePlus, OPPO and realme (ColorOS) kill background apps on their own, on top of Android's rules. */
    static boolean colorOs() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        return m.contains("oneplus") || m.contains("oppo") || m.contains("realme");
    }

    /**
     * Show the ColorOS checklist until it has been opened once, and again after any night that was cut short
     * since then (the kill came back, so the settings didn't stick or were never finished).
     */
    static boolean colorOsNeedsSetup(Context c) {
        if (!colorOs()) return false;
        long seen = Store.root(c).optLong("colorOsSeen", 0);
        if (seen == 0) return true;
        JSONObject last = lastNight(c);
        return last != null && last.optBoolean("interrupted") && last.optLong("start") > seen;
    }

    static void openAppInfo(Context c) {
        Store.put(Store.root(c), "colorOsSeen", System.currentTimeMillis());
        Store.save(c);
        try {
            c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    static void askBattery(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + c.getPackageName())));
        } catch (Exception e) {
            try {
                c.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    static boolean fullScreenAllowed(Context c) {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm == null || nm.canUseFullScreenIntent();
    }

    static void askFullScreen(Context c) {
        if (Build.VERSION.SDK_INT < 34) return;
        try {
            c.startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + c.getPackageName())));
        } catch (Exception ignored) {
        }
    }

    static boolean exactAllowed(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am == null || am.canScheduleExactAlarms();
    }

    static void notifyInfo(Context c, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("sleepinfo", "Sleep notes", NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN, MainActivity.SLEEP)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            nm.notify(NOTE_INFO, new Notification.Builder(c, "sleepinfo")
                    .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
                    .setColor(Ui.MAROON).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, NOTE_INFO, open,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                    .build());
        } catch (Exception ignored) {
        }
    }

    // ---- the morning analysis ----

    static List<SleepDsp.Epoch> readEpochs(Context c, long id) {
        List<SleepDsp.Epoch> out = new ArrayList<>();
        File f = epochFile(c, id);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                SleepDsp.Epoch e = SleepDsp.Epoch.parse(line);
                if (e != null) out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Closes the active night: stages, numbers, score, saved to history, and to Health Connect if allowed. */
    static synchronized JSONObject finishNight(Context c, long endMs, boolean interrupted) {
        JSONObject s = active(c);
        if (s == null) return null;
        long id = s.optLong("id"), start = s.optLong("start");
        List<SleepDsp.Epoch> raw = readEpochs(c, id);
        // Put every epoch on its 30-second slot from the start, filling gaps as "no data".
        int slots = (int) Math.max(0, (endMs - start) / 30_000L);
        SleepDsp.Epoch[] ep = new SleepDsp.Epoch[slots];
        for (SleepDsp.Epoch e : raw) {
            int i = (int) ((e.startMs - start) / 30_000L);
            if (i >= 0 && i < slots) ep[i] = e;
        }
        for (int i = 0; i < slots; i++) {
            if (ep[i] == null) {
                ep[i] = new SleepDsp.Epoch();
                ep[i].startMs = start + i * 30_000L;
                ep[i].noData = true;
            }
        }
        JSONObject night = analyse(c, ep, start, endMs);
        Store.put(night, "id", id);
        Store.put(night, "mode", s.optString("mode"));
        Store.put(night, "target", s.optLong("target"));
        Store.put(night, "window", s.optInt("window"));
        Store.put(night, "interrupted", interrupted);
        Store.put(night, "charging", s.optBoolean("charging"));
        if (s.has("firedAt")) {
            Store.put(night, "firedAt", s.optLong("firedAt"));
            Store.put(night, "firedReason", s.optString("firedReason"));
            Store.put(night, "firedStage", s.optString("firedStage"));
        }
        Store.put(night, "batteryEnd", battery(c));
        JSONArray all = nights(c);
        all.put(night);
        while (all.length() > 180) all.remove(0);
        Store.root(c).remove("sleepNow");
        Store.save(c);
        // Fragments (tracking killed early, or stopped by mistake) stay in the app but never go to Health Connect.
        if (Build.VERSION.SDK_INT >= 34 && slots > 20 && !night.optBoolean("short")) {
            HealthSteps.writeSleep(c, id, start, endMs, night.optString("stages"), showRem(c));
        }
        // epoch files older than 90 days go
        File[] old = dir(c).listFiles();
        for (int i = 0; old != null && i < old.length; i++) {
            if (System.currentTimeMillis() - old[i].lastModified() > 90L * 86_400_000L) old[i].delete();
        }
        return night;
    }

    /**
     * Offline staging with look-ahead. Movement drives it: a lot is awake, some is light sleep,
     * a long stretch of near-stillness is deep sleep. REM is only a guess and is marked as one.
     */
    static JSONObject analyse(Context c, SleepDsp.Epoch[] ep, long start, long end) {
        int n = ep.length;
        double[] act = new double[n];
        for (int i = 0; i < n; i++) act[i] = ep[i].noData ? 0 : ep[i].activity;
        double[] sorted = act.clone();
        Arrays.sort(sorted);
        double scale = n == 0 ? 1 : Math.max(1.0, sorted[(int) Math.floor((n - 1) * 0.9)]);
        double[] a = new double[n], d = new double[n];
        for (int i = 0; i < n; i++) a[i] = act[i] / scale;
        double[] w = {0.15, 0.15, 0.20, 0.25, 1.00, 0.25, 0.15};
        for (int i = 0; i < n; i++) {
            double v = 0;
            for (int k = -4; k <= 2; k++) if (i + k >= 0 && i + k < n) v += w[k + 4] * a[i + k];
            d[i] = v;
        }

        char[] st = new char[n];
        for (int i = 0; i < n; i++) {
            SleepDsp.Epoch e = ep[i];
            st[i] = (e.phone || e.activeSec >= 10 || d[i] >= 1.2) ? 'W' : 'L';
        }
        // sleep onset: the first 10 minutes in a row without waking
        int onset = -1, run = 0;
        for (int i = 0; i < n; i++) {
            boolean calm = st[i] != 'W' && d[i] < 0.6;
            run = calm ? run + 1 : 0;
            if (run >= 20) {
                onset = i - 19;
                break;
            }
        }
        int wake = -1;
        if (onset >= 0) {
            for (int i = n - 1; i >= onset; i--) {
                if (st[i] != 'W') {
                    wake = i;
                    break;
                }
            }
        }
        if (onset < 0) {
            Arrays.fill(st, 'W');
        } else {
            for (int i = 0; i < onset; i++) st[i] = 'W';
            for (int i = wake + 1; i < n; i++) st[i] = 'W';
            // deep: six or more quiet epochs, a stricter bar early in the night, looser late
            for (int i = onset + 30; i <= wake; ) {
                double hours = (i - onset) / 120.0;
                double thr = 0.15 * (hours < 3 ? 1.3 : hours > 5 ? 0.8 : 1.0);
                int j = i;
                while (j <= wake && st[j] == 'L' && d[j] < thr
                        && (ep[j].breathConf < 0.3 || ep[j].breathReg < 0 || ep[j].breathReg >= 0.8)) j++;
                if (j - i >= 6) for (int k = i; k < j; k++) st[k] = 'D';
                i = Math.max(j, i + 1);
            }
            // REM (estimate): still body, small twitches or uneven breathing, after a cycle boundary
            for (int i = onset + 140; i <= wake; i++) {
                if (st[i] != 'L') continue;
                SleepDsp.Epoch e = ep[i];
                boolean signs = e.micro >= 2 || (e.breathConf >= 0.3 && e.breathReg >= 0 && e.breathReg < 0.6);
                if (d[i] >= 0.4 || e.major > 0 || !signs) continue;
                boolean boundary = false;
                for (int k = Math.max(onset, i - 40); k < i; k++) {
                    if (st[k] == 'D' || ep[k].major > 0) {
                        boundary = true;
                        break;
                    }
                }
                if (boundary) st[i] = 'R';
            }
            // stretch REM a little so it forms bouts, then smooth everything
            for (int i = onset; i <= wake; i++) {
                if (st[i] == 'R') {
                    for (int k = i + 1; k <= Math.min(wake, i + 6) && st[k] == 'L'; k++) st[k] = 'r';
                }
            }
            for (int i = 0; i < n; i++) if (st[i] == 'r') st[i] = 'R';
            smooth(st, onset, wake);
        }

        // numbers
        int sleepEp = 0, deep = 0, light = 0, rem = 0, wakeIn = 0, awakenings = 0;
        double snore = 0, move = 0;
        int moveN = 0;
        if (onset >= 0) {
            boolean inWake = false;
            int wakeRun = 0;
            for (int i = onset; i <= wake; i++) {
                char ch = st[i];
                if (ch == 'W') {
                    wakeIn++;
                    wakeRun++;
                    if (!inWake && wakeRun >= 4) {
                        awakenings++;
                        inWake = true;
                    }
                } else {
                    sleepEp++;
                    wakeRun = 0;
                    inWake = false;
                    if (ch == 'D') deep++;
                    else if (ch == 'R') rem++;
                    else light++;
                    move += act[i];
                    moveN++;
                }
                snore += ep[i].snoreSec;
            }
        }
        double tib = (end - start) / 60_000.0, tst = sleepEp / 2.0;
        double latency = onset < 0 ? tib : onset / 2.0;
        double waso = wakeIn / 2.0;
        double movementIndex = moveN == 0 ? 0 : move / moveN;

        JSONObject night = new JSONObject();
        Store.put(night, "start", start);
        Store.put(night, "end", end);
        Store.put(night, "date", Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault()).toLocalDate().toString());
        if (onset >= 0) {
            Store.put(night, "onset", start + onset * 30_000L);
            Store.put(night, "wake", start + (wake + 1) * 30_000L);
        }
        Store.put(night, "tib", Math.round(tib));
        Store.put(night, "tst", Math.round(tst));
        Store.put(night, "latency", Math.round(latency));
        Store.put(night, "waso", Math.round(waso));
        Store.put(night, "awakenings", awakenings);
        Store.put(night, "eff", tib <= 0 ? 0 : Math.round(100 * tst / tib));
        Store.put(night, "deep", deep / 2);
        Store.put(night, "light", light / 2);
        Store.put(night, "rem", rem / 2);
        Store.put(night, "awake", Math.round(tib - tst));
        Store.put(night, "snore", Math.round(snore / 60));
        Store.put(night, "move", Math.round(movementIndex * 100) / 100.0);
        Store.put(night, "short", tib < 180);
        Store.put(night, "stages", new String(st));
        // Phone pickups after falling asleep: separate runs of epochs where the phone was unlocked.
        org.json.JSONArray pickups = new org.json.JSONArray();
        if (onset >= 0) {
            boolean in = false;
            for (int i = onset; i < n; i++) {
                if (ep[i].phone && !in) pickups.put(start + i * 30_000L);
                in = ep[i].phone;
            }
        }
        Store.put(night, "pickups", pickups);
        score(c, night, tst, sleepEp == 0 ? 0 : deep * 100.0 / sleepEp, movementIndex, awakenings, waso, start);
        return night;
    }

    /** Bouts shorter than their minimum join whichever neighbour has more time in total. */
    private static void smooth(char[] st, int from, int to) {
        for (int pass = 0; pass < 3; pass++) {
            int i = from;
            while (i <= to) {
                int j = i;
                while (j <= to && st[j] == st[i]) j++;
                int len = j - i;
                int min = st[i] == 'D' ? 6 : st[i] == 'R' ? 10 : st[i] == 'W' ? 2 : 1;
                if (len < min) {
                    char before = i > from ? st[i - 1] : 0, after = j <= to ? st[j] : 0;
                    char pick = before == 0 ? after : after == 0 ? before
                            : total(st, from, to, before) >= total(st, from, to, after) ? before : after;
                    if (pick == 0 || pick == 'W') pick = 'L';
                    for (int k = i; k < j; k++) st[k] = pick;
                }
                i = j;
            }
        }
    }

    private static int total(char[] st, int from, int to, char ch) {
        int n = 0;
        for (int i = from; i <= to; i++) if (st[i] == ch) n++;
        return n;
    }

    /**
     * 0–100, from five parts that are always shown: duration (35), deep sleep (20), calm (20),
     * continuity (15) and regularity (10). Deep and calm are judged against your own past nights.
     */
    private static void score(Context c, JSONObject night, double tst, double deepPct, double movementIndex,
                              int awakenings, double waso, long start) {
        JSONArray past = nights(c);
        List<Double> deeps = new ArrayList<>(), moves = new ArrayList<>(), beds = new ArrayList<>();
        for (int i = Math.max(0, past.length() - 14); i < past.length(); i++) {
            JSONObject p = past.optJSONObject(i);
            if (p == null || p.optBoolean("short")) continue;
            long t = Math.max(1, p.optLong("tst"));
            deeps.add(p.optInt("deep") * 100.0 / t);
            moves.add(p.optDouble("move", 0));
            int m = minuteOf(p.optLong("start"));
            beds.add((double) (m < 720 ? m + 1440 : m));   // after-midnight bedtimes count as late, not early
        }
        double goal = goalMinutes(c);
        double dur = Math.min(tst / goal, 1) * 35 - (tst > goal + 120 ? 5 : 0);
        double deepRef = deeps.size() >= 7 ? median(deeps) : 15;
        double deepPart = Math.min(Math.max(deepPct / Math.max(deepRef, 1), 0), 1.2) / 1.2 * 20;
        double moveRef = moves.size() >= 7 ? percentile(moves, 0.9) : Math.max(1, movementIndex * 1.5);
        double calm = 20 * (1 - Math.min(Math.max(movementIndex / Math.max(moveRef, 0.01), 0), 1));
        double cont = Math.max(0, 15 - 3 * awakenings - 0.2 * waso);
        int bed = minuteOf(start);
        double bedAdj = bed < 720 ? bed + 1440 : bed;
        double reg = beds.size() >= 3 ? Math.max(0, 10 - Math.abs(bedAdj - median(beds)) / 6) : 10;
        double total = Math.max(0, Math.min(100, dur + deepPart + calm + cont + reg));
        JSONObject parts = new JSONObject();
        Store.put(parts, "duration", Math.round(dur));
        Store.put(parts, "deep", Math.round(deepPart));
        Store.put(parts, "calm", Math.round(calm));
        Store.put(parts, "continuity", Math.round(cont));
        Store.put(parts, "regularity", Math.round(reg));
        Store.put(night, "parts", parts);
        Store.put(night, "score", Math.round(total));
    }

    private static double median(List<Double> v) {
        return percentile(v, 0.5);
    }

    private static double percentile(List<Double> v, double p) {
        if (v.isEmpty()) return 0;
        double[] s = new double[v.size()];
        for (int i = 0; i < s.length; i++) s[i] = v.get(i);
        Arrays.sort(s);
        return s[(int) Math.floor((s.length - 1) * p)];
    }

    static String hm(long minutes) {
        return minutes / 60 + "h " + String.format(java.util.Locale.ROOT, "%02d", minutes % 60) + "m";
    }

    static String reasonText(String r) {
        switch (r == null ? "" : r) {
            case "MAJOR_MOVEMENT":
                return "you turned over in light sleep";
            case "RESTLESS":
                return "you were getting restless";
            case "AWAKE":
                return "you were already awake";
            case "LIGHTNESS":
                return "you were in light sleep";
            case "DEADLINE":
                return "it was time";
            default:
                return "";
        }
    }
}
