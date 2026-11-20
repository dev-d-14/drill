package app.sthira;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** What Sthira does with health data, for Health Connect's permission screen. */
public final class PrivacyActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.DEEP);
        LinearLayout page = Ui.col(this);
        Ui.pad(page, 24, 40, 24, 32);
        page.addView(Ui.display(this, "YOUR DATA", 48, Ui.CREAM));
        String[] lines = {
                "Steps: Sthira reads your daily step totals from Health Connect to show them on the Train tab. "
                        + "It never writes or changes steps.",
                "Sleep: Sthira writes one sleep session per night, with estimated stages, so other apps can see it.",
                "Everything stays on this phone. Nothing is uploaded, shared or sold. "
                        + "The sleep tracker keeps numbers only, never audio.",
                "You can take these permissions back any time in Health Connect."};
        for (String l : lines) {
            page.addView(Ui.text(this, l, 15, Ui.CREAM, Ui.regular), Ui.gap(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 16, 0, 0));
        }
        scroll.addView(page);
        setContentView(scroll);
    }
}
