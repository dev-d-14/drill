package app.sthira;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Rings the wake-up alarm: a soft chime that starts at 5% volume and rises over 90 seconds,
 * with a gentle vibration from the first minute. Gives up after 15 minutes.
 */
public final class RingService extends Service {

    static final String CHANNEL = "sleepalarm";
    static final long RAMP_MS = 90_000, VIBRATE_AFTER_MS = 60_000, GIVE_UP_MS = 15 * 60_000;
    static volatile RingService instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private AudioTrack track;
    private Vibrator vibrator;
    private PowerManager.WakeLock lock;
    private long started;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void channel(NotificationManager nm) {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Wake-up alarm", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, null);          // the service plays the sound itself, with a volume ramp
        ch.setBypassDnd(true);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        channel(nm);
        Intent full = new Intent(this, AlarmActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(this, 302, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Good morning")
                .setContentText("Wake-up alarm · " + Store.hhmm(Sleep.minuteOf(System.currentTimeMillis())))
                .setColor(Ui.MAROON)
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(fullPi, true)
                .setContentIntent(fullPi)
                .setOngoing(true);
        if (SleepAlarm.canSnooze(this)) {
            b.addAction(new Notification.Action.Builder(null, "Snooze",
                    SleepAlarm.action(this, SleepAlarm.SNOOZE, 303)).build());
        }
        b.addAction(new Notification.Action.Builder(null, "Stop", SleepAlarm.action(this, SleepAlarm.STOP, 304)).build());
        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Sleep.NOTE_RING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(Sleep.NOTE_RING, n);
            }
        } catch (Exception e) {
            SleepAlarm.fallback(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (instance == this && track != null) return START_NOT_STICKY;   // already ringing
        instance = this;

        // The night ends at the first ring; snoozes after that are not sleep.
        SleepService svc = SleepService.instance;
        if (svc != null) svc.finishNight(System.currentTimeMillis());
        else if (Sleep.active(this) != null) Sleep.finishNight(this, System.currentTimeMillis(), false);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sthira:ring");
            lock.acquire(GIVE_UP_MS + 60_000);
        }
        started = System.currentTimeMillis();
        play();
        h.post(ramp);
        return START_NOT_STICKY;
    }

    private final Runnable ramp = new Runnable() {
        boolean buzzing;

        @Override
        public void run() {
            long t = System.currentTimeMillis() - started;
            if (t > GIVE_UP_MS) {
                Sleep.notifyInfo(RingService.this, "Alarm stopped", "It rang for 15 minutes.");
                stopAlarm(RingService.this);
                return;
            }
            float v = 0.05f + 0.95f * Math.min(1f, t / (float) RAMP_MS);
            try {
                if (track != null) track.setVolume(v * v);   // squared, so the rise sounds even
            } catch (Exception ignored) {
            }
            if (!buzzing && t >= VIBRATE_AFTER_MS) {
                buzzing = true;
                try {
                    vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (vibrator != null) {
                        vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 1600}, 0),
                                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                    }
                } catch (Exception ignored) {
                }
            }
            h.postDelayed(this, 500);
        }
    };

    /** A rising five-note chime with a long tail, made in code and looped. */
    private void play() {
        try {
            int rate = 22050;
            double[] notes = {392.00, 493.88, 587.33, 739.99, 880.00};   // a bright, open chord, played upward
            double gap = 0.42, tail = 2.6;
            int n = (int) (rate * (notes.length * gap + tail));
            float[] mix = new float[n];
            for (int k = 0; k < notes.length; k++) {
                int off = (int) (k * gap * rate);
                for (int i = 0; off + i < n; i++) {
                    double t = i / (double) rate;
                    double env = Math.min(1, t / 0.006) * Math.exp(-t * 1.7);
                    double w = Math.sin(2 * Math.PI * notes[k] * t) + 0.3 * Math.sin(2 * Math.PI * notes[k] * 2.01 * t) * Math.exp(-t * 3);
                    mix[off + i] += (float) (w * env * 0.22);
                }
            }
            short[] pcm = new short[n];
            for (int i = 0; i < n; i++) pcm[i] = (short) (Math.max(-1, Math.min(1, mix[i])) * Short.MAX_VALUE);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(n * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(pcm, 0, n);
            track.setLoopPoints(0, n, -1);
            track.setVolume(0.0025f);
            track.play();
        } catch (Exception e) {
            SleepAlarm.fallback(this);
        }
    }

    private void silence() {
        h.removeCallbacks(ramp);
        try {
            if (track != null) {
                track.stop();
                track.release();
            }
        } catch (Exception ignored) {
        }
        track = null;
        try {
            if (vibrator != null) vibrator.cancel();
        } catch (Exception ignored) {
        }
        if (lock != null && lock.isHeld()) lock.release();
    }

    @Override
    public void onDestroy() {
        silence();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private static void quiet(Context c) {
        RingService r = instance;
        if (r != null) {
            r.silence();
            r.stopForeground(Service.STOP_FOREGROUND_REMOVE);
            r.stopSelf();
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(Sleep.NOTE_RING);
        AlarmActivity.closeAll();
    }

    /** Snoozes, or stops if no snooze is left. Returns the next ring time, or 0 if stopped. */
    static long snooze(Context c) {
        long next = SleepAlarm.snoozeNext(c);
        if (next == 0) {
            stopAlarm(c);
            return 0;
        }
        quiet(c);
        return next;
    }

    static void stopAlarm(Context c) {
        quiet(c);
        SleepAlarm.cancel(c);
        if (Sleep.active(c) != null) Sleep.stopNow(c, false);
    }
}
