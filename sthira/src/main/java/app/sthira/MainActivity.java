package app.sthira;

import android.Manifest;
import android.animation.LayoutTransition;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.YearMonth;

/** The one activity: a stage that screens slide across, and the tab bar under it. */
public final class MainActivity extends Activity {

    static final int TODAY = 0, CAL = 1, TRAIN = 2, DIGEST = 3, REVIEW = 4, PLANS = 5, MEDITATE = 6, SHUTDOWN = 7;
    static final String EXTRA_OPEN = "open";

    final Handler handler = new Handler(Looper.getMainLooper());
    LocalDate day = LocalDate.now();          // the day shown on Today
    YearMonth calMonth = YearMonth.now();     // the month shown on Calendar
    LocalDate calDay = LocalDate.now();       // the day selected on Calendar
    String editPlan;                          // plan being edited on Plans, or null
    int current = -1;

    private FrameLayout stage;
    private LinearLayout nav;
    private View currentView;
    private LocalDate lastSeen = LocalDate.now();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BLACK);
        stage = new FrameLayout(this);
        root.addView(stage, Ui.lp(Ui.MATCH, 0, 1));
        nav = Ui.row(this);
        nav.setBackgroundColor(Ui.BLACK);
        root.addView(nav, Ui.lp(Ui.MATCH, Ui.WRAP));
        root.setLayoutTransition(new LayoutTransition());
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return insets;
            });
        }

        int first = getIntent() == null ? TODAY : getIntent().getIntExtra(EXTRA_OPEN, TODAY);
        open(first);
        Reminders.scheduleAll(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !Store.root(this).optBoolean("askedNotify", false)) {
            Store.put(Store.root(this), "askedNotify", true);
            Store.save(this);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasExtra(EXTRA_OPEN)) open(intent.getIntExtra(EXTRA_OPEN, TODAY));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!LocalDate.now().equals(lastSeen)) {
            lastSeen = LocalDate.now();
            day = lastSeen;
        }
        if (current >= 0) refresh();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        refresh();
        AgendaWidget.updateAll(this);
    }

    void askCalendar() {
        requestPermissions(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, 1);
    }

    private static int tabOf(int screen) {
        return screen == PLANS ? TRAIN : screen;
    }

    private View build(int screen) {
        switch (screen) {
            case CAL:
                return CalendarScreen.build(this);
            case TRAIN:
                return TrainScreen.build(this);
            case PLANS:
                return PlansScreen.build(this);
            case DIGEST:
                return DigestScreen.build(this);
            case REVIEW:
                return ReviewScreen.build(this);
            case MEDITATE:
                return MeditateScreen.build(this);
            case SHUTDOWN:
                return ShutdownScreen.build(this);
            default:
                return TodayScreen.build(this);
        }
    }

    /** Moves to a screen with the transition that matches the direction of travel. */
    void open(int screen) {
        boolean full = screen >= MEDITATE, wasFull = current >= MEDITATE;
        int anim;
        if (current < 0 || screen == current) anim = 0;
        else if (full) anim = 2;
        else if (wasFull) anim = -2;
        else if (tabOf(screen) == tabOf(current)) anim = screen == PLANS ? 1 : -1;
        else anim = Integer.signum(tabOf(screen) - tabOf(current));
        current = screen;
        swap(build(screen), anim);
        nav.setVisibility(full ? View.GONE : View.VISIBLE);
        drawNav();
    }

    /** Rebuilds the current screen in place, keeping the scroll position. */
    void refresh() {
        if (current < 0) return;
        int y = 0;
        View old = currentView == null ? null : currentView.findViewById(Ui.SCROLL_ID);
        if (old instanceof ScrollView) y = old.getScrollY();
        swap(build(current), 0);
        View now = currentView.findViewById(Ui.SCROLL_ID);
        if (now instanceof ScrollView && y > 0) {
            final int scrollTo = y;
            now.post(() -> now.scrollTo(0, scrollTo));
        }
        drawNav();
    }

    private void swap(View next, int anim) {
        final View old = currentView;
        stage.addView(next, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        currentView = next;
        if (old == null) return;
        old.animate().cancel();
        if (anim == 0) {
            stage.removeView(old);
            return;
        }
        float w = Math.max(stage.getWidth(), 1);
        DecelerateInterpolator ease = new DecelerateInterpolator(2f);
        if (Math.abs(anim) == 1) {
            next.setTranslationX(anim * w * 0.2f);
            next.setAlpha(0f);
            next.animate().translationX(0).alpha(1f).setDuration(240).setInterpolator(ease);
            old.animate().translationX(-anim * w * 0.2f).alpha(0f).setDuration(180)
                    .withEndAction(() -> stage.removeView(old));
        } else if (anim == 2) {
            next.setScaleX(0.92f);
            next.setScaleY(0.92f);
            next.setAlpha(0f);
            next.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(ease);
            old.animate().alpha(0f).setDuration(200).withEndAction(() -> stage.removeView(old));
        } else {
            next.setAlpha(0f);
            next.animate().alpha(1f).setDuration(220);
            old.animate().scaleX(0.92f).scaleY(0.92f).alpha(0f).setDuration(200).setInterpolator(ease)
                    .withEndAction(() -> stage.removeView(old));
        }
    }

    private void drawNav() {
        nav.removeAllViews();
        boolean digest = !Store.root(this).optBoolean("hideDigest", false);
        int[] tabs = digest ? new int[]{TODAY, CAL, TRAIN, DIGEST, REVIEW} : new int[]{TODAY, CAL, TRAIN, REVIEW};
        String[] names = {"TODAY", "CALENDAR", "TRAIN", "DIGEST", "REVIEW"};
        int[] icons = {Ui.I_SUN, Ui.I_CAL, Ui.I_BELL, Ui.I_LINES, Ui.I_BARS};
        for (int tab : tabs) {
            boolean on = tabOf(current) == tab;
            int color = on ? Ui.OCHRE : Ui.MUTED;
            LinearLayout item = Ui.col(this);
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(Ui.dp(60));
            item.setBackground(Ui.press(Ui.fill(Ui.BLACK, 0), 0));
            View bar = new View(this);
            bar.setBackgroundColor(on ? Ui.OCHRE : Ui.BLACK);
            item.addView(bar, Ui.lp(Ui.MATCH, Ui.dp(3)));
            item.addView(new Ui.Icon(this, icons[tab], color), Ui.gap(Ui.lp(Ui.dp(22), Ui.dp(22)), 0, 8, 0, 4));
            TextView t = Ui.text(this, names[tab], 10, color, Ui.bold);
            t.setLetterSpacing(0.06f);
            item.addView(t, Ui.gap(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, 0, 8));
            item.setContentDescription(names[tab]);
            item.setOnClickListener(v -> {
                if (tab == TODAY) day = LocalDate.now();
                open(tab);
            });
            nav.addView(item, Ui.lp(0, Ui.WRAP, 1));
        }
    }

    @Override
    public void onBackPressed() {
        if (current == PLANS && editPlan != null) {
            editPlan = null;
            refresh();
        } else if (current == PLANS) {
            open(TRAIN);
        } else if (current != TODAY) {
            open(TODAY);
        } else {
            super.onBackPressed();
        }
    }
}
