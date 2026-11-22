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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecordingConfiguration;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.FileWriter;
import java.util.Iterator;

/**
 * Listens through the night. Nothing but the per-epoch numbers is written: no audio is ever stored.
 *
 * Staying alive: the service is sticky and a watchdog alarm (Sleep.onWatch) restarts it if the phone kills it.
 * Android doesn't let a restarted background service use the microphone, so after a restart it carries on with
 * the accelerometer ("motion" epochs) and takes the microphone back the next time the app is opened.
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
    private volatile double lastReg = -1;
    private volatile boolean micOk;      // started with the microphone foreground type, so recording is allowed
    private volatile boolean motion;     // currently measuring with the accelerometer

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

    boolean motionOnly() {
        return running && !micOk;
    }

    /**
     * Goes foreground with the best type allowed right now: microphone (+ health) when the app is in front,
     * health alone (accelerometer) when started from the background by the watchdog, a sticky restart or boot.
     * Returns false if no foreground type could be used at all.
     */
    private boolean goForeground(JSONObject s) {
        if (Build.VERSION.SDK_INT >= 34) {
            int mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, health = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
            int[] types = {mic | health, mic, health};
            for (int t : types) {
                try {
                    boolean withMic = (t & mic) != 0;
                    startForeground(Sleep.NOTE_TRACK, notification(s, !withMic), t);
                    micOk = withMic;
                    return true;
                } catch (Exception ignored) {
                }
            }
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(Sleep.NOTE_TRACK, notification(s, false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(Sleep.NOTE_TRACK, notification(s, false));
            micOk = true;   // older Android: allowed, but a background start may be silenced; capture checks that
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        JSONObject s = Sleep.active(this);
        if (running) {
            // Already tracking. If it's on the motion sensor, try to take the microphone back (works when the app
            // is in front); the capture loop notices micOk and switches.
            if (s != null && !micOk && goForeground(s) && micOk) motion = false;
            return START_STICKY;
        }
        if (s == null || !Sleep.stillNight(s)) {
            goForeground(s);   // must go foreground once after startForegroundService, even just to stop
            if (s != null) Sleep.finishNight(this, System.currentTimeMillis(), true);
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!goForeground(s)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        instance = this;
        fired = s.has("firedAt");
        motion = !micOk;
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
        dsp.nightStartMs = start;
        running = true;
        thread = new Thread(this::capture, "sleep-capture");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
        Sleep.scheduleWatch(this, Sleep.WATCH_MINUTES * 60_000L);
        // Sticky: if the phone kills the process, Android starts the service again (on the motion sensor).
        return START_STICKY;
    }

    private Notification notification(JSONObject s, boolean motionMode) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("sleep", "Sleep tracking", NotificationManager.IMPORTANCE_LOW));
        String text = motionMode ? "Tracking by motion after a restart. Open Sthira to switch the microphone back on."
                : "Listening. No audio is saved.";
        if (!motionMode && s != null && s.optLong("target") > 0) {
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
            if (motion || !micOk) {
                if (rec != null) {
                    try {
                        rec.stop();
                    } catch (Exception ignored) {
                    }
                    rec.release();
                    rec = null;
                }
                motion = true;
                runMotion();       // returns when stopped, when the microphone is back, or every 5 min to retry it
                if (micOk) motion = false;
                continue;
            }
            if (rec == null) {
                rec = open();
                if (rec == null) {
                    motion = true;     // no microphone right now: keep tracking by motion meanwhile
                    continue;
                }
                try {
                    rec.startRecording();
                } catch (Exception e) {
                    rec.release();
                    rec = null;
                    motion = true;
                    continue;
                }
                if (silenced(rec)) {
                    // Android hands a background-started recorder silence; zeros would read as deep sleep.
                    try {
                        rec.stop();
                    } catch (Exception ignored) {
                    }
                    rec.release();
                    rec = null;
                    micOk = false;
                    motion = true;
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
                motion = true;
                continue;
            }
            long now = System.currentTimeMillis();
            dsp.frame(buf, got, now);
            if (++checks >= 100) {      // every 5 seconds
                checks = 0;
                if (silenced(rec)) {
                    // Silenced mid-night (privacy toggle, or the system took it): switch to motion now rather than
                    // writing zeros that would read as deep sleep. The microphone is retried every 5 minutes.
                    try {
                        rec.stop();
                    } catch (Exception ignored) {
                    }
                    rec.release();
                    rec = null;
                    motion = true;
                    continue;
                }
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

    private static boolean silenced(AudioRecord rec) {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            AudioRecordingConfiguration conf = rec.getActiveRecordingConfiguration();
            return conf != null && conf.isClientSilenced();
        } catch (Exception e) {
            return false;
        }
    }

    // ---- motion fallback ----

    /**
     * Tracks with the accelerometer (works with the phone on the mattress) until the service stops, the microphone
     * comes back, or, while the microphone type is held, five minutes pass so the microphone can be retried.
     */
    private void runMotion() {
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) {
            pause(60_000);
            return;
        }
        HandlerThread ht = new HandlerThread("sleep-motion");
        ht.start();
        Motion m = new Motion();
        SensorEventListener l = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent ev) {
                // sensor time → wall clock, so batched readings land in the right epoch
                long wall = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - ev.timestamp) / 1_000_000L;
                m.add(ev.values, wall);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        sm.registerListener(l, acc, 100_000, 10_000_000, new Handler(ht.getLooper()));
        long until = micOk ? System.currentTimeMillis() + 5 * 60_000L : Long.MAX_VALUE;
        while (running && motion && System.currentTimeMillis() < until) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }
        sm.unregisterListener(l);
        ht.quitSafely();
    }

    /** Turns 10 Hz accelerometer readings into 30-second epochs on the same grid as the microphone's. */
    private final class Motion {
        private double gravity = -1, secMax, sum, peak;
        private long sec = -1;
        private int index = -1, activeSec, micro, medium, major;

        void add(float[] v, long t) {
            double mag = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            if (gravity < 0) gravity = mag;
            gravity += 0.05 * (mag - gravity);
            double dev = Math.abs(mag - gravity);
            long s = t / 1000;
            if (s != sec) {
                closeSecond();
                sec = s;
                secMax = 0;
            }
            secMax = Math.max(secMax, dev);
            int i = (int) ((t - start) / 30_000L);
            if (i != index) {
                closeEpoch();
                index = i;
            }
        }

        private void closeSecond() {
            if (sec < 0) return;
            if (secMax >= 0.1) activeSec++;
            if (secMax >= 1.5) major++;
            else if (secMax >= 0.4) medium++;
            else if (secMax >= 0.1) micro++;
            sum += secMax;
            peak = Math.max(peak, secMax);
        }

        private void closeEpoch() {
            if (index >= 0) {
                SleepDsp.Epoch e = new SleepDsp.Epoch();
                e.startMs = start + index * 30_000L;
                e.motion = true;
                e.activeSec = activeSec;
                e.micro = micro;
                e.medium = medium;
                e.major = major;
                e.events = micro + medium + major;
                e.maxPeak = peak * 10;
                e.activity = sum * 10;
                e.online = activeSec >= 10 ? 'W' : 'L';
                onEpoch(e);
            }
            activeSec = micro = medium = major = 0;
            sum = peak = 0;
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
     * Inside the wake window, every 5 seconds: wake when you're plainly awake, on a big movement, on a cluster of
     * movements, or on the first signs of light sleep (smaller movements, uneven breathing). Never out of deep sleep,
     * but "deep" now only counts where deep sleep is plausible (the first few hours), so a still sleeper late in the
     * night is no longer mistaken for one in deep sleep all the way to the deadline.
     */
    private void smartCheck(long now) {
        if (fired || !Sleep.MODE_SMART.equals(mode) || target <= 0 || windowMin <= 0) return;
        long windowStart = target - windowMin * 60_000L;
        if (now < windowStart || now >= target - 30_000) return;
        if (now - start < 20 * 60_000L) return;                       // too little night to judge
        SleepDsp d = dsp;
        int n = d.stages.size();
        if (n == 0) return;
        char last = d.stages.get(n - 1);
        boolean deepNow = n >= 3 && d.stages.get(n - 1) == 'D' && d.stages.get(n - 2) == 'D' && d.stages.get(n - 3) == 'D';
        if (deepNow) return;

        int bigLately = 0, majorJustNow = 0, mediumLately = 0, microLately = 0;
        for (Iterator<SleepDsp.Event> it = d.recent.descendingIterator(); it.hasNext(); ) {
            SleepDsp.Event ev = it.next();
            if (now - ev.endMs > 120_000) break;
            if (ev.kind != SleepDsp.MICRO) bigLately++;
            if (ev.kind == SleepDsp.MAJOR && now - ev.endMs <= 6000) majorJustNow++;
            if (ev.kind == SleepDsp.MEDIUM && now - ev.endMs <= 60_000) mediumLately++;
            if (ev.kind == SleepDsp.MICRO) microLately++;
        }
        // In the last third of the window, a single small movement is enough.
        boolean lateInWindow = now >= target - windowMin * 60_000L / 3;
        String reason = null;
        if (last == 'W') reason = "AWAKE";
        else if (majorJustNow > 0) reason = "MAJOR_MOVEMENT";
        else if (bigLately >= 2) reason = "RESTLESS";
        else if (mediumLately > 0 || microLately >= 2 || (lateInWindow && microLately >= 1)) reason = "LIGHTNESS";
        else if (lastReg >= 0 && lastReg < 0.6) reason = "LIGHTNESS";   // breathing has gone uneven
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
            // Stopped by the system in the night: the night stays open and the watchdog restarts tracking in a minute.
            // The alarm clock is armed separately and doesn't depend on this service.
            if (Sleep.active(this) != null) Sleep.scheduleWatch(this, 60_000L);
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
