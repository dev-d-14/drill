package app.sthira;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

/** One-tap meditation timer: a ring that empties, one bell at the end, and it logs itself. */
final class MeditateScreen {

    private MeditateScreen() {
    }

    static View build(MainActivity a) {
        final JSONObject root = Store.root(a);
        final long endAt = root.optLong("medEnd", 0);
        final boolean running = endAt > 0;
        final int minutes = running ? root.optInt("medLen", 10) : Store.medMinutes(a);

        LinearLayout page = Ui.col(a);
        page.setBackground(new Ui.Ground());
        Ui.pad(page, 20, 16, 20, 28);

        LinearLayout top = Ui.row(a);
        top.addView(Ui.iconButton(a, Ui.I_LEFT, Ui.CREAM, true, "Back to Today", v -> a.open(MainActivity.TODAY)),
                Ui.lp(Ui.dp(46), Ui.dp(46)));
        TextView label = Ui.label(a, "meditate", Ui.JADE);
        label.setGravity(Gravity.CENTER);
        top.addView(label, Ui.lp(0, Ui.WRAP, 1));
        top.addView(new View(a), Ui.lp(Ui.dp(46), Ui.dp(46)));
        page.addView(top, Ui.lp(Ui.MATCH, Ui.WRAP));

        LinearLayout middle = Ui.col(a);
        middle.setGravity(Gravity.CENTER);
        FrameLayout ringBox = new FrameLayout(a);
        final Ui.Ring ring = new Ui.Ring(a);
        ringBox.addView(ring, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        LinearLayout inner = Ui.col(a);
        inner.setGravity(Gravity.CENTER);
        final TextView time = Ui.display(a, minutes + ":00", 80, Ui.CREAM);
        time.setFontFeatureSettings("tnum");
        time.setGravity(Gravity.CENTER);
        inner.addView(time, Ui.lp(Ui.MATCH, Ui.WRAP));
        final TextView sub = Ui.text(a, "one bell at the end", 13, Ui.MUTED, Ui.regular);
        sub.setGravity(Gravity.CENTER);
        inner.addView(sub, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
        FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        center.gravity = Gravity.CENTER;
        ringBox.addView(inner, center);
        middle.addView(ringBox, Ui.lp(Ui.dp(264), Ui.dp(264)));

        TextView line = Ui.text(a, running ? "Phone face down. Stay with the breath."
                : "Sit down. Phone face down. It logs itself when the bell rings.", 15, Ui.CREAM, Ui.regular);
        line.setGravity(Gravity.CENTER);
        middle.addView(line, Ui.gap(Ui.lp(Ui.dp(270), Ui.WRAP), 0, 26, 0, 0));

        if (!running) {
            LinearLayout chips = Ui.row(a);
            chips.setGravity(Gravity.CENTER);
            for (int m : new int[]{5, 10, 15, 20}) {
                boolean on = m == minutes;
                TextView chip = Ui.text(a, String.valueOf(m), 15, on ? Ui.BLACK : Ui.MUTED, Ui.bold);
                chip.setGravity(Gravity.CENTER);
                chip.setBackground(Ui.press(on ? Ui.fill(Ui.JADE, 4) : Ui.stroke(Ui.MUTED, 4, false), 4));
                chip.setContentDescription(m + " minutes");
                chip.setOnClickListener(v -> {
                    Store.setSetting(a, "medMin", m);
                    a.refresh();
                });
                chips.addView(chip, Ui.gap(Ui.lp(Ui.dp(60), Ui.dp(44)), 5, 0, 5, 0));
            }
            middle.addView(chips, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 24, 0, 0));
        }
        page.addView(middle, Ui.lp(Ui.MATCH, 0, 1));

        if (running) {
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            page.addView(Ui.ghost(a, "Stop without logging", Ui.MUTED, false, v -> {
                stop(a);
                a.refresh();
            }), Ui.lp(Ui.MATCH, Ui.dp(56)));
            tick(a, time, sub, ring, endAt, minutes);
        } else {
            a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            TextView begin = Ui.button(a, "BEGIN", Ui.JADE, Ui.BLACK, v -> {
                long end = System.currentTimeMillis() + minutes * 60_000L;
                Store.put(root, "medEnd", end);
                Store.put(root, "medLen", minutes);
                Store.save(a);
                Reminders.scheduleMeditationEnd(a, end);
                a.refresh();
            });
            begin.setTypeface(Ui.bebas);
            begin.setTextSize(28);
            begin.setLetterSpacing(0.06f);
            page.addView(begin, Ui.lp(Ui.MATCH, Ui.dp(60)));
        }
        return page;
    }

    private static void stop(MainActivity a) {
        Store.root(a).remove("medEnd");
        Store.save(a);
        Reminders.cancelMeditationEnd(a);
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private static void tick(MainActivity a, TextView time, TextView sub, Ui.Ring ring, long endAt, int minutes) {
        a.handler.post(new Runnable() {
            boolean seen;
            int waits;

            @Override
            public void run() {
                if (time.isAttachedToWindow()) seen = true;
                else if (seen || ++waits > 25) return;
                if (Store.root(a).optLong("medEnd", 0) != endAt) return; // stopped, or finished in the background
                long left = endAt - System.currentTimeMillis();
                if (left <= 0) {
                    Reminders.cancelMeditationEnd(a);
                    Store.logMeditation(a, minutes);
                    bell();
                    a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    ring.set(0f);
                    time.setText("DONE");
                    sub.setText("logged, " + minutes + " minutes");
                    a.handler.postDelayed(() -> {
                        if (a.current == MainActivity.MEDITATE && !a.isFinishing()) a.open(MainActivity.TODAY);
                    }, 2600);
                    return;
                }
                long s = (left + 999) / 1000;
                time.setText(String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60));
                ring.set(left / (minutes * 60_000f));
                a.handler.postDelayed(this, 200);
            }
        });
    }

    /** A soft struck-bowl tone, made in code so the app needs no sound file. */
    static void bell() {
        new Thread(() -> {
            try {
                int rate = 44100, n = rate * 5;
                short[] pcm = new short[n];
                for (int i = 0; i < n; i++) {
                    double t = i / (double) rate;
                    double wave = 0.60 * Math.sin(2 * Math.PI * 432 * t) * Math.exp(-t * 0.9)
                            + 0.25 * Math.sin(2 * Math.PI * 1188 * t) * Math.exp(-t * 1.6)
                            + 0.15 * Math.sin(2 * Math.PI * 2330 * t) * Math.exp(-t * 3.0);
                    double attack = Math.min(1.0, t / 0.01);
                    pcm[i] = (short) (wave * attack * 0.75 * Short.MAX_VALUE);
                }
                AudioTrack track = new AudioTrack.Builder()
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
                track.play();
                Thread.sleep(5300);
                track.release();
            } catch (Exception ignored) {
            }
        }, "bell").start();
    }
}
