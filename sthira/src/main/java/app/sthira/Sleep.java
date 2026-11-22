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
        scheduleWatch(a, WATCH_MINUTES * 60_000L);
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

    /**
     * On app open: if tonight's tracker isn't running (the phone killed it), start it again while the night is
     * still on; the gap is marked as no data. Only a night that's long over gets wrapped up with what was recorded.
     * Coming back to the app also hands the microphone back to a tracker that fell back to the motion sensor.
     */
    static void checkStale(Context c) {
        restageIfNeeded(c);
        JSONObject s = active(c);
        if (s == null) return;
        if (stillNight(s)) {
            SleepService svc = SleepService.instance;
            if (svc == null || svc.motionOnly()) restartTracking(c);
            return;
        }
        if (SleepService.instance != null) return;
        File f = epochFile(c, s.optLong("id"));
        long last = f.exists() ? f.lastModified() : s.optLong("start");
        finishNight(c, Math.max(last, s.optLong("start")), true);
    }

    /** Whether a night is still worth tracking: before the alarm (plus an hour of snoozing), at most 14 hours in all. */
    static boolean stillNight(JSONObject s) {
        long now = System.currentTimeMillis(), target = s.optLong("target");
        if (now - s.optLong("start") > 14 * 3_600_000L) return false;
        return target <= 0 || now < target + 3_600_000L;
    }

    /** Starts (or nudges) the tracking service. From the background it comes back on the motion sensor. */
    static boolean restartTracking(Context c) {
        try {
            Intent svc = new Intent(c, SleepService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(svc);
            else c.startService(svc);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static final int WATCH_MINUTES = 10;

    private static PendingIntent watchIntent(Context c) {
        Intent i = new Intent(c, SleepAlarm.class).setAction(SleepAlarm.WATCH);
        return PendingIntent.getBroadcast(c, 307, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * The watchdog: an exact alarm every few minutes through the night. If the phone killed the tracker, the alarm
     * brings the app back and the tracker restarts (exact alarms are allowed to start a foreground service).
     */
    static void scheduleWatch(Context c, long inMs) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = System.currentTimeMillis() + inMs;
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, watchIntent(c));
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, watchIntent(c));
        } catch (Exception ignored) {
        }
    }

    static void cancelWatch(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(watchIntent(c));
    }

    /** The watchdog fired. */
    static void onWatch(Context c) {
        JSONObject s = active(c);
        if (s == null) return;
        if (!stillNight(s)) {
            if (SleepService.instance != null) SleepService.instance.finishNight(System.currentTimeMillis());
            else {
                File f = epochFile(c, s.optLong("id"));
                long last = f.exists() ? f.lastModified() : s.optLong("start");
                finishNight(c, Math.max(last, s.optLong("start")), true);
            }
            return;
        }
        if (SleepService.instance == null) {
            Store.put(s, "restarts", s.optInt("restarts") + 1);
            Store.save(c);
            restartTracking(c);
        }
        scheduleWatch(c, WATCH_MINUTES * 60_000L);
    }

    /** After a reboot: the alarm is re-armed; the microphone can't restart on its own. */
    static void onBoot(Context c) {
        JSONObject s = active(c);
        JSONObject a = Store.root(c).optJSONObject("sleepAlarm");
        if (a != null && a.optLong("next") > System.currentTimeMillis()) {
            SleepAlarm.arm(c, a.optLong("next"), a.optLong("target"));
        }
        if (s != null && SleepService.instance == null) {
            if (stillNight(s) && restartTracking(c)) {
                // After a reboot the microphone can't come back on its own; the motion sensor carries on.
                scheduleWatch(c, WATCH_MINUTES * 60_000L);
                return;
            }
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
        cancelWatch(c);
        long id = s.optLong("id"), start = s.optLong("start");
        int slots = (int) Math.max(0, (endMs - start) / 30_000L);
        SleepDsp.Epoch[] ep = grid(c, id, start, endMs);
        JSONObject night = analyse(c, ep, start, endMs);
        Store.put(night, "id", id);
        Store.put(night, "mode", s.optString("mode"));
        Store.put(night, "target", s.optLong("target"));
        Store.put(night, "window", s.optInt("window"));
        Store.put(night, "interrupted", interrupted);
        Store.put(night, "restarts", s.optInt("restarts"));
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

    /** Stages, numbers and score for one night. Staging lives in SleepStager (testable off the phone). */
    static JSONObject analyse(Context c, SleepDsp.Epoch[] ep, long start, long end) {
        return analyse(c, ep, start, end, nights(c));
    }

    /** As above, scored against the given earlier nights (the last 14 of them are used). */
    static JSONObject analyse(Context c, SleepDsp.Epoch[] ep, long start, long end, JSONArray past) {
        int n = ep.length;
        SleepStager.Result r = SleepStager.stage(ep);
        char[] st = r.stages;
        int onset = r.onset, wake = r.wake;
        double[] act = r.activity;

        int sleepEp = 0, deep = 0, light = 0, rem = 0, wakeIn = 0;
        double snore = 0, move = 0;
        int moveN = 0;
        if (onset >= 0) {
            for (int i = onset; i <= wake; i++) {
                char ch = st[i];
                if (ch == 'W') {
                    wakeIn++;
                } else {
                    sleepEp++;
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
        Store.put(night, "awakenings", r.awakenings);
        Store.put(night, "eff", tib <= 0 ? 0 : Math.round(100 * tst / tib));
        Store.put(night, "deep", deep / 2);
        Store.put(night, "light", light / 2);
        Store.put(night, "rem", rem / 2);
        Store.put(night, "awake", Math.round(tib - tst));
        Store.put(night, "snore", Math.round(snore / 60));
        Store.put(night, "move", Math.round(movementIndex * 100) / 100.0);
        Store.put(night, "short", tib < 180);
        Store.put(night, "cycles", r.cycleStarts.size());
        Store.put(night, "model", SleepStager.MODEL);
        // How much of the night had no recording at all, and how much came from the motion sensor.
        int gaps = 0, motionEp = 0;
        for (SleepDsp.Epoch e : ep) {
            if (e.noData) gaps++;
            else if (e.motion) motionEp++;
        }
        Store.put(night, "gapMin", gaps / 2);
        Store.put(night, "motionMin", motionEp / 2);
        Store.put(night, "stages", new String(st));
        // Phone pickups after falling asleep: separate runs of epochs where the phone was unlocked.
        JSONArray pickups = new JSONArray();
        for (int i : r.pickups) pickups.put(start + i * 30_000L);
        Store.put(night, "pickups", pickups);
        score(c, night, past, tst, sleepEp == 0 ? 0 : deep * 100.0 / sleepEp, movementIndex, r.awakenings, waso, start);
        return night;
    }

    /** Builds the night's epoch grid from its file: one per 30-second slot, gaps marked as no data. */
    static SleepDsp.Epoch[] grid(Context c, long id, long start, long endMs) {
        List<SleepDsp.Epoch> raw = readEpochs(c, id);
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
        return ep;
    }

    /**
     * Once after an update to the staging model: every saved night whose recording is still on the phone (90 days)
     * is staged again, oldest first, so history and scores all come from the same model. Nights are re-sent to
     * Health Connect with a higher record version so the old stages there are replaced.
     */
    static synchronized void restageIfNeeded(Context c) {
        JSONObject root = Store.root(c);
        if (root.optInt("sleepModel", 1) >= SleepStager.MODEL) return;
        JSONArray all = nights(c);
        JSONArray done = new JSONArray();
        String[] keep = {"id", "mode", "target", "window", "interrupted", "restarts", "charging",
                "firedAt", "firedReason", "firedStage", "batteryEnd"};
        for (int k = 0; k < all.length(); k++) {
            JSONObject old = all.optJSONObject(k);
            if (old == null) continue;
            long id = old.optLong("id"), start = old.optLong("start"), end = old.optLong("end");
            File f = epochFile(c, id);
            if (id <= 0 || end <= start || !f.exists()) {
                done.put(old);
                continue;
            }
            try {
                JSONObject night = analyse(c, grid(c, id, start, end), start, end, done);
                for (String key : keep) if (old.has(key)) Store.put(night, key, old.opt(key));
                done.put(night);
                int slots = (int) ((end - start) / 30_000L);
                if (Build.VERSION.SDK_INT >= 34 && slots > 20 && !night.optBoolean("short")) {
                    HealthSteps.writeSleep(c, id, start, end, night.optString("stages"), showRem(c));
                }
            } catch (Exception e) {
                done.put(old);
            }
        }
        Store.put(root, "sleepNights", done);
        Store.put(root, "sleepModel", SleepStager.MODEL);
        Store.save(c);
    }

    /**
     * 0–100, from five parts that are always shown: duration (35), deep sleep (20), calm (20),
     * continuity (15) and regularity (10). Deep and calm are judged against your own past nights.
     */
    private static void score(Context c, JSONObject night, JSONArray past, double tst, double deepPct,
                              double movementIndex, int awakenings, double waso, long start) {
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
