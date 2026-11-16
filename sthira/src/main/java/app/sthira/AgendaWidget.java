package app.sthira;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The big home-screen widget: today drawn as a perforated stamp. Tapping it opens the app. */
public final class AgendaWidget extends AppWidgetProvider {

    private static final String ACTION_REFRESH = "app.sthira.REFRESH";
    private static final int MAX_PIXELS = 1_400_000;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) draw(context, manager, id);
        scheduleMidnight(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        draw(context, manager, id);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action)) {
            updateAll(context);
        }
    }

    @Override
    public void onDisabled(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(refreshIntent(context));
    }

    /** Redraws every placed widget, for example after something is logged or the day changes. */
    static void updateAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, AgendaWidget.class));
            if (ids == null || ids.length == 0) return;
            for (int id : ids) draw(context, manager, id);
            scheduleMidnight(context);
        } catch (Exception ignored) {
        }
    }

    /** Today's lines, in the order of the day: calendar events and the habits with their state. */
    static StampRenderer.Model model(Context c) {
        LocalDate today = LocalDate.now();
        StampRenderer.Model m = new StampRenderer.Model();
        m.number = String.valueOf(today.getDayOfMonth());
        m.unit = today.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT) + " · "
                + today.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);

        List<StampRenderer.Line> lines = new ArrayList<>();
        for (Cal.Ev e : Cal.on(c, today)) {
            lines.add(new StampRenderer.Line((e.allDay ? "All day" : Store.hhmm(e.startMinute())) + "  " + e.title,
                    StampRenderer.EVENT, e.startMinute()));
        }
        JSONObject log = Store.dayOrEmpty(c, today);
        boolean hideDigest = Store.root(c).optBoolean("hideDigest", false);
        String trained = Store.trained(c, today);
        boolean trainDue = Store.isTrainingDay(c, today) || Store.sessionDone(c);
        JSONObject w = Store.nextWorkout(c);
        String workout = Store.sessionDone(c) ? Store.session(c).optString("name") : w == null ? "" : w.optString("name");
        String[] names = {"Digest", "Meditate", "Train · " + workout, "Shutdown"};
        int[] minutes = {Store.minutes(Store.digestTime(c)), Store.minutes(Store.homeTime(c)),
                Store.minutes(Store.trainTime(c)), Store.minutes(Store.shutTime(c))};
        boolean[] shown = {!hideDigest, true, trainDue, true};
        boolean[] done = {log.optBoolean("digest", false), Store.meditated(c, today), !trained.isEmpty(), Store.shutDown(c, today)};
        int next = -1, total = 0, finished = 0;
        for (int i = 0; i < 4; i++) {
            if (!shown[i]) continue;
            total++;
            if (done[i]) finished++;
            else if (next < 0 || minutes[i] < minutes[next]) next = i;
        }
        for (int i = 0; i < 4; i++) {
            if (!shown[i]) continue;
            lines.add(new StampRenderer.Line(names[i], done[i] ? StampRenderer.DONE : i == next ? StampRenderer.NEXT
                    : StampRenderer.PENDING, minutes[i]));
        }
        lines.sort((x, y) -> Integer.compare(x.minute, y.minute));
        m.lines = lines;
        m.footLeft = finished + " / " + total + " DONE";
        m.footRight = next < 0 ? "ALL DONE" : "NEXT: " + names[next].split(" · ")[0].toUpperCase(Locale.ROOT);
        return m;
    }

    private static void draw(Context context, AppWidgetManager manager, int id) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        Bundle options = manager.getAppWidgetOptions(id);
        int widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (widthDp <= 0) widthDp = 320;
        if (heightDp <= 0) heightDp = 320;

        // Keep the bitmap comfortably under the size limit for widget updates.
        float k = metrics.density;
        float pixels = widthDp * k * heightDp * k;
        if (pixels > MAX_PIXELS) k *= (float) Math.sqrt(MAX_PIXELS / pixels);

        StampRenderer.Model model = model(context);
        Bitmap bitmap = StampRenderer.render(Math.round(widthDp * k), Math.round(heightDp * k), k, model);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
        views.setImageViewBitmap(R.id.stamp, bitmap);
        views.setContentDescription(R.id.stamp, "Today, " + model.footLeft.toLowerCase(Locale.ROOT));
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.stamp, PendingIntent.getActivity(
                context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.updateAppWidget(id, views);
    }

    private static PendingIntent refreshIntent(Context context) {
        Intent intent = new Intent(context, AgendaWidget.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(
                context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Asks for a redraw just after the next midnight; the half-hourly system update is the fallback. */
    private static void scheduleMidnight(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        long midnight = LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        alarms.set(AlarmManager.RTC, midnight + 5_000L, refreshIntent(context));
    }
}
