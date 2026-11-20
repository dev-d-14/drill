package app.sthira;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

/**
 * The wake-up alarm. It is always an AlarmManager alarm clock (shown in the status bar, survives Doze),
 * so it rings even if tracking died in the night. The smart alarm fires by moving it to "now".
 */
public final class SleepAlarm extends BroadcastReceiver {

    static final String RING = "app.sthira.SLEEP_RING", SNOOZE = "app.sthira.SLEEP_SNOOZE",
            STOP = "app.sthira.SLEEP_STOP", STOP_TRACKING = "app.sthira.SLEEP_STOP_TRACKING";
    static final int REGULAR_SNOOZE_MIN = 9, MAX_LATE_SNOOZES = 3;

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (RING.equals(action)) {
            JSONObject s = Sleep.active(c);
            if (s != null && !s.has("firedAt")) {
                Store.put(s, "firedAt", System.currentTimeMillis());
                Store.put(s, "firedReason", "DEADLINE");
                Store.save(c);
            }
            JSONObject a = Store.root(c).optJSONObject("sleepAlarm");
            if (a != null) {
                Store.put(a, "ringing", true);
                Store.save(c);
            }
            try {
                Intent ring = new Intent(c, RingService.class);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(ring);
                else c.startService(ring);
            } catch (Exception e) {
                fallback(c);
            }
        } else if (SNOOZE.equals(action)) {
            RingService.snooze(c);
        } else if (STOP.equals(action)) {
            RingService.stopAlarm(c);
        } else if (STOP_TRACKING.equals(action)) {
            Sleep.stopNow(c, true);
        }
    }

    static PendingIntent ringIntent(Context c) {
        Intent i = new Intent(c, SleepAlarm.class).setAction(RING);
        return PendingIntent.getBroadcast(c, 300, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent action(Context c, String action, int code) {
        Intent i = new Intent(c, SleepAlarm.class).setAction(action);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Sets the alarm clock for `at`. `target` is the time the person asked for, kept for snoozing. */
    static void arm(Context c, long at, long target) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent show = new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN, MainActivity.SLEEP)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent showPi = PendingIntent.getActivity(c, 301, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), ringIntent(c));
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, ringIntent(c));
        }
        JSONObject a = Store.obj(Store.root(c), "sleepAlarm");
        if (a.optLong("target") != target) Store.put(a, "snoozes", 0);
        Store.put(a, "next", at);
        Store.put(a, "target", target);
        Store.put(a, "ringing", false);
        Store.save(c);
    }

    static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(ringIntent(c));
        Store.root(c).remove("sleepAlarm");
        Store.save(c);
    }

    /** Called by the tracker when it finds a light moment inside the window. */
    static void fireNow(Context c, String reason, char stage) {
        JSONObject s = Sleep.active(c);
        JSONObject a = Store.root(c).optJSONObject("sleepAlarm");
        if (s == null || s.has("firedAt") || a == null) return;
        long now = System.currentTimeMillis();
        Store.put(s, "firedAt", now);
        Store.put(s, "firedReason", reason);
        Store.put(s, "firedStage", String.valueOf(stage));
        Store.save(c);
        arm(c, now + 1000, a.optLong("target"));
    }

    /**
     * Intelligent snooze before the set time: each snooze is half the time left, and the last ring is
     * the set time itself. After the set time: regular 9-minute snoozes, three at most.
     * Returns the next ring time, or 0 when no more snoozing is allowed.
     */
    static long snoozeNext(Context c) {
        JSONObject a = Store.root(c).optJSONObject("sleepAlarm");
        if (a == null) return 0;
        long now = System.currentTimeMillis(), target = a.optLong("target", now);
        long next;
        if (now < target - 60_000) {
            next = now + Math.max(60_000, (target - now) / 2);
            if (target - next < 60_000) next = target;
        } else {
            int late = a.optInt("snoozes", 0);
            if (late >= MAX_LATE_SNOOZES) return 0;
            Store.put(a, "snoozes", late + 1);
            next = now + REGULAR_SNOOZE_MIN * 60_000L;
        }
        int snoozes = a.optInt("snoozes", 0);
        arm(c, next, target);
        Store.put(Store.obj(Store.root(c), "sleepAlarm"), "snoozes", snoozes);
        Store.save(c);
        return next;
    }

    static boolean canSnooze(Context c) {
        JSONObject a = Store.root(c).optJSONObject("sleepAlarm");
        if (a == null) return false;
        return System.currentTimeMillis() < a.optLong("target") - 60_000 || a.optInt("snoozes", 0) < MAX_LATE_SNOOZES;
    }

    /** If the ringing service can't start, at least post a loud alarm notification. */
    static void fallback(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            RingService.channel(nm);
            Intent full = new Intent(c, AlarmActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent fullPi = PendingIntent.getActivity(c, 302, full,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nm.notify(Sleep.NOTE_RING, new Notification.Builder(c, RingService.CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat).setContentTitle("Wake up").setContentText("Alarm")
                    .setCategory(Notification.CATEGORY_ALARM).setFullScreenIntent(fullPi, true)
                    .setContentIntent(fullPi).setOngoing(true).build());
        } catch (Exception ignored) {
        }
    }
}
