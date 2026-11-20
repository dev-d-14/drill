package app.sthira;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The small things on the Calendar tab: type-to-add, deadline countdowns, upcoming Codeforces
 * contests and the ISB question of the day. Secondary parts stay folded until tapped.
 */
final class CalExtras {

    private CalExtras() {
    }

    // ---- quick add ----

    static View quickAdd(MainActivity a) {
        LinearLayout box = Ui.col(a);
        LinearLayout row = Ui.row(a);
        EditText field = new EditText(a);
        field.setHint("Add: gym tomorrow 7pm");
        field.setHintTextColor(Ui.MUTED);
        field.setTextColor(Ui.CREAM);
        field.setTextSize(15);
        field.setTypeface(Ui.regular);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setBackground(Ui.fill(Ui.BLACK, 4));
        Ui.pad(field, 12, 10, 12, 10);
        row.addView(field, Ui.lp(0, Ui.dp(46), 1));
        TextView add = Ui.button(a, "Add", Ui.OCHRE, Ui.BLACK, null);
        row.addView(add, Ui.gap(Ui.lp(Ui.dp(64), Ui.dp(46)), 6, 0, 0, 0));
        box.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView preview = Ui.text(a, "", 12, Ui.MUTED, Ui.regular);
        preview.setVisibility(View.GONE);
        box.addView(preview, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 2, 5, 0, 0));

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                String t = e.toString().trim();
                if (t.isEmpty()) {
                    preview.setVisibility(View.GONE);
                    return;
                }
                preview.setText(describe(QuickAdd.parse(t, LocalDate.now(), LocalTime.now())));
                preview.setVisibility(View.VISIBLE);
            }
        });
        Runnable save = () -> {
            String t = field.getText().toString().trim();
            if (t.isEmpty()) return;
            QuickAdd.Parsed p = QuickAdd.parse(t, LocalDate.now(), LocalTime.now());
            if (insert(a, p.title, p.date, p.start, p.minutes, 10)) {
                Toast.makeText(a, "Added: " + describe(p), Toast.LENGTH_SHORT).show();
                InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
                a.calDay = p.date;
                a.calMonth = java.time.YearMonth.from(p.date);
                a.refresh();
            } else {
                Toast.makeText(a, "Couldn't add it to the calendar.", Toast.LENGTH_SHORT).show();
            }
        };
        add.setOnClickListener(v -> save.run());
        field.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_DONE || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                save.run();
                return true;
            }
            return false;
        });
        return box;
    }

    static String describe(QuickAdd.Parsed p) {
        LocalDate today = LocalDate.now();
        String day = p.date.equals(today) ? "Today" : p.date.equals(today.plusDays(1)) ? "Tomorrow"
                : p.date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + p.date.getDayOfMonth() + " "
                + p.date.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        String when = p.allDay() ? "all day" : Store.hhmm(p.start) + " to " + Store.hhmm((p.start + p.minutes) % 1440);
        return p.title + " · " + day + ", " + when;
    }

    static boolean insert(Context c, String title, LocalDate date, int start, int minutes, int reminder) {
        if (start < 0) {
            long b = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            return Cal.insert(c, title, b, b + 86_400_000L, true, -1);
        }
        long b = date.atTime(start / 60, start % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return Cal.insert(c, title, b, b + minutes * 60_000L, false, reminder);
    }

    // ---- deadline countdowns ----

    private static final Pattern DEADLINE = Pattern.compile(
            "(?i)\\b(exam|test|quiz|mid|midterm|assignment|deadline|submission|submit|due|viva|interview|presentation|project review)\\b");

    /** Up to three exams and deadlines in the next 14 days, as slim chips. Null if there are none. */
    static View countdowns(MainActivity a) {
        if (!Cal.allowed(a)) return null;
        LocalDate today = LocalDate.now();
        List<Cal.Ev> hits = new ArrayList<>();
        for (Cal.Ev e : Cal.between(a, today, today.plusDays(14))) {
            if (DEADLINE.matcher(e.title).find() && !e.first.isBefore(today)) hits.add(e);
            if (hits.size() == 3) break;
        }
        if (hits.isEmpty()) return null;
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(a);
        for (int i = 0; i < hits.size(); i++) {
            Cal.Ev e = hits.get(i);
            long days = ChronoUnit.DAYS.between(today, e.first);
            String when = days == 0 ? "today" : days == 1 ? "tomorrow" : "in " + days + " days";
            LinearLayout chip = Ui.col(a);
            chip.setBackground(Ui.press(Ui.fill(days <= 2 ? Ui.OCHRE : Ui.BLACK, 4), 4));
            Ui.pad(chip, 12, 8, 12, 8);
            int fg = days <= 2 ? Ui.BLACK : Ui.CREAM;
            chip.addView(Ui.text(a, when.toUpperCase(Locale.ROOT), 11, fg, Ui.bold));
            TextView t = Ui.text(a, e.title, 13, fg, Ui.medium);
            t.setMaxWidth(Ui.dp(180));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.addView(t, Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 2, 0, 0));
            chip.setOnClickListener(v -> {
                a.calDay = e.first;
                a.calMonth = java.time.YearMonth.from(e.first);
                a.refresh();
            });
            row.addView(chip, Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), i == 0 ? 0 : 6, 0, 0, 0));
        }
        scroll.addView(row);
        return scroll;
    }

    // ---- folded extras under the day: ISB question and Codeforces contests ----

    private static boolean openIsb, openCf;
    private static long timerEnds;          // elapsedRealtime when the 2-minute answer ends, 0 if idle

    static View extras(MainActivity a) {
        LinearLayout box = Ui.col(a);
        box.addView(isb(a), Ui.lp(Ui.MATCH, Ui.WRAP));
        box.addView(contests(a), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
        return box;
    }

    private static View isb(MainActivity a) {
        String q = ISB[(int) Math.floorMod(LocalDate.now().toEpochDay(), (long) ISB.length)];
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.press(Ui.fill(Ui.BLACK, 4), 4));
        Ui.pad(card, 14, 10, 14, 10);
        LinearLayout head = Ui.row(a);
        head.addView(Ui.label(a, "isb question of the day", Ui.OCHRE), Ui.lp(0, Ui.WRAP, 1));
        head.addView(Ui.text(a, openIsb ? "Hide" : "2:00", 12, Ui.MUTED, Ui.medium));
        card.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView qt = Ui.text(a, q, 14, Ui.CREAM, Ui.medium);
        if (!openIsb) {
            qt.setSingleLine(true);
            qt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        card.addView(qt, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 4, 0, 0));
        card.setOnClickListener(v -> {
            openIsb = !openIsb;
            if (!openIsb) timerEnds = 0;
            a.refresh();
        });
        if (!openIsb) return card;

        LinearLayout tr = Ui.row(a);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        TextView clock = Ui.display(a, "2:00", 44, Ui.CREAM);
        clock.setFontFeatureSettings("tnum");
        tr.addView(clock, Ui.lp(0, Ui.WRAP, 1));
        TextView go = Ui.button(a, timerEnds > 0 ? "Reset" : "Start answering", Ui.OCHRE, Ui.BLACK, null);
        tr.addView(go);
        card.addView(tr, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        TextView tip = Ui.text(a, "Answer out loud. Aim for a clear point, one example, and a finish before the buzz.",
                12, Ui.MUTED, Ui.regular);
        card.addView(tip, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
        go.setOnClickListener(v -> {
            timerEnds = timerEnds > 0 ? 0 : SystemClock.elapsedRealtime() + 120_000;
            a.refresh();
        });
        if (timerEnds > 0) {
            Handler h = new Handler(Looper.getMainLooper());
            h.post(new Runnable() {
                boolean seen;
                int waits;

                @Override
                public void run() {
                    if (clock.isAttachedToWindow()) seen = true;
                    else if (seen || ++waits > 25) return;
                    if (timerEnds == 0) return;
                    long left = timerEnds - SystemClock.elapsedRealtime();
                    if (left <= 0) {
                        timerEnds = 0;
                        clock.setText("TIME");
                        go.setText("Again");
                        try {
                            Vibrator vib = (Vibrator) a.getSystemService(Context.VIBRATOR_SERVICE);
                            if (vib != null) vib.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE));
                        } catch (Exception ignored) {
                        }
                        return;
                    }
                    long sec = (left + 999) / 1000;
                    clock.setText(sec / 60 + ":" + String.format(Locale.ROOT, "%02d", sec % 60));
                    h.postDelayed(this, 200);
                }
            });
        }
        return card;
    }

    private static View contests(MainActivity a) {
        LinearLayout card = Ui.col(a);
        card.setBackground(Ui.press(Ui.fill(Ui.BLACK, 4), 4));
        Ui.pad(card, 14, 10, 14, 10);
        List<JSONObject> list = upcoming(a);
        LinearLayout head = Ui.row(a);
        head.addView(Ui.label(a, "codeforces", Ui.OCHRE), Ui.lp(0, Ui.WRAP, 1));
        head.addView(Ui.text(a, openCf ? "Hide" : list.isEmpty() ? "" : list.size() + " coming", 12, Ui.MUTED, Ui.medium));
        card.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP));
        card.setOnClickListener(v -> {
            openCf = !openCf;
            a.refresh();
        });
        if (list.isEmpty()) {
            card.addView(Ui.text(a, "Checking for contests…", 13, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 4, 0, 0));
            refreshContests(a);
            return card;
        }
        JSONObject first = list.get(0);
        if (!openCf) {
            TextView t = Ui.text(a, "Next: " + first.optString("name") + " · " + when(first), 13, Ui.CREAM, Ui.medium);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(t, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 4, 0, 0));
            refreshContests(a);
            return card;
        }
        JSONObject added = Store.obj(Store.root(a), "cfAdded");
        for (JSONObject c : list) {
            LinearLayout r = Ui.row(a);
            r.setMinimumHeight(Ui.dp(48));
            LinearLayout t = Ui.col(a);
            t.addView(Ui.text(a, c.optString("name"), 13, Ui.CREAM, Ui.bold));
            t.addView(Ui.text(a, when(c) + " · " + c.optLong("durationSeconds") / 3600.0 + " h", 12, Ui.MUTED, Ui.regular),
                    Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 2, 0, 0));
            r.addView(t, Ui.lp(0, Ui.WRAP, 1));
            String id = String.valueOf(c.optLong("id"));
            boolean done = added.optBoolean(id, false);
            TextView b = Ui.button(a, done ? "Added" : "Add", done ? Ui.CELL : Ui.OCHRE, done ? Ui.MUTED : Ui.BLACK, null);
            if (!done) {
                b.setOnClickListener(v -> {
                    if (!Cal.allowed(a)) {
                        a.askCalendar();
                        return;
                    }
                    long begin = c.optLong("startTimeSeconds") * 1000L, end = begin + c.optLong("durationSeconds") * 1000L;
                    if (Cal.insert(a, "Codeforces: " + c.optString("name"), begin, end, false, 30)) {
                        Store.put(added, id, true);
                        Store.save(a);
                        a.refresh();
                    } else {
                        Toast.makeText(a, "Couldn't add it to the calendar.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            r.addView(b, Ui.gap(Ui.lp(Ui.WRAP, Ui.dp(38)), 8, 0, 0, 0));
            card.addView(r, Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
        }
        return card;
    }

    private static String when(JSONObject c) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochSecond(c.optLong("startTimeSeconds")), ZoneId.systemDefault());
        LocalDate d = t.toLocalDate(), today = LocalDate.now();
        String day = d.equals(today) ? "today" : d.equals(today.plusDays(1)) ? "tomorrow"
                : d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getDayOfMonth();
        return day + " " + Store.hhmm(t.getHour() * 60 + t.getMinute());
    }

    private static File cacheFile(Context c) {
        return new File(c.getFilesDir(), "codeforces.json");
    }

    /** Up to three contests that haven't started, from the daily cache. */
    static List<JSONObject> upcoming(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            File f = cacheFile(c);
            if (!f.exists()) return out;
            JSONArray all = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis() / 1000;
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o != null && o.optLong("startTimeSeconds") > now) out.add(o);
            }
            out.sort((x, y) -> Long.compare(x.optLong("startTimeSeconds"), y.optLong("startTimeSeconds")));
            while (out.size() > 3) out.remove(out.size() - 1);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static volatile boolean fetching;

    /** Fetches the contest list at most every 6 hours, in the background, then redraws. */
    static void refreshContests(MainActivity a) {
        File f = cacheFile(a);
        if (fetching || (f.exists() && System.currentTimeMillis() - f.lastModified() < 6 * 3_600_000L)) return;
        fetching = true;
        new Thread(() -> {
            boolean changed = false;
            try {
                HttpURLConnection con = (HttpURLConnection) new URL("https://codeforces.com/api/contest.list?gym=false").openConnection();
                con.setConnectTimeout(10_000);
                con.setReadTimeout(15_000);
                try (InputStream in = con.getInputStream()) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                    JSONObject res = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
                    JSONArray list = res.optJSONArray("result"), keep = new JSONArray();
                    for (int i = 0; list != null && i < list.length(); i++) {
                        JSONObject o = list.optJSONObject(i);
                        if (o != null && "BEFORE".equals(o.optString("phase"))) keep.put(o);
                    }
                    Files.write(f.toPath(), keep.toString().getBytes(StandardCharsets.UTF_8));
                    changed = true;
                }
            } catch (Exception ignored) {
            }
            fetching = false;
            if (changed) new Handler(Looper.getMainLooper()).post(() -> {
                if (a.current == MainActivity.CAL) a.refresh();
            });
        }, "codeforces").start();
    }

    // ---- ISB / MBA interview questions, one a day ----

    static final String[] ISB = {
            "Walk me through your journey so far, in under two minutes.",
            "Why an MBA, and why now rather than in five years?",
            "Why ISB over other schools you could apply to?",
            "What do you want to be doing five years after the MBA?",
            "And ten years out: what does success look like for you?",
            "Why move from engineering towards management?",
            "Tell me about a time you led people who didn't report to you.",
            "Describe a project that failed. What was your part in it?",
            "What is the hardest feedback you've received, and what did you change?",
            "Tell me about a decision you made with incomplete information.",
            "Describe a conflict in a team and how you handled it.",
            "What will you contribute to your class that others won't?",
            "Which leader do you admire, and what would you copy from them?",
            "What is one weakness you're actively working on?",
            "Tell me about something you organised from scratch.",
            "How did you handle a deadline you were going to miss?",
            "What's a risk you took that didn't pay off?",
            "Describe a time you changed someone's mind with data.",
            "What would your teammates say is annoying about working with you?",
            "Tell me about a time you had to learn something difficult quickly.",
            "If you weren't admitted this year, what would you do?",
            "How would you explain machine learning to a CFO in one minute?",
            "Which industry would you like to work in after ISB, and why that one?",
            "What trend in Indian business excites you the most right now?",
            "Pick a company you use every day. How would you grow it?",
            "Tell me about an ethical dilemma you faced and what you did.",
            "What does good leadership look like on a bad day?",
            "Describe a time you motivated a team that had lost interest.",
            "How do you decide what not to do?",
            "What's the most useful thing you've built, and who used it?",
            "Tell me about a time you disagreed with someone senior.",
            "How do you handle working with people who are slower than you?",
            "Describe a time you went beyond what was asked of you.",
            "What did organising a college event teach you about people?",
            "How would you persuade a sponsor to fund a student event?",
            "What's a belief you held strongly and later changed?",
            "Describe your ideal first job after the MBA in detail.",
            "Which ISB club or initiative would you join, and what would you bring?",
            "How do you deal with pressure when everything is due at once?",
            "Tell me about a mistake that cost someone else time or money.",
            "What does India's tech sector need more of: builders or managers?",
            "If you could fix one thing about your college, what would it be and how?",
            "How do you make sure a team actually follows through on a plan?",
            "What's a recent news story that you think matters, and why?",
            "Describe a time you had to say no to someone important.",
            "How do you measure whether something you did was a success?",
            "Tell me about a time you helped someone else succeed.",
            "What would you do in your first 90 days as a product manager?",
            "How do you balance short-term results with long-term goals?",
            "What have you done outside academics that you're proud of?",
            "Tell me about a time you were wrong and how you found out.",
            "What do you do when two people you trust give opposite advice?",
            "Why should we pick you over someone with more work experience?",
            "What is the biggest misconception people have about you?",
            "Describe a process you made faster or simpler.",
            "How do you keep learning when no one is grading you?",
            "What will you do differently from your peers in the two years after ISB?",
            "Tell me about a time you had to adapt a plan halfway through.",
            "If you started a company tomorrow, what would it do?",
            "Is there anything you'd like to ask us, or anything we didn't cover?"
    };
}
