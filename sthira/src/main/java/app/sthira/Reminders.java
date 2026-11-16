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

    static final String REMIND = "app.sthira.REMIND", MED_END = "app.sthira.MED_END";
    static final int DIGEST = 1, MEDITATE = 2, SHUTDOWN = 3, MED_DONE = 4;

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (REMIND.equals(action)) {
            remind(c, intent.getIntExtra("kind", 0));
            scheduleAll(c);
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
        }
    }

    private static void remind(Context c, int kind) {
        LocalDate today = LocalDate.now();
        if (kind == DIGEST) {
            if (Store.root(c).optBoolean("hideDigest", false) || Store.dayOrEmpty(c, today).optBoolean("digest", false)) return;
            post(c, DIGEST, "remind", "Today's digest is ready", "Five items, about four minutes.", MainActivity.DIGEST);
        } else if (kind == MEDITATE) {
            if (Store.meditated(c, today)) return;
            post(c, MEDITATE, "remind", "Time to sit", Store.medMinutes(c) + " minutes. One tap to start.", MainActivity.MEDITATE);
        } else if (kind == SHUTDOWN) {
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
            Notification n = new Notification.Builder(c, channel)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setColor(Ui.MAROON)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, id, open,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                    .build();
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
        String[] times = {Store.digestTime(c), Store.homeTime(c), Store.shutTime(c)};
        int[] kinds = {DIGEST, MEDITATE, SHUTDOWN};
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 3; i++) {
            int m = Store.minutes(times[i]);
            LocalDateTime at = LocalDate.now().atTime(m / 60, m % 60);
            if (!at.isAfter(now.plusSeconds(5))) at = at.plusDays(1);
            set(c, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), alarm(c, REMIND, kinds[i]));
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
