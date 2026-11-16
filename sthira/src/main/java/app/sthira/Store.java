package app.sthira;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Everything the app remembers, kept as one JSON file in private storage:
 * the daily habit log, workout plans and history, digest taste, and settings.
 */
final class Store {

    private static JSONObject root;
    private static File file;

    private Store() {
    }

    static synchronized JSONObject root(Context c) {
        if (root == null) {
            file = new File(c.getApplicationContext().getFilesDir(), "state.json");
            root = read(file);
            if (root == null) root = read(new File(file.getPath() + ".bak"));
            if (root == null) root = new JSONObject();
            if (root.optJSONArray("plans") == null) seedPlans();
            if (root.optString("installed").isEmpty()) put(root, "installed", LocalDate.now().toString());
        }
        return root;
    }

    private static JSONObject read(File f) {
        try {
            if (!f.exists()) return null;
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    static synchronized void save(Context c) {
        root(c);
        try {
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            File bak = new File(file.getPath() + ".bak");
            if (file.exists()) {
                bak.delete();
                file.renameTo(bak);
            }
            tmp.renameTo(file);
        } catch (Exception ignored) {
        }
        AgendaWidget.updateAll(c.getApplicationContext());
    }

    // ---- small JSON helpers ----

    static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (JSONException ignored) {
        }
    }

    static JSONObject obj(JSONObject p, String k) {
        JSONObject o = p.optJSONObject(k);
        if (o == null) {
            o = new JSONObject();
            put(p, k, o);
        }
        return o;
    }

    static JSONArray arr(JSONObject p, String k) {
        JSONArray a = p.optJSONArray(k);
        if (a == null) {
            a = new JSONArray();
            put(p, k, a);
        }
        return a;
    }

    // ---- settings ----

    static String setting(Context c, String key, String def) {
        return obj(root(c), "settings").optString(key, def);
    }

    static int setting(Context c, String key, int def) {
        return obj(root(c), "settings").optInt(key, def);
    }

    static void setSetting(Context c, String key, Object value) {
        put(obj(root(c), "settings"), key, value);
        save(c);
    }

    static String digestTime(Context c) {
        return setting(c, "digest", "13:00");
    }

    static String homeTime(Context c) {
        return setting(c, "home", "16:40");
    }

    static String trainTime(Context c) {
        return setting(c, "train", "19:15");
    }

    static String shutTime(Context c) {
        return setting(c, "shut", "22:30");
    }

    static int medMinutes(Context c) {
        return setting(c, "medMin", 10);
    }

    static int restSeconds(Context c) {
        return setting(c, "rest", 60);
    }

    // ---- the daily log ----

    static JSONObject day(Context c, LocalDate d) {
        return obj(obj(root(c), "days"), d.toString());
    }

    static JSONObject dayOrEmpty(Context c, LocalDate d) {
        JSONObject o = obj(root(c), "days").optJSONObject(d.toString());
        return o == null ? new JSONObject() : o;
    }

    static boolean meditated(Context c, LocalDate d) {
        return dayOrEmpty(c, d).optInt("med", 0) > 0;
    }

    static String trained(Context c, LocalDate d) {
        return dayOrEmpty(c, d).optString("train", "");
    }

    static boolean journaled(Context c, LocalDate d) {
        return "yes".equals(dayOrEmpty(c, d).optString("journal", ""));
    }

    static boolean shutDown(Context c, LocalDate d) {
        return dayOrEmpty(c, d).optBoolean("shut", false);
    }

    /** Records a finished sit and clears the running timer. */
    static void logMeditation(Context c, int minutes) {
        JSONObject d = day(c, LocalDate.now());
        put(d, "med", Math.max(1, minutes));
        java.time.LocalTime t = java.time.LocalTime.now();
        put(d, "medAt", hhmm(t.getHour() * 60 + t.getMinute()));
        root(c).remove("medEnd");
        save(c);
    }

    static boolean allDone(Context c, LocalDate d) {
        return shutDown(c, d) && meditated(c, d) && journaled(c, d) && !"missed".equals(trained(c, d));
    }

    // ---- workout plans ----

    static JSONObject exercise(String name, int sets, int lo, int hi) {
        JSONObject e = new JSONObject();
        put(e, "name", name);
        put(e, "sets", sets);
        put(e, "lo", lo);
        put(e, "hi", hi);
        return e;
    }

    static JSONObject planDay(String name, JSONObject... exercises) {
        JSONObject d = new JSONObject();
        put(d, "name", name);
        JSONArray a = new JSONArray();
        for (JSONObject e : exercises) a.put(e);
        put(d, "ex", a);
        return d;
    }

    static JSONObject plan(String name, boolean[] week, JSONObject... days) {
        JSONObject p = new JSONObject();
        put(p, "id", "p" + System.nanoTime());
        put(p, "name", name);
        JSONArray w = new JSONArray();
        for (boolean b : week) w.put(b);
        put(p, "week", w);
        JSONArray a = new JSONArray();
        for (JSONObject d : days) a.put(d);
        put(p, "days", a);
        return p;
    }

    private static void seedPlans() {
        JSONObject ulul = plan("ULUL Home", new boolean[]{true, true, false, true, true, false, false},
                planDay("Upper A",
                        exercise("Pull-up", 3, 6, 10),
                        exercise("Parallette push-up", 3, 8, 12),
                        exercise("One-arm row, slow", 3, 12, 15),
                        exercise("Pike push-up", 3, 6, 10),
                        exercise("Dumbbell curl", 2, 12, 15),
                        exercise("Lateral raise", 2, 15, 20)),
                planDay("Lower A",
                        exercise("Bulgarian split squat", 3, 8, 12),
                        exercise("Single-leg glute bridge", 3, 10, 15),
                        exercise("Slider hamstring curl", 3, 8, 12),
                        exercise("Single-leg calf raise", 3, 12, 20),
                        exercise("Hanging knee raise", 3, 8, 15)),
                planDay("Upper B",
                        exercise("Chin-up", 3, 6, 10),
                        exercise("Feet-raised parallette push-up", 3, 8, 12),
                        exercise("Bent-over row, slow", 3, 12, 15),
                        exercise("Overhead press, slow", 3, 10, 15),
                        exercise("Hammer curl", 2, 12, 15),
                        exercise("Parallette L-sit hold (seconds)", 3, 10, 20)),
                planDay("Lower B",
                        exercise("Reverse lunge", 3, 10, 15),
                        exercise("Slow squat", 3, 15, 25),
                        exercise("Glute bridge", 3, 15, 20),
                        exercise("Calf raise", 3, 15, 25),
                        exercise("Rear-delt raise", 2, 15, 20),
                        exercise("Plank (seconds)", 3, 30, 60)));
        JSONObject rotation = plan("3-Day Rotation", new boolean[]{true, false, true, false, true, false, false},
                planDay("Upper",
                        exercise("Pull-up", 3, 6, 10),
                        exercise("Parallette push-up", 3, 8, 12),
                        exercise("One-arm row, slow", 3, 12, 15),
                        exercise("Pike push-up", 3, 6, 10),
                        exercise("Dumbbell curl", 2, 12, 15)),
                planDay("Lower",
                        exercise("Bulgarian split squat", 3, 8, 12),
                        exercise("Single-leg glute bridge", 3, 10, 15),
                        exercise("Slider hamstring curl", 3, 8, 12),
                        exercise("Single-leg calf raise", 3, 12, 20),
                        exercise("Hanging knee raise", 3, 8, 15)));
        JSONArray plans = new JSONArray();
        plans.put(ulul);
        plans.put(rotation);
        put(root, "plans", plans);
        put(root, "active", ulul.optString("id"));
        put(root, "activeSince", LocalDate.now().toString());
        put(root, "next", 0);
    }

    static JSONArray plans(Context c) {
        return arr(root(c), "plans");
    }

    static JSONObject planById(Context c, String id) {
        JSONArray a = plans(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject p = a.optJSONObject(i);
            if (p != null && p.optString("id").equals(id)) return p;
        }
        return null;
    }

    static JSONObject activePlan(Context c) {
        JSONObject p = planById(c, root(c).optString("active"));
        if (p == null && plans(c).length() > 0) {
            p = plans(c).optJSONObject(0);
            put(root(c), "active", p.optString("id"));
        }
        return p;
    }

    static void usePlan(Context c, String id) {
        put(root(c), "active", id);
        put(root(c), "activeSince", LocalDate.now().toString());
        put(root(c), "next", 0);
        root(c).remove("session");
        save(c);
    }

    static int weeksOnPlan(Context c) {
        try {
            LocalDate since = LocalDate.parse(root(c).optString("activeSince"));
            return (int) (ChronoUnit.DAYS.between(since, LocalDate.now()) / 7) + 1;
        } catch (Exception e) {
            return 1;
        }
    }

    static boolean isTrainingDay(Context c, LocalDate d) {
        JSONObject p = activePlan(c);
        if (p == null) return false;
        JSONArray w = p.optJSONArray("week");
        return w != null && w.optBoolean(d.getDayOfWeek().getValue() - 1, false);
    }

    static int trainingDaysPerWeek(Context c) {
        JSONObject p = activePlan(c);
        int n = 0;
        if (p != null && p.optJSONArray("week") != null) {
            for (int i = 0; i < 7; i++) if (p.optJSONArray("week").optBoolean(i, false)) n++;
        }
        return n;
    }

    /** The workout that is up next in the rotation, or null when the plan has no days. */
    static JSONObject nextWorkout(Context c) {
        JSONObject p = activePlan(c);
        if (p == null) return null;
        JSONArray days = p.optJSONArray("days");
        if (days == null || days.length() == 0) return null;
        return days.optJSONObject(Math.floorMod(root(c).optInt("next", 0), days.length()));
    }

    /** Today's session: created on first use and reset when the date changes. */
    static JSONObject session(Context c) {
        String today = LocalDate.now().toString();
        JSONObject s = root(c).optJSONObject("session");
        if (s == null || !today.equals(s.optString("date"))) {
            s = new JSONObject();
            put(s, "date", today);
            put(s, "reps", new JSONObject());
            JSONObject w = nextWorkout(c);
            put(s, "name", w == null ? "" : w.optString("name"));
            put(root(c), "session", s);
        }
        return s;
    }

    static boolean sessionDone(Context c) {
        JSONObject s = root(c).optJSONObject("session");
        return s != null && LocalDate.now().toString().equals(s.optString("date")) && s.optBoolean("done", false);
    }

    static JSONArray sessionReps(Context c, int exercise) {
        return arr(obj(session(c), "reps"), String.valueOf(exercise));
    }

    static void setRep(Context c, int exercise, int set, int reps) {
        JSONArray old = sessionReps(c, exercise);
        JSONArray next = new JSONArray();
        for (int i = 0; i < Math.max(old.length(), set + 1); i++) next.put(i == set ? reps : old.optInt(i, 0));
        put(obj(session(c), "reps"), String.valueOf(exercise), next);
        save(c);
    }

    /** Writes the session into each exercise's history and moves the rotation on. */
    static void finishSession(Context c) {
        JSONObject s = session(c);
        JSONObject w = nextWorkout(c);
        if (w != null) {
            JSONArray ex = w.optJSONArray("ex");
            for (int i = 0; ex != null && i < ex.length(); i++) {
                JSONArray reps = obj(s, "reps").optJSONArray(String.valueOf(i));
                if (reps == null || reps.length() == 0) continue;
                JSONObject entry = new JSONObject();
                put(entry, "d", s.optString("date"));
                put(entry, "r", reps);
                arr(obj(root(c), "logs"), ex.optJSONObject(i).optString("name")).put(entry);
            }
        }
        put(s, "done", true);
        put(root(c), "next", root(c).optInt("next", 0) + 1);
        put(day(c, LocalDate.now()), "train", "done");
        save(c);
    }

    /** Reps from the most recent earlier session of this exercise, or null. */
    static JSONArray lastReps(Context c, String exerciseName) {
        JSONArray log = obj(root(c), "logs").optJSONArray(exerciseName);
        String today = LocalDate.now().toString();
        for (int i = log == null ? -1 : log.length() - 1; i >= 0; i--) {
            JSONObject e = log.optJSONObject(i);
            if (e != null && !today.equals(e.optString("d"))) return e.optJSONArray("r");
        }
        return null;
    }

    static String join(JSONArray a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            if (i > 0) b.append(", ");
            b.append(a.optInt(i));
        }
        return b.toString();
    }

    static LocalDate monday(LocalDate d) {
        return d.minusDays(d.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }

    static int minutes(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    static String hhmm(int minutes) {
        int m = Math.floorMod(minutes, 1440);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", m / 60, m % 60);
    }
}
