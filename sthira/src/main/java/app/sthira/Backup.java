package app.sthira;

import android.app.AlertDialog;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Backup to one file in Google Drive, through Android's file picker: you create the file once,
 * Sthira keeps permission to it and overwrites it every week. No Google sign-in inside the app.
 * Everything (habits, plans, sleep nights, digest saves) lives in state.json, so that's the backup.
 */
public final class Backup {

    static final int REQ_CREATE = 31, REQ_OPEN = 32;
    private static final int JOB_ID = 51;

    private Backup() {
    }

    static JSONObject info(Context c) {
        return Store.obj(Store.root(c), "backup");
    }

    static Uri target(Context c) {
        String u = info(c).optString("uri", "");
        return u.isEmpty() ? null : Uri.parse(u);
    }

    // ---- choosing the file ----

    static void choose(MainActivity a) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "sthira-backup.json");
        try {
            a.startActivityForResult(i, REQ_CREATE);
        } catch (Exception e) {
            Toast.makeText(a, "No file picker found.", Toast.LENGTH_SHORT).show();
        }
    }

    static void pickRestore(MainActivity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            a.startActivityForResult(i, REQ_OPEN);
        } catch (Exception e) {
            Toast.makeText(a, "No file picker found.", Toast.LENGTH_SHORT).show();
        }
    }

    /** From MainActivity.onActivityResult. */
    static void onResult(MainActivity a, int code, Intent data) {
        Uri uri = data == null ? null : data.getData();
        if (uri == null) return;
        if (code == REQ_CREATE) {
            try {
                a.getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            Store.put(info(a), "uri", uri.toString());
            Store.save(a);
            schedule(a);
            now(a, ok -> {
                Toast.makeText(a, ok ? "Backed up. It will update every week." : "Couldn't write the backup file.",
                        Toast.LENGTH_LONG).show();
                a.refresh();
            });
        } else if (code == REQ_OPEN) {
            new Thread(() -> {
                String text = read(a, uri);
                new Handler(Looper.getMainLooper()).post(() -> confirmRestore(a, text));
            }, "restore").start();
        }
    }

    private static void confirmRestore(MainActivity a, String text) {
        if (text == null) {
            Toast.makeText(a, "Couldn't read that file.", Toast.LENGTH_LONG).show();
            return;
        }
        String when = "";
        try {
            JSONObject o = new JSONObject(text);
            long t = o.optJSONObject("backup") == null ? 0 : o.optJSONObject("backup").optLong("last", 0);
            if (t > 0) when = " from " + date(t);
        } catch (Exception e) {
            Toast.makeText(a, "That isn't a Sthira backup.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Restore backup" + when + "?")
                .setMessage("Everything in Sthira now is replaced by the backup. A copy of the current data is kept on the phone.")
                .setPositiveButton("Restore", (d, w) -> {
                    boolean ok = Store.replace(a, text);
                    Toast.makeText(a, ok ? "Restored." : "That isn't a Sthira backup.", Toast.LENGTH_LONG).show();
                    Reminders.scheduleAll(a);
                    a.refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- writing ----

    interface Done {
        void done(boolean ok);
    }

    /** Writes the backup on a background thread; `done` runs on the main thread. */
    static void now(Context c, Done done) {
        Context app = c.getApplicationContext();
        Uri uri = target(app);
        Handler main = new Handler(Looper.getMainLooper());
        if (uri == null) {
            if (done != null) main.post(() -> done.done(false));
            return;
        }
        long stamp = System.currentTimeMillis();
        new Thread(() -> {
            boolean ok = write(app, uri, stamp);
            main.post(() -> {
                if (ok) {
                    Store.put(info(app), "last", stamp);
                    info(app).remove("failed");
                } else {
                    Store.put(info(app), "failed", stamp);
                }
                Store.save(app);
                if (done != null) done.done(ok);
            });
        }, "backup").start();
    }

    private static boolean write(Context c, Uri uri, long stamp) {
        String json;
        try {
            JSONObject copy = new JSONObject(Store.snapshot(c));
            Store.put(Store.obj(copy, "backup"), "last", stamp);
            json = copy.toString();
        } catch (Exception e) {
            return false;
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        for (String mode : new String[]{"wt", "w"}) {
            try (OutputStream out = c.getContentResolver().openOutputStream(uri, mode)) {
                if (out == null) continue;
                out.write(bytes);
                out.flush();
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static String read(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- weekly ----

    static void schedule(Context c) {
        if (target(c) == null) return;
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        try {
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, Job.class))
                    .setPeriodic(7L * 24 * 3_600_000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build());
        } catch (Exception ignored) {
        }
    }

    public static final class Job extends JobService {
        @Override
        public boolean onStartJob(JobParameters params) {
            now(this, ok -> jobFinished(params, !ok));
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return true;
        }
    }

    // ---- the block on Review ----

    static View section(MainActivity a) {
        LinearLayout box = Ui.col(a);
        box.addView(Ui.label(a, "backup", Ui.CREAM), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 22, 0, 2));
        JSONObject in = info(a);
        Uri uri = target(a);
        LinearLayout row = Ui.card(a, Ui.BLACK, uri == null ? v -> choose(a) : v -> now(a, ok -> {
            Toast.makeText(a, ok ? "Backed up." : "Backup failed. Check that Drive is online, or pick the file again.",
                    Toast.LENGTH_LONG).show();
            a.refresh();
        }));
        row.setMinimumHeight(Ui.dp(52));
        LinearLayout t = Ui.col(a);
        t.addView(Ui.text(a, uri == null ? "Back up to Google Drive" : "Back up now", 14, Ui.CREAM, Ui.medium));
        String sub;
        if (uri == null) sub = "Pick Drive in the file picker and save. Then it updates itself every week.";
        else if (in.optLong("last") > 0) sub = "Last backup " + date(in.optLong("last")) + ". Weekly.";
        else sub = "Not backed up yet.";
        if (in.optLong("failed") > in.optLong("last")) sub += " Last try failed.";
        t.addView(Ui.text(a, sub, 12, Ui.MUTED, Ui.regular), Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 2, 0, 0));
        row.addView(t, Ui.lp(0, Ui.WRAP, 1));
        row.setLayoutParams(Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
        box.addView(row);

        LinearLayout links = Ui.row(a);
        TextView restore = Ui.text(a, "Restore from a backup", 13, Ui.OCHRE, Ui.medium);
        restore.setPadding(0, Ui.dp(12), Ui.dp(16), Ui.dp(12));
        restore.setOnClickListener(v -> pickRestore(a));
        links.addView(restore);
        if (uri != null) {
            TextView change = Ui.text(a, "Change file", 13, Ui.MUTED, Ui.medium);
            change.setPadding(0, Ui.dp(12), 0, Ui.dp(12));
            change.setOnClickListener(v -> choose(a));
            links.addView(change);
        }
        box.addView(links);
        return box;
    }

    static String date(long ms) {
        LocalDate d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate();
        if (d.equals(LocalDate.now())) return "today";
        if (d.equals(LocalDate.now().minusDays(1))) return "yesterday";
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }
}
