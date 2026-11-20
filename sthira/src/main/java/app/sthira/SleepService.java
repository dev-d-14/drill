package app.sthira;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.Iterator;

/**
 * Listens through the night. Started only by a tap on "Start sleep" (Android does not let a
 * microphone service start from the background). Nothing but the per-epoch numbers is written:
 * no audio is ever stored.
 */
public final class SleepService extends Service {

    static volatile SleepService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread thread;
    private PowerManager.WakeLock lock;
    private FileWriter out;
    private SleepDsp dsp;
    private long id, start, target;
    private int windowMin;
    private String mode;
    private boolean finished;
    private volatile boolean fired;
    private int lastEpochIndex = -1;
    private long lastDeepMs;
    private volatile double lastReg = -1;

    private final BroadcastReceiver unlock = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            SleepDsp d = dsp;
            if (d != null) d.markPhoneUse();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static double level() {
        SleepService s = instance;
        return s == null || s.dsp == null ? 0 : s.dsp.level;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        JSONObject s = Sleep.active(this);
        Notification n = notification(s);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(Sleep.NOTE_TRACK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(Sleep.NOTE_TRACK, n);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (s == null || running) {
            if (s == null) stopSelf();
            return START_NOT_STICKY;
        }
        instance = this;
        id = s.optLong("id");
        start = s.optLong("start");
        target = s.optLong("target");
        windowMin = s.optInt("window");
        mode = s.optString("mode");

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sthira:sleep");
            lock.acquire(14 * 3_600_000L);
        }
        IntentFilter f = new IntentFilter(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlock, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(unlock, f);

        try {
            out = new FileWriter(Sleep.epochFile(this, id), true);
        } catch (Exception e) {
            out = null;
        }
        dsp = new SleepDsp(this::onEpoch);
        running = true;
        thread = new Thread(this::capture, "sleep-capture");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
        return START_NOT_STICKY;
    }

    private Notification notification(JSONObject s) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("sleep", "Sleep tracking", NotificationManager.IMPORTANCE_LOW));
        String text = "Listening. No audio is saved.";
        if (s != null && s.optLong("target") > 0) {
            int t = Sleep.minuteOf(s.optLong("target")), w = s.optInt("window");
            text = w > 0 ? "Alarm " + Store.hhmm(t) + " · wakes you from " + Store.hhmm(t - w) + " when you're in light sleep"
                    : "Alarm " + Store.hhmm(t);
        }
        Intent open = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN, MainActivity.SLEEP)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return new Notification.Builder(this, "sleep")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Sleep tracking")
                .setContentText(text)
                .setColor(Ui.MAROON)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 305, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null, "Stop tracking",
                        SleepAlarm.action(this, SleepAlarm.STOP_TRACKING, 306)).build())
                .build();
    }

    // ---- capture thread ----

    private AudioRecord open() {
        int min = AudioRecord.getMinBufferSize(SleepDsp.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min * 2, SleepDsp.FRAME * 8);
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean unprocessed = am != null
                && "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int[] sources = unprocessed
                ? new int[]{MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC}
                : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            try {
                AudioRecord r = new AudioRecord(src, SleepDsp.RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, size);
                if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                    r.release();
                    continue;
                }
                // Gain control and noise suppression would erase the quiet rustles we need.
                try {
                    if (AutomaticGainControl.isAvailable()) {
                        AutomaticGainControl g = AutomaticGainControl.create(r.getAudioSessionId());
                        if (g != null) g.setEnabled(false);
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        NoiseSuppressor ns = NoiseSuppressor.create(r.getAudioSessionId());
                        if (ns != null) ns.setEnabled(false);
                    }
                } catch (Exception ignored) {
                }
                return r;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void capture() {
        short[] buf = new short[SleepDsp.FRAME];
        AudioRecord rec = null;
        int checks = 0;
        while (running) {
            if (rec == null) {
                rec = open();
                if (rec == null) {
                    if (!pause(60_000)) break;
                    continue;
                }
                try {
                    rec.startRecording();
                } catch (Exception e) {
                    rec.release();
                    rec = null;
                    if (!pause(60_000)) break;
                    continue;
                }
            }
            int got = 0;
            while (got < buf.length && running) {
                int r = rec.read(buf, got, buf.length - got);
                if (r <= 0) {
                    got = -1;
                    break;
                }
                got += r;
            }
            if (!running) break;
            if (got < 0) {
                // The microphone was taken (a call, another app). Try again in a minute; the alarm is unaffected.
                try {
                    rec.stop();
                } catch (Exception ignored) {
                }
                rec.release();
                rec = null;
                if (!pause(60_000)) break;
                continue;
            }
            long now = System.currentTimeMillis();
            dsp.frame(buf, got, now);
            if (++checks >= 100) {      // every 5 seconds
                checks = 0;
                smartCheck(now);
            }
        }
        if (rec != null) {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            rec.release();
        }
    }

    private boolean pause(long ms) {
        long until = System.currentTimeMillis() + ms;
        while (running && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return running;
    }

    /** Called on the capture thread for each 30-second epoch. */
    private void onEpoch(SleepDsp.Epoch e) {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (km != null && pm != null && pm.isInteractive() && !km.isKeyguardLocked()) e.phone = true;
        if (e.phone) e.online = 'W';
        if (e.online == 'D') lastDeepMs = e.startMs;
        lastReg = e.breathConf >= 0.3 && e.breathReg >= 0 ? e.breathReg : -1;
        int index = (int) ((e.startMs - start) / 30_000L);
        if (index <= lastEpochIndex) return;
        lastEpochIndex = index;
        try {
            if (out != null) {
                out.write(e.csv());
                out.write('\n');
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    // ---- the smart alarm ----

    /**
     * Inside the wake window, every 5 seconds: wake on a big movement in light sleep, on a cluster of
     * movements, when you're plainly awake, or when the night has gone light. Never out of deep sleep.
     */
    private void smartCheck(long now) {
        if (fired || !Sleep.MODE_SMART.equals(mode) || target <= 0 || windowMin <= 0) return;
        if (now < target - windowMin * 60_000L || now >= target - 30_000) return;
        if (now - start < 20 * 60_000L) return;                       // too little night to judge
        SleepDsp d = dsp;
        int n = d.stages.size();
        if (n == 0) return;
        char last = d.stages.get(n - 1);
        boolean deepNow = n >= 3 && d.stages.get(n - 1) == 'D' && d.stages.get(n - 2) == 'D' && d.stages.get(n - 3) == 'D';
        if (deepNow) return;

        String reason = null;
        int bigLately = 0, majorJustNow = 0;
        for (Iterator<SleepDsp.Event> it = d.recent.descendingIterator(); it.hasNext(); ) {
            SleepDsp.Event ev = it.next();
            if (now - ev.endMs > 120_000) break;
            if (ev.kind != SleepDsp.MICRO) bigLately++;
            if (ev.kind == SleepDsp.MAJOR && now - ev.endMs <= 6000) majorJustNow++;
        }
        if (last == 'W') reason = "AWAKE";
        else if (majorJustNow > 0) reason = "MAJOR_MOVEMENT";
        else if (bigLately >= 3) reason = "RESTLESS";
        else {
            double dd = d.onlineD();
            // the latest breathing regularity, if the breathing could be heard; otherwise neutral
            double reg = lastReg >= 0 ? lastReg : 0.5;
            double lightness = 0.5 * Math.min(1, dd / 0.8) + 0.3 * (1 - reg)
                    + 0.2 * (now - lastDeepMs >= 10 * 60_000L ? 1 : 0);
            if (lightness >= 0.7) reason = "LIGHTNESS";
        }
        if (reason == null) return;
        fired = true;
        final String r = reason;
        final char stage = last;
        main.post(() -> SleepAlarm.fireNow(this, r, stage));
    }

    // ---- ending ----

    /** Stops listening and wraps up the night. Safe to call more than once, from any thread. */
    void finishNight(long endMs) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> finishNight(endMs));
            return;
        }
        if (finished) return;
        finished = true;
        stopCapture();
        if (Sleep.active(this) != null) Sleep.finishNight(this, endMs, false);
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopCapture() {
        running = false;
        if (thread != null) {
            try {
                thread.join(3000);
            } catch (InterruptedException ignored) {
            }
        }
        try {
            if (out != null) out.close();
        } catch (Exception ignored) {
        }
        out = null;
        try {
            unregisterReceiver(unlock);
        } catch (Exception ignored) {
        }
        if (lock != null && lock.isHeld()) lock.release();
    }

    @Override
    public void onDestroy() {
        if (!finished) {
            finished = true;
            stopCapture();
            // Killed in the night: keep what was recorded. The alarm clock is armed separately.
            if (Sleep.active(this) != null) {
                File f = Sleep.epochFile(this, id);
                long end = f.exists() ? f.lastModified() : System.currentTimeMillis();
                Sleep.finishNight(this, Math.max(end, start), true);
            }
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
