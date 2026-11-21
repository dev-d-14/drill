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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** The three daily nudges (digest, sit, shutdown) and the backup bell for the meditation timer. */
public final class Reminders extends BroadcastReceiver {

    static final String REMIND = "app.sthira.REMIND", MED_END = "app.sthira.MED_END", VIT_TAKEN = "app.sthira.VIT_TAKEN",
            REST = "app.sthira.REST", STEPS = "app.sthira.STEPS";
    static final int DIGEST = 1, MEDITATE = 2, SHUTDOWN = 3, MED_DONE = 4, VITAMIN = 5, TRAIN = 6, REST_DONE = 7, STEPS_EOD = 8, DIGEST_RETRY = 9;

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (REMIND.equals(action)) {
            int kind = intent.getIntExtra("kind", 0);
            if (kind == DIGEST || kind == DIGEST_RETRY) {
                // Check the list really is today's before announcing it; if not yet, look again in 30 minutes.
                PendingResult pr = goAsync();
                new Thread(() -> {
                    try {
                        if (!Store.root(c).optBoolean("hideDigest", false)
                                && !Store.dayOrEmpty(c, LocalDate.now()).optBoolean("digest", false)) {
                            if (DigestScreen.todayReady(c)) remind(c, DIGEST);
                            else retryDigest(c);
                        }
                        scheduleAll(c);
                    } finally {
                        pr.finish();
                    }
                }, "digest-remind").start();
                return;
            }
            remind(c, kind);
            scheduleAll(c);
        } else if (REST.equals(action)) {
            long end = Store.root(c).optLong("restEnd", 0);
            if (end > 0 && end <= System.currentTimeMillis() + 2000) {
                post(c, REST_DONE, "timer", "Rest over", "Next set.", MainActivity.TRAIN);
            }
        } else if (STEPS.equals(action)) {
            // A reading just before midnight, so the evening's steps land on the right day.
            PendingResult pr = goAsync();
            Steps.sample(c, () -> {
                scheduleSteps(c);
                pr.finish();
            });
        } else if (VIT_TAKEN.equals(action)) {
            Store.setVitamin(c, true);
        } else if (MED_END.equals(action)) {
            JSONObject root = Store.root(c);
            long end = root.optLong("medEnd", 0);
            if (end > 0 && end <= System.currentTimeMillis()) {
                int minutes = root.optInt("medLen", 10);
                Store.logMeditation(c, minutes);
                post(c, MED_DONE, "timer", "Meditation done", minutes + " minutes logged.", MainActivity.TODAY);
            }
        } else {
            scheduleAll(c);
            AgendaWidget.updateAll(c);
            if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                Sleep.onBoot(c);
                Steps.start(c);
                Backup.schedule(c);
            }
        }
    }

    private static void remind(Context c, int kind) {
        LocalDate today = LocalDate.now();
        if (kind == DIGEST) {
            if (Store.root(c).optBoolean("hideDigest", false) || Store.dayOrEmpty(c, today).optBoolean("digest", false)) return;
            post(c, DIGEST, "remind", "Today's digest is ready", "The whole list, a few minutes.", MainActivity.DIGEST);
        } else if (kind == MEDITATE) {
            if (Store.meditated(c, today)) return;
            post(c, MEDITATE, "remind", "Time to sit", Store.medMinutes(c) + " minutes. One tap to start.", MainActivity.MEDITATE);
        } else if (kind == VITAMIN) {
            // One nudge in the evening if it hasn't been taken. If it's still not taken after that, let it go.
            if (Store.vitamin(c, today)) return;
            post(c, VITAMIN, "remind", "Multivitamin", "Not taken today. Take it with dinner.", MainActivity.TODAY);
        } else if (kind == TRAIN) {
            // Only on the plan's training days, and only if nothing is logged yet.
            if (!Store.isTrainingDay(c, today) || Store.sessionDone(c) || !Store.trained(c, today).isEmpty()) return;
            org.json.JSONObject w = Store.nextWorkout(c);
            String name = w == null ? "Training" : w.optString("name", "Training");
            post(c, TRAIN, "remind", "Time to train: " + name, "Open it and tap the first set. Even half a session counts.",
                    MainActivity.TRAIN);
        } else if (kind == SHUTDOWN) {
            // The safety net for the morning: the alarm arms itself now even if Start never gets tapped.
            Sleep.armBackup(c);
            if (Store.shutDown(c, today)) return;
            post(c, SHUTDOWN, "remind", "Shutdown", "A few taps, then the phone goes on the charger.", MainActivity.SHUTDOWN);
        }
    }

    private static void post(Context c, int id, String channel, String title, String text, int screen) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel("remind", "Daily reminders", NotificationManager.IMPORTANCE_DEFAULT));
            nm.createNotificationChannel(new NotificationChannel("timer", "Meditation timer", NotificationManager.IMPORTANCE_HIGH));
            Intent open = new Intent(c, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN, screen)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Notification.Builder builder = new Notification.Builder(c, channel)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setColor(Ui.MAROON)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, id, open,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            if (id == VITAMIN) {
                Intent took = new Intent(c, Reminders.class).setAction(VIT_TAKEN);
                builder.addAction(new Notification.Action.Builder(null, "Took it",
                        PendingIntent.getBroadcast(c, 100 + VITAMIN, took,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
                // Gone by midnight-ish; there's no second nudge.
                builder.setTimeoutAfter(3 * 60 * 60_000L);
            }
            if (id == REST_DONE) builder.setTimeoutAfter(2 * 60_000L);
            Notification n = builder.build();
            nm.notify(id, n);
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent alarm(Context c, String action, int kind) {
        Intent i = new Intent(c, Reminders.class).setAction(action).putExtra("kind", kind);
        return PendingIntent.getBroadcast(c, kind, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void set(Context c, long at, PendingIntent pi) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception e) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Exception ignored) {
            }
        }
    }

    /** Arms each daily reminder for the next time its clock time comes round. */
    static void scheduleAll(Context c) {
        String[] times = {Store.digestTime(c), Store.homeTime(c), Store.shutTime(c), Store.vitTime(c), Store.trainTime(c)};
        int[] kinds = {DIGEST, MEDITATE, SHUTDOWN, VITAMIN, TRAIN};
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < times.length; i++) {
            int m = Store.minutes(times[i]);
            LocalDateTime at = LocalDate.now().atTime(m / 60, m % 60);
            if (!at.isAfter(now.plusSeconds(5))) at = at.plusDays(1);
            set(c, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), alarm(c, REMIND, kinds[i]));
        }
        scheduleSteps(c);
    }

    /** Another look in 30 minutes, until four hours after the usual time. */
    private static void retryDigest(Context c) {
        int m = Store.minutes(Store.digestTime(c));
        LocalDateTime giveUp = LocalDate.now().atTime(m / 60, m % 60).plusHours(4);
        LocalDateTime next = LocalDateTime.now().plusMinutes(30);
        if (next.isAfter(giveUp)) return;
        set(c, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), alarm(c, REMIND, DIGEST_RETRY));
    }

    static void scheduleRest(Context c, long endAt) {
        set(c, endAt, alarm(c, REST, REST_DONE));
    }

    static void cancelRest(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarm(c, REST, REST_DONE));
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(REST_DONE);
    }

    /** 23:58 every night: one step reading so the day's total closes on the right date. Inexact is fine. */
    static void scheduleSteps(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        LocalDateTime at = LocalDate.now().atTime(23, 58);
        if (!at.isAfter(LocalDateTime.now().plusSeconds(5))) at = at.plusDays(1);
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    alarm(c, STEPS, STEPS_EOD));
        } catch (Exception ignored) {
        }
    }

    /** A few seconds after the timer ends, in case the app is not on screen to ring the bell itself. */
    static void scheduleMeditationEnd(Context c, long endAt) {
        set(c, endAt + 3000, alarm(c, MED_END, MED_DONE));
    }

    static void cancelMeditationEnd(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarm(c, MED_END, MED_DONE));
    }
}
