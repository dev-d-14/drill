package app.sthira;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One list a day, then it ends. A scheduled task writes the day's items to a JSON file in the
 * repo; the phone shows every one of them, ordered by the taste it has learned from the
 * more/less buttons. The order is settled once per digest and then held, so a vote never
 * shifts the list under your thumb.
 */
final class DigestScreen {

    private static final String SOURCE = "https://raw.githubusercontent.com/dev-d-14/drill/main/digest/latest.json";

    private static JSONObject data;
    private static boolean loading, triedCache;
    private static long lastFetch;
    private static String problem;

    private DigestScreen() {
    }

    static View build(MainActivity a) {
        load(a);
        LinearLayout body = Ui.col(a);
        String date = data == null ? "" : data.optString("date");
        boolean fresh = LocalDate.now().toString().equals(date);
        String label = data == null ? "one list a day" : fresh ? "today · " + Store.digestTime(a) : "from " + date;

        if (data == null || data.optJSONArray("items") == null || data.optJSONArray("items").length() == 0) {
            String msg = loading ? "Fetching today's digest."
                    : problem != null ? "Couldn't fetch the digest (" + problem + ")."
                    : "No digest yet. The first list arrives around " + Store.digestTime(a) + ".";
            body.addView(Ui.text(a, msg, 14, Ui.CREAM, Ui.regular));
            body.addView(Ui.button(a, "Try again", Ui.CELL, Ui.CREAM, v -> {
                lastFetch = 0;
                a.refresh();
            }), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 12, 0, 0));
            return Ui.page(a, Ui.header(a, label, "DIGEST", null), body);
        }

        JSONObject root = Store.root(a);
        if (!date.equals(root.optString("digestDate"))) {
            Store.put(root, "digestDate", date);
            Store.put(root, "votes", new JSONObject());
            Store.put(root, "read", new JSONObject());
            Store.put(root, "digestOrder", new JSONArray());
            Store.save(a);
        }
        if (fresh && !Store.dayOrEmpty(a, LocalDate.now()).optBoolean("digest", false)) {
            Store.put(Store.day(a, LocalDate.now()), "digest", true);
            Store.save(a);
        }
        if (!fresh) {
            body.addView(Ui.text(a, "Today's digest isn't in yet. This is the last one.", 13, Ui.CREAM, Ui.regular),
                    Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 8));
        }

        List<JSONObject> items = ordered(a);
        JSONObject votes = Store.obj(root, "votes"), read = Store.obj(root, "read");
        int opened = 0;
        for (int i = 0; i < items.size(); i++) {
            final JSONObject item = items.get(i);
            final String id = item.optString("id");
            boolean science = "science".equals(item.optString("kind"));
            if (read.optBoolean(id, false)) opened++;
            LinearLayout card = Ui.row(a);
            Ui.pad(card, 14, 12, 8, 12);
            // Same card for every item; science is marked by its label, not a border.
            card.setBackground(Ui.fill(Ui.BLACK, 4));
            LinearLayout texts = Ui.col(a);
            String tag = (i + 1) + " · " + item.optString("tag", "tech");
            if (science && !item.optString("evidence").isEmpty()) tag += " · " + item.optString("evidence") + " evidence";
            texts.addView(Ui.label(a, tag, science ? Ui.JADE : Ui.OCHRE));
            TextView title = Ui.text(a, item.optString("title"), 16, Ui.CREAM, Ui.bold);
            texts.addView(title, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 5, 0, 0));
            texts.addView(Ui.text(a, item.optString("why"), 13, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 4, 0, 0));
            if (!item.optString("source").isEmpty()) {
                texts.addView(Ui.text(a, item.optString("source") + (read.optBoolean(id, false) ? " · opened" : " · tap to open"),
                        12, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 4, 0, 0));
            }
            texts.setOnClickListener(v -> {
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.optString("url"))));
                    Store.put(read, id, true);
                    Store.put(root, "digestUsed", LocalDate.now().toString());
                    Store.save(a);
                } catch (Exception e) {
                    Toast.makeText(a, "Couldn't open the link.", Toast.LENGTH_SHORT).show();
                }
            });
            card.addView(texts, Ui.lp(0, Ui.WRAP, 1));
            card.addView(votePair(a, item, votes.optInt(id, 0)), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 10, 0, 0, 0));
            body.addView(card, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, i == 0 ? 0 : 8, 0, 0));
        }

        JSONObject drill = data.optJSONObject("drill");
        if (drill != null && drill.optJSONArray("options") != null) body.addView(drillCard(a, drill, date),
                Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));

        body.addView(new View(a), Ui.lp(Ui.MATCH, 0, 1));
        LinearLayout foot = Ui.row(a);
        foot.addView(Ui.text(a, opened >= items.size() ? "You're caught up. That's all for today."
                : "That's the whole digest. No feed.", 14, Ui.CREAM, Ui.regular), Ui.lp(0, Ui.WRAP, 1));
        foot.addView(Ui.display(a, opened + " / " + items.size(), 28, Ui.CREAM));
        body.addView(foot, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 16, 0, 0));
        return Ui.page(a, Ui.header(a, label, "DIGEST", null), body);
    }

    /**
     * The more/less pair. A tap writes the taste and repaints these two buttons where they
     * stand: the screen is not rebuilt and the list is not reordered, so the button cannot
     * walk out from under a second tap.
     */
    private static View votePair(MainActivity a, JSONObject item, int current) {
        LinearLayout pair = Ui.row(a);
        final int[] vote = {current};
        final View up = Ui.iconButton(a, Ui.I_PLUS, Ui.CREAM, true, "More like this", null);
        final View down = Ui.iconButton(a, Ui.I_MINUS, Ui.CREAM, true, "Less like this", null);
        paint(up, current == 1);
        paint(down, current == -1);
        View.OnClickListener tap = v -> {
            int direction = v == up ? 1 : -1;
            int after = vote[0] == direction ? 0 : direction;
            JSONObject root = Store.root(a);
            JSONObject votes = Store.obj(root, "votes"), tags = Store.obj(root, "tags");
            String tag = item.optString("tag", "tech").toLowerCase(Locale.ROOT);
            double weight = tags.optDouble(tag, 0) + 0.5 * (after - vote[0]);
            Store.put(tags, tag, Math.max(-3, Math.min(3, weight)));
            Store.put(votes, item.optString("id"), after);
            Store.put(root, "digestUsed", LocalDate.now().toString());
            Store.save(a);
            vote[0] = after;
            paint(up, after == 1);
            paint(down, after == -1);
        };
        up.setOnClickListener(tap);
        down.setOnClickListener(tap);
        pair.addView(up, Ui.lp(Ui.dp(46), Ui.dp(46)));
        pair.addView(down, Ui.gap(Ui.lp(Ui.dp(46), Ui.dp(46)), 10, 0, 0, 0));
        return pair;
    }

    /** Cream while the vote is on, a darker panel while it is off. No outlines in the digest. */
    private static void paint(View button, boolean on) {
        button.setBackground(on ? Ui.press(Ui.fill(Ui.OCHRE, 4), 4) : Ui.press(Ui.fill(Ui.CELL, 4), 4));
        View icon = button instanceof ViewGroup ? ((ViewGroup) button).getChildAt(0) : null;
        if (icon instanceof Ui.Icon) ((Ui.Icon) icon).setColor(on ? Ui.BLACK : Ui.CREAM);
    }

    private static View drillCard(MainActivity a, JSONObject drill, String date) {
        JSONObject answers = Store.obj(Store.root(a), "drills");
        JSONArray options = drill.optJSONArray("options");
        int picked = answers.optInt(date, -1);
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.fill(Ui.OCHRE, 4));
        Ui.pad(card, 14, 12, 14, 12);
        card.addView(Ui.label(a, "weekly drill · 60 seconds", Ui.BLACK));
        card.addView(Ui.text(a, drill.optString("q"), 16, Ui.BLACK, Ui.bold), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 5, 0, 0));
        if (picked < 0) {
            card.addView(Ui.button(a, "Answer", Ui.BLACK, Ui.CREAM, v -> {
                String[] names = new String[options.length()];
                for (int i = 0; i < names.length; i++) names[i] = options.optString(i);
                new AlertDialog.Builder(a).setTitle("Pick one").setItems(names, (dlg, which) -> {
                    Store.put(answers, date, which);
                    Store.put(Store.root(a), "digestUsed", LocalDate.now().toString());
                    Store.save(a);
                    a.refresh();
                }).show();
            }), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 10, 0, 0));
        } else {
            boolean right = picked == drill.optInt("answer", -1);
            String verdict = (right ? "Right. " : "Not quite: " + options.optString(drill.optInt("answer", 0)) + ". ")
                    + drill.optString("why");
            card.addView(Ui.text(a, verdict, 13, Ui.BLACK, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        }
        return card;
    }

    /**
     * Every item in the day's file, in the order it will keep: the picker's order plus the
     * learned taste, worked out the first time the digest is opened and then held in the store.
     * Today's votes move tomorrow's list, not the one being read. A digest file that changes
     * under us (different items, same day) earns a fresh order.
     */
    private static List<JSONObject> ordered(Context c) {
        JSONArray all = data.optJSONArray("items");
        JSONObject root = Store.root(c);
        JSONArray held = root.optJSONArray("digestOrder");
        List<JSONObject> list = new ArrayList<>();
        if (held != null && held.length() == all.length()) {
            for (int i = 0; i < held.length(); i++) {
                JSONObject item = byId(all, held.optString(i));
                if (item == null) {
                    list.clear();
                    break;
                }
                list.add(item);
            }
            if (list.size() == all.length()) return list;
        }
        JSONObject tags = Store.obj(root, "tags");
        final List<Double> scores = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item == null) continue;
            double score = (all.length() - i) + 3 * tags.optDouble(item.optString("tag", "tech").toLowerCase(Locale.ROOT), 0);
            int at = 0;
            while (at < list.size() && scores.get(at) >= score) at++;
            list.add(at, item);
            scores.add(at, score);
        }
        JSONArray order = new JSONArray();
        for (JSONObject item : list) order.put(item.optString("id"));
        Store.put(root, "digestOrder", order);
        Store.save(c);
        return list;
    }

    private static JSONObject byId(JSONArray all, String id) {
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return item;
        }
        return null;
    }

    private static void load(MainActivity a) {
        final File cache = new File(a.getFilesDir(), "digest.json");
        if (!triedCache) {
            triedCache = true;
            try {
                if (cache.exists()) data = new JSONObject(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
        long now = System.currentTimeMillis();
        if (loading || now - lastFetch < 15 * 60_000L) return;
        loading = true;
        lastFetch = now;
        new Thread(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(SOURCE + "?t=" + now / 600_000L).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                if (con.getResponseCode() == 200) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (InputStream in = con.getInputStream()) {
                        byte[] buf = new byte[8192];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    }
                    JSONObject fetched = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
                    Files.write(cache.toPath(), out.toByteArray());
                    data = fetched;
                    problem = null;
                } else {
                    problem = con.getResponseCode() == 404 ? null : "error " + con.getResponseCode();
                }
            } catch (Exception e) {
                problem = "no connection";
            }
            loading = false;
            a.handler.post(() -> {
                if (a.current == MainActivity.DIGEST && !a.isFinishing()) a.refresh();
            });
        }, "digest").start();
    }
}
