package app.sthira;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.lang.ref.WeakReference;

/** The full-screen wake-up page over the lock screen: a big Snooze, and Stop that needs a one-second hold. */
public final class AlarmActivity extends Activity {

    private static WeakReference<AlarmActivity> current = new WeakReference<>(null);
    private final Handler h = new Handler(Looper.getMainLooper());

    static void closeAll() {
        AlarmActivity a = current.get();
        if (a != null && !a.isFinishing()) a.finish();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = new WeakReference<>(this);
        Ui.init(this);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout page = Ui.col(this);
        page.setBackground(new Ui.Ground());
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        Ui.pad(page, 24, 60, 24, 36);

        page.addView(Ui.label(this, "good morning", Ui.MUTED));
        TextView time = Ui.display(this, Store.hhmm(Sleep.minuteOf(System.currentTimeMillis())), 120, Ui.CREAM);
        time.setFontFeatureSettings("tnum");
        page.addView(time, Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 10, 0, 0));

        JSONObject last = Sleep.lastNight(this);
        String why = "";
        if (last != null && last.has("firedAt") && !"DEADLINE".equals(last.optString("firedReason"))) {
            long early = (last.optLong("target") - last.optLong("firedAt")) / 60_000;
            why = (early > 0 ? early + " min before your alarm, because " : "Woke you because ")
                    + Sleep.reasonText(last.optString("firedReason")) + ".";
        }
        if (last != null && last.optLong("tst") > 0) {
            why = (why.isEmpty() ? "" : why + "\n") + Sleep.hm(last.optLong("tst")) + " asleep · score " + last.optInt("score");
        }
        TextView line = Ui.text(this, why, 15, Ui.CREAM, Ui.regular);
        line.setGravity(Gravity.CENTER);
        page.addView(line, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 16, 0, 0));
        page.addView(new android.view.View(this), Ui.lp(Ui.MATCH, 0, 1));

        if (SleepAlarm.canSnooze(this)) {
            TextView snooze = Ui.button(this, "SNOOZE", Ui.CREAM, Ui.DEEP, v -> {
                long next = RingService.snooze(this);
                if (next > 0) {
                    Toast.makeText(this, "Ringing again at " + Store.hhmm(Sleep.minuteOf(next)), Toast.LENGTH_LONG).show();
                }
                finish();
            });
            snooze.setTypeface(Ui.bebas);
            snooze.setTextSize(30);
            page.addView(snooze, Ui.lp(Ui.MATCH, Ui.dp(120)));
        }

        TextView stop = Ui.ghost(this, "Hold to stop", Ui.CREAM, false, null);
        stop.setTextSize(17);
        final Runnable done = () -> {
            RingService.stopAlarm(this);
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            Intent open = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN, MainActivity.SLEEP)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (km != null && km.isKeyguardLocked() && Build.VERSION.SDK_INT >= 26) {
                km.requestDismissKeyguard(this, null);
            }
            startActivity(open);
            finish();
        };
        stop.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                stop.setText("Keep holding…");
                h.postDelayed(done, 1000);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                h.removeCallbacks(done);
                stop.setText("Hold to stop");
            }
            return true;
        });
        page.addView(stop, Ui.gap(Ui.lp(Ui.MATCH, Ui.dp(64)), 0, 14, 0, 0));
        setContentView(page);
    }

    @Override
    public void onBackPressed() {
        // Back does nothing here; snooze or stop.
    }
}
