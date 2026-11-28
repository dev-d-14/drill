package app.sthira;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.Locale;

/** Today's workout: tap a box to log a set, rest timer pinned at the bottom. */
final class TrainScreen {

    // The rest end is kept in the store as wall-clock time, so it survives the app being killed, and an alarm
    // (Reminders.REST) rings it when the screen is off or the app isn't in front.
    private static boolean restBeeped = true;

    private static long restEnd(MainActivity a) {
        return Store.root(a).optLong("restEnd", 0);
    }

    private static void setRest(MainActivity a, long end) {
        if (end > 0) {
            Store.put(Store.root(a), "restEnd", end);
            Reminders.scheduleRest(a, end);
        } else {
            Store.root(a).remove("restEnd");
            Reminders.cancelRest(a);
        }
        Store.save(a);
    }

    private TrainScreen() {
    }

    static View build(MainActivity a) {
        if (Plyo.on(a)) return Plyo.buildList(a);
        JSONObject plan = Store.activePlan(a);
        JSONObject workout = Store.nextWorkout(a);
        boolean done = Store.sessionDone(a);
        String label = plan == null ? "no plan" : plan.optString("name") + " · week " + Store.weeksOnPlan(a);
        boolean restDay = !done && workout != null && !Store.isTrainingDay(a, LocalDate.now());
        String title = done ? "DONE" : workout == null ? "TRAIN" : restDay ? "REST DAY"
                : workout.optString("name").toUpperCase(Locale.ROOT);
        View plans = Ui.ghost(a, "Plans", Ui.CREAM, false, v -> a.open(MainActivity.PLANS));
        LinearLayout body = Ui.col(a);
        body.addView(Plyo.modes(a), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 12));
        body.addView(Steps.card(a), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 12));

        if (workout == null) {
            body.addView(Ui.text(a, "This plan has no workouts yet. Add one in Plans.", 14, Ui.CREAM, Ui.regular));
            return Ui.page(a, Ui.header(a, label, title, plans), body);
        }
        if (done) {
            LinearLayout card = Ui.card(a, Ui.BLACK, null);
            card.addView(Ui.titled(a, Store.session(a).optString("name").toUpperCase(Locale.ROOT) + " LOGGED",
                    "Next in the rotation: " + workout.optString("name"), Ui.CREAM, Ui.MUTED), Ui.lp(0, Ui.WRAP, 1));
            body.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP));
            body.addView(Ui.text(a, "Your history follows each exercise, so it carries over if you change plans.",
                    13, Ui.CREAM, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 12, 0, 0));
            return Ui.page(a, Ui.header(a, label, title, plans), body);
        }

        if (!Store.isTrainingDay(a, LocalDate.now())) {
            body.addView(Ui.text(a, "Nothing planned today. If you want to train anyway, " + workout.optString("name")
                            + " is next in the rotation:", 13, Ui.CREAM, Ui.regular),
                    Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 8));
        }
        body.addView(Ui.text(a, "Tap a box to log the reps. Stop one or two short of failure.", 13, Ui.CREAM, Ui.regular),
                Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 4));

        final TextView restText = Ui.display(a, "READY", 28, Ui.BLACK);
        JSONArray exercises = workout.optJSONArray("ex");
        for (int i = 0; exercises != null && i < exercises.length(); i++) {
            final int index = i;
            JSONObject ex = exercises.optJSONObject(i);
            final String name = ex.optString("name");
            final int sets = Math.max(1, ex.optInt("sets", 3)), lo = ex.optInt("lo", 8), hi = ex.optInt("hi", 12);
            final JSONArray last = Store.lastReps(a, name);

            LinearLayout card = Ui.card(a, Ui.BLACK, null);
            LinearLayout texts = Ui.col(a);
            texts.addView(Ui.text(a, name, 16, Ui.CREAM, Ui.bold));
            String sub = sets + " × " + lo + "–" + hi + (last == null ? "" : " · last " + Store.join(last));
            texts.addView(Ui.text(a, sub, 12, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 3, 0, 0));
            boolean topped = last != null && last.length() >= sets;
            for (int s = 0; topped && s < sets; s++) if (last.optInt(s) < hi) topped = false;
            if (topped) {
                texts.addView(Ui.text(a, "Top of the range last time. Make it harder: slower, more reps or added weight.",
                        12, Ui.JADE, Ui.regular), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 3, 0, 0));
            }
            card.addView(texts, Ui.lp(0, Ui.WRAP, 1));

            for (int s = 0; s < sets; s++) {
                final int set = s;
                final TextView box = Ui.text(a, "", 16, Ui.BLACK, Ui.bold);
                box.setGravity(Gravity.CENTER);
                paint(box, Store.sessionReps(a, index).optInt(set, 0));
                box.setContentDescription(name + ", set " + (set + 1));
                box.setOnClickListener(v -> {
                    int now = Store.sessionReps(a, index).optInt(set, 0);
                    int start = now > 0 ? now : last != null && last.optInt(set, 0) > 0 ? last.optInt(set) : lo;
                    Ui.pickNumber(a, name + ", set " + (set + 1), 0, 120, start, value -> {
                        Store.setRep(a, index, set, value);
                        paint(box, value);
                        if (value > 0) {
                            restBeeped = false;
                            setRest(a, System.currentTimeMillis() + Store.restSeconds(a) * 1000L);
                        }
                    });
                });
                card.addView(box, Ui.gap(Ui.lp(Ui.dp(44), Ui.dp(44)), 6, 0, 0, 0));
            }
            body.addView(card, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        }

        body.addView(Ui.button(a, "Finish session", Ui.JADE, Ui.BLACK, v -> {
            JSONObject reps = Store.obj(Store.session(a), "reps");
            boolean any = false;
            for (java.util.Iterator<String> it = reps.keys(); it.hasNext(); ) {
                JSONArray r = reps.optJSONArray(it.next());
                for (int i = 0; r != null && i < r.length(); i++) if (r.optInt(i) > 0) any = true;
            }
            if (!any) {
                Toast.makeText(a, "Log at least one set first.", Toast.LENGTH_SHORT).show();
                return;
            }
            setRest(a, 0);
            Store.finishSession(a);
            a.refresh();
        }), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 14, 0, 0));

        LinearLayout page = Ui.page(a, Ui.header(a, label, title, plans), body);
        LinearLayout rest = Ui.row(a);
        rest.setBackgroundColor(Ui.OCHRE);
        Ui.pad(rest, 20, 8, 20, 8);
        rest.addView(Ui.label(a, "rest", Ui.BLACK), Ui.lp(0, Ui.WRAP, 1));
        rest.addView(restText);
        page.addView(rest, Ui.lp(Ui.MATCH, Ui.WRAP));
        tick(a, restText);
        return page;
    }

    private static void paint(TextView box, int reps) {
        if (reps > 0) {
            box.setText(String.valueOf(reps));
            box.setTextColor(Ui.BLACK);
            box.setBackground(Ui.press(Ui.fill(Ui.JADE, 4), 4));
        } else {
            box.setText("+");
            box.setTextColor(Ui.CREAM);
            box.setBackground(Ui.press(Ui.stroke(Ui.MUTED, 4, false), 4));
        }
    }

    /** Updates the rest countdown four times a second for as long as its view is on screen. */
    private static void tick(MainActivity a, TextView restText) {
        a.handler.postDelayed(new Runnable() {
            boolean seen;
            int waits;

            @Override
            public void run() {
                if (restText.isAttachedToWindow()) seen = true;
                else if (seen || ++waits > 20) return;
                long end = restEnd(a), left = end - System.currentTimeMillis();
                if (end == 0) {
                    restText.setText("READY");
                } else if (left > 0) {
                    restBeeped = false;
                    long s = (left + 999) / 1000;
                    restText.setText(String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60));
                } else {
                    restText.setText("GO");
                    if (left < -5000) restBeeped = true;   // ended a while ago: the notification already rang
                    // Beep here only when the screen is actually in front; otherwise the alarm's notification rings.
                    if (!restBeeped && a.hasWindowFocus()) {
                        restBeeped = true;
                        Reminders.cancelRest(a);
                        try {
                            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
                            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300);
                            a.handler.postDelayed(tone::release, 600);
                        } catch (Exception ignored) {
                        }
                    }
                }
                a.handler.postDelayed(this, 250);
            }
        }, 50);
    }
}
