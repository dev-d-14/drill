package app.sthira;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.json.JSONObject;

/** Quick Settings tiles: Sleep, Meditate and Wim Hof, one swipe from anywhere. */
public final class Tiles {

    private Tiles() {
    }

    static void launch(TileService s, String action, int code) {
        Intent i = new Intent(s, MainActivity.class)
                .putExtra(MainActivity.EXTRA_ACTION, action)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                s.startActivityAndCollapse(PendingIntent.getActivity(s, code, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                startLegacy(s, i);
            }
        } catch (Exception e) {
            s.startActivity(i);
        }
    }

    @SuppressWarnings("deprecation")
    private static void startLegacy(TileService s, Intent i) {
        s.startActivityAndCollapse(i);
    }

    static void show(TileService s, boolean active, String subtitle) {
        Tile t = s.getQsTile();
        if (t == null) return;
        t.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(subtitle);
        t.updateTile();
    }

    /** Starts tonight's tracking (the app opens, because the microphone needs it on screen). */
    public static final class SleepTile extends TileService {
        @Override
        public void onStartListening() {
            JSONObject now = Sleep.active(this);
            String sub;
            if (now != null) {
                sub = now.optLong("target") > 0 ? "Alarm " + Store.hhmm(Sleep.minuteOf(now.optLong("target"))) : "Tracking";
            } else {
                sub = Sleep.MODE_TRACK.equals(Sleep.mode(this)) ? "No alarm" : "Alarm " + Sleep.alarmTime(this);
            }
            show(this, now != null, sub);
        }

        @Override
        public void onClick() {
            launch(this, MainActivity.ACTION_SLEEP, 401);
        }
    }

    public static final class MeditateTile extends TileService {
        @Override
        public void onStartListening() {
            show(this, false, Store.medMinutes(this) + " min");
        }

        @Override
        public void onClick() {
            launch(this, MainActivity.ACTION_MEDITATE, 402);
        }
    }

    public static final class WimHofTile extends TileService {
        @Override
        public void onStartListening() {
            show(this, false, WimHof.rounds(this) + " × " + WimHof.breaths(this));
        }

        @Override
        public void onClick() {
            launch(this, MainActivity.ACTION_WIMHOF, 403);
        }
    }
}
