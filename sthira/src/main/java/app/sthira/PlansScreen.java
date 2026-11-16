package app.sthira;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Workout plans: switch, duplicate, edit, or paste in a new one as text. */
final class PlansScreen {

    private static final String[] DAY_NAMES = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final String[] LIBRARY = {
            "Pull-up", "Chin-up", "Negative pull-up", "Hanging knee raise", "Hanging leg raise",
            "Parallette push-up", "Feet-raised parallette push-up", "Pike push-up", "Parallette L-sit hold (seconds)",
            "Parallette tuck planche hold (seconds)", "One-arm row, slow", "Bent-over row, slow", "Overhead press, slow",
            "Dumbbell curl", "Hammer curl", "Lateral raise", "Rear-delt raise", "Overhead triceps extension",
            "Bulgarian split squat", "Reverse lunge", "Slow squat", "Backpack squat", "Single-leg glute bridge",
            "Glute bridge", "Slider hamstring curl", "Single-leg calf raise", "Calf raise", "Plank (seconds)"};

    private PlansScreen() {
    }

    static View build(MainActivity a) {
        JSONObject editing = a.editPlan == null ? null : Store.planById(a, a.editPlan);
        return editing == null ? list(a) : editor(a, editing);
    }

    // ---- the list of plans ----

    private static View list(MainActivity a) {
        a.editPlan = null;
        LinearLayout body = Ui.col(a);
        body.addView(Ui.text(a, "Switch anytime. Your history follows each exercise. Six to eight weeks on one plan works best.",
                13, Ui.CREAM, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 4));
        String active = Store.root(a).optString("active");
        JSONArray plans = Store.plans(a);
        for (int i = 0; i < plans.length(); i++) {
            final int index = i;
            final JSONObject p = plans.optJSONObject(i);
            boolean on = p.optString("id").equals(active);
            LinearLayout card = Ui.col(a);
            card.setBackground(on ? new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                    Ui.fill(Ui.BLACK, 4), Ui.stroke(Ui.OCHRE, 4, false)}) : Ui.fill(Ui.BLACK, 4));
            Ui.pad(card, 14, 12, 14, 12);
            LinearLayout top = Ui.row(a);
            top.addView(Ui.display(a, p.optString("name").toUpperCase(Locale.ROOT), 26, Ui.CREAM), Ui.lp(0, Ui.WRAP, 1));
            if (on) top.addView(Ui.label(a, "active", Ui.OCHRE));
            card.addView(top);
            int perWeek = 0;
            for (int d = 0; d < 7; d++) if (p.optJSONArray("week") != null && p.optJSONArray("week").optBoolean(d)) perWeek++;
            int workouts = p.optJSONArray("days") == null ? 0 : p.optJSONArray("days").length();
            String sub = perWeek + " days a week · " + workouts + " workouts" + (on ? " · week " + Store.weeksOnPlan(a) : "");
            card.addView(Ui.text(a, sub, 13, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 4, 0, 10));
            LinearLayout actions = Ui.row(a);
            actions.addView(Ui.button(a, "Edit", Ui.OCHRE, Ui.BLACK, v -> {
                a.editPlan = p.optString("id");
                a.refresh();
            }), Ui.gap(Ui.lp(0, Ui.WRAP, 1), 0, 0, 3, 0));
            if (!on) {
                actions.addView(Ui.ghost(a, "Use", Ui.CREAM, false, v -> Ui.confirm(a, "Switch to " + p.optString("name") + "?",
                        "The week counter restarts. Your exercise history stays.", "Switch", () -> {
                            Store.usePlan(a, p.optString("id"));
                            a.refresh();
                        })), Ui.gap(Ui.lp(0, Ui.WRAP, 1), 3, 0, 3, 0));
            }
            actions.addView(Ui.ghost(a, "Copy", Ui.CREAM, false, v -> {
                try {
                    JSONObject copy = new JSONObject(p.toString());
                    Store.put(copy, "id", "p" + System.nanoTime());
                    Store.put(copy, "name", p.optString("name") + " copy");
                    plans.put(copy);
                    Store.save(a);
                    a.refresh();
                } catch (Exception ignored) {
                }
            }), Ui.gap(Ui.lp(0, Ui.WRAP, 1), 3, 0, on ? 0 : 3, 0));
            if (!on) {
                actions.addView(Ui.iconButton(a, Ui.I_CLOSE, Ui.CREAM, true, "Delete " + p.optString("name"),
                        v -> Ui.confirm(a, "Delete " + p.optString("name") + "?", "The plan is removed. Exercise history stays.",
                                "Delete", () -> {
                                    plans.remove(index);
                                    Store.save(a);
                                    a.refresh();
                                })), Ui.gap(Ui.lp(Ui.dp(48), Ui.dp(48)), 3, 0, 0, 0));
            }
            card.addView(actions);
            body.addView(card, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        }

        body.addView(Ui.ghost(a, "+ New plan", Ui.CREAM, true, v -> Ui.prompt(a, "Name the plan", "", name -> {
            JSONObject p = Store.plan(name, new boolean[]{true, false, true, false, true, false, false},
                    Store.planDay("Workout A"));
            plans.put(p);
            Store.save(a);
            a.editPlan = p.optString("id");
            a.refresh();
        })), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 12, 0, 0));
        body.addView(Ui.button(a, "Paste a plan from Claude", Ui.JADE, Ui.BLACK, v -> {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            CharSequence text = cm == null || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0
                    ? null : cm.getPrimaryClip().getItemAt(0).coerceToText(a);
            JSONObject p = text == null ? null : parse(text.toString());
            if (p == null) {
                Toast.makeText(a, "No plan found on the clipboard. Copy a plan as text first.", Toast.LENGTH_LONG).show();
                return;
            }
            plans.put(p);
            Store.save(a);
            a.refresh();
            Toast.makeText(a, "Added \"" + p.optString("name") + "\".", Toast.LENGTH_SHORT).show();
        }), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        body.addView(Ui.text(a, "To get a new plan, copy your current one from its edit screen, ask Claude to change it, "
                        + "copy the reply and paste it here.", 12, Ui.CREAM, Ui.regular),
                Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        View back = Ui.ghost(a, "Session", Ui.CREAM, false, v -> a.open(MainActivity.TRAIN));
        return Ui.page(a, Ui.header(a, "workouts", "PLANS", back), body);
    }

    // ---- editing one plan ----

    private static View editor(MainActivity a, JSONObject p) {
        LinearLayout body = Ui.col(a);
        body.addView(Ui.ghost(a, "Rename: " + p.optString("name"), Ui.CREAM, false,
                v -> Ui.prompt(a, "Plan name", p.optString("name"), name -> {
                    Store.put(p, "name", name);
                    Store.save(a);
                    a.refresh();
                })), Ui.lp(Ui.MATCH, Ui.WRAP));

        body.addView(Ui.label(a, "training days", Ui.CREAM), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 14, 0, 6));
        LinearLayout week = Ui.row(a);
        JSONArray w = Store.arr(p, "week");
        for (int i = 0; i < 7; i++) {
            final int d = i;
            boolean on = w.optBoolean(i, false);
            TextView chip = Ui.text(a, DAY_NAMES[i].substring(0, 1), 14, on ? Ui.BLACK : Ui.CREAM, Ui.bold);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(Ui.press(on ? Ui.fill(Ui.OCHRE, 4) : Ui.fill(Ui.BLACK, 4), 4));
            chip.setContentDescription(DAY_NAMES[i] + (on ? ", training day" : ", rest day"));
            chip.setOnClickListener(v -> {
                JSONArray next = new JSONArray();
                for (int k = 0; k < 7; k++) next.put(k == d ? !w.optBoolean(k, false) : w.optBoolean(k, false));
                Store.put(p, "week", next);
                Store.save(a);
                a.refresh();
            });
            week.addView(chip, Ui.gap(Ui.lp(0, Ui.dp(44), 1), i == 0 ? 0 : 3, 0, i == 6 ? 0 : 3, 0));
        }
        body.addView(week, Ui.lp(Ui.MATCH, Ui.WRAP));
        body.addView(Ui.text(a, "Workouts run in order on these days, so a missed day just moves the next one along.",
                12, Ui.CREAM, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));

        JSONArray days = Store.arr(p, "days");
        for (int di = 0; di < days.length(); di++) {
            final int dayIndex = di;
            final JSONObject day = days.optJSONObject(di);
            LinearLayout head = Ui.row(a);
            head.addView(Ui.display(a, day.optString("name").toUpperCase(Locale.ROOT), 26, Ui.CREAM), Ui.lp(0, Ui.WRAP, 1));
            head.addView(Ui.ghost(a, "Rename", Ui.CREAM, false, v -> Ui.prompt(a, "Workout name", day.optString("name"), n -> {
                Store.put(day, "name", n);
                Store.save(a);
                a.refresh();
            })));
            head.addView(Ui.iconButton(a, Ui.I_CLOSE, Ui.CREAM, true, "Remove " + day.optString("name"),
                    v -> Ui.confirm(a, "Remove " + day.optString("name") + "?", "The workout leaves this plan.", "Remove", () -> {
                        days.remove(dayIndex);
                        Store.save(a);
                        a.refresh();
                    })), Ui.gap(Ui.lp(Ui.dp(48), Ui.dp(48)), 6, 0, 0, 0));
            body.addView(head, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 18, 0, 0));

            final JSONArray exercises = Store.arr(day, "ex");
            for (int ei = 0; ei < exercises.length(); ei++) {
                final int exIndex = ei;
                final JSONObject ex = exercises.optJSONObject(ei);
                LinearLayout row = Ui.card(a, Ui.BLACK, null);
                row.setMinimumHeight(Ui.dp(56));
                Ui.pad(row, 12, 4, 4, 4);
                TextView name = Ui.text(a, ex.optString("name"), 15, Ui.CREAM, Ui.bold);
                name.setMinHeight(Ui.dp(48));
                name.setGravity(Gravity.CENTER_VERTICAL);
                name.setOnClickListener(v -> chooseExercise(a, "Swap " + ex.optString("name"), picked -> {
                    Store.put(ex, "name", picked);
                    Store.save(a);
                    a.refresh();
                }));
                row.addView(name, Ui.lp(0, Ui.WRAP, 1));
                String range = ex.optInt("sets") + " × " + ex.optInt("lo") + "–" + ex.optInt("hi");
                row.addView(Ui.ghost(a, range, Ui.CREAM, false, v -> pickRange(a, ex)));
                row.addView(Ui.iconButton(a, Ui.I_CLOSE, Ui.CREAM, true, "Remove " + ex.optString("name"), v -> {
                    exercises.remove(exIndex);
                    Store.save(a);
                    a.refresh();
                }), Ui.gap(Ui.lp(Ui.dp(48), Ui.dp(48)), 4, 0, 0, 0));
                body.addView(row, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
            }
            body.addView(Ui.ghost(a, "+ Add exercise", Ui.CREAM, true, v -> chooseExercise(a, "Add to " + day.optString("name"), picked -> {
                exercises.put(Store.exercise(picked, 3, 8, 12));
                Store.save(a);
                a.refresh();
            })), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
        }

        body.addView(Ui.ghost(a, "+ Add workout", Ui.CREAM, true, v -> Ui.prompt(a, "Workout name", "", n -> {
            days.put(Store.planDay(n));
            Store.save(a);
            a.refresh();
        })), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 18, 0, 0));
        body.addView(Ui.button(a, "Copy plan as text", Ui.JADE, Ui.BLACK, v -> {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Workout plan", export(p)));
            Toast.makeText(a, "Copied. Paste it to Claude and ask for changes.", Toast.LENGTH_LONG).show();
        }), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));

        View done = Ui.button(a, "Done", Ui.OCHRE, Ui.BLACK, v -> {
            a.editPlan = null;
            a.refresh();
        });
        return Ui.page(a, Ui.header(a, "editing", "PLAN", done), body);
    }

    private static void chooseExercise(MainActivity a, String title, Ui.TextResult r) {
        String[] items = new String[LIBRARY.length + 1];
        items[0] = "Type my own…";
        System.arraycopy(LIBRARY, 0, items, 1, LIBRARY.length);
        new AlertDialog.Builder(a).setTitle(title).setItems(items, (dlg, which) -> {
            if (which == 0) Ui.prompt(a, "Exercise name", "", r);
            else r.done(items[which]);
        }).setNegativeButton("Cancel", null).show();
    }

    private static void pickRange(MainActivity a, JSONObject ex) {
        LinearLayout row = Ui.row(a);
        row.setGravity(Gravity.CENTER);
        NumberPicker sets = Ui.picker(a, 1, 8, ex.optInt("sets", 3));
        NumberPicker lo = Ui.picker(a, 1, 120, ex.optInt("lo", 8));
        NumberPicker hi = Ui.picker(a, 1, 120, ex.optInt("hi", 12));
        String[] names = {"Sets", "From", "To"};
        NumberPicker[] pickers = {sets, lo, hi};
        for (int i = 0; i < 3; i++) {
            LinearLayout c = Ui.col(a);
            c.setGravity(Gravity.CENTER);
            c.addView(Ui.text(a, names[i], 12, Ui.CREAM, Ui.bold));
            c.addView(pickers[i]);
            row.addView(c, Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 10, 8, 10, 0));
        }
        new AlertDialog.Builder(a).setTitle(ex.optString("name")).setView(row)
                .setPositiveButton("Save", (dlg, w) -> {
                    sets.clearFocus();
                    lo.clearFocus();
                    hi.clearFocus();
                    Store.put(ex, "sets", sets.getValue());
                    Store.put(ex, "lo", Math.min(lo.getValue(), hi.getValue()));
                    Store.put(ex, "hi", Math.max(lo.getValue(), hi.getValue()));
                    Store.save(a);
                    a.refresh();
                }).setNegativeButton("Cancel", null).show();
    }

    // ---- plans as plain text, for moving them through a chat ----

    static String export(JSONObject p) {
        StringBuilder b = new StringBuilder("Plan: ").append(p.optString("name")).append("\nDays:");
        JSONArray w = p.optJSONArray("week");
        for (int i = 0; i < 7; i++) if (w != null && w.optBoolean(i)) b.append(' ').append(DAY_NAMES[i]);
        JSONArray days = p.optJSONArray("days");
        for (int d = 0; days != null && d < days.length(); d++) {
            JSONObject day = days.optJSONObject(d);
            b.append("\n# ").append(day.optString("name"));
            JSONArray ex = day.optJSONArray("ex");
            for (int e = 0; ex != null && e < ex.length(); e++) {
                JSONObject x = ex.optJSONObject(e);
                b.append('\n').append(x.optString("name")).append(" | ").append(x.optInt("sets")).append(" | ")
                        .append(x.optInt("lo")).append('-').append(x.optInt("hi"));
            }
        }
        return b.append('\n').toString();
    }

    /** Reads the format written by {@link #export}. Returns null when no workout is found. */
    static JSONObject parse(String text) {
        String name = "Pasted plan";
        boolean[] week = new boolean[7];
        JSONArray days = new JSONArray();
        JSONObject day = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("plan:")) {
                name = line.substring(5).trim();
            } else if (lower.startsWith("days:")) {
                for (int i = 0; i < 7; i++) week[i] = lower.contains(DAY_NAMES[i].toLowerCase(Locale.ROOT));
            } else if (line.startsWith("#")) {
                day = Store.planDay(line.replaceFirst("^#+", "").trim());
                days.put(day);
            } else if (line.contains("|") && day != null) {
                String[] parts = line.split("\\|");
                if (parts.length < 3) continue;
                try {
                    String[] range = parts[2].trim().split("[-–]");
                    int lo = Integer.parseInt(range[0].trim());
                    int hi = range.length > 1 ? Integer.parseInt(range[1].trim()) : lo;
                    Store.arr(day, "ex").put(Store.exercise(parts[0].trim(), Integer.parseInt(parts[1].trim()), lo, hi));
                } catch (Exception ignored) {
                }
            }
        }
        if (days.length() == 0) return null;
        boolean any = false;
        for (boolean b : week) any |= b;
        if (!any) week = new boolean[]{true, false, true, false, true, false, false};
        JSONObject p = Store.plan(name.isEmpty() ? "Pasted plan" : name, week);
        Store.put(p, "days", days);
        return p;
    }
}
