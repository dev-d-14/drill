package app.sthira;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Colours, type and the small set of view builders every screen shares. */
final class Ui {

    static final int RED = 0xFF9D2F21, BLACK = 0xFF140A08, OCHRE = 0xFFE2A32B, CREAM = 0xFFF3E3B8,
            JADE = 0xFF5FB89A, MUTED = 0xFFCDBE96, CELL = 0xFF2A1512, SHADOW = 0xFF3A0707;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final int SCROLL_ID = View.generateViewId();

    static float d = 1f;
    static Typeface bebas = Typeface.DEFAULT_BOLD, regular = Typeface.DEFAULT,
            medium = Typeface.DEFAULT, bold = Typeface.DEFAULT_BOLD;

    private Ui() {
    }

    static void init(Context c) {
        d = c.getResources().getDisplayMetrics().density;
        try {
            bebas = Typeface.createFromAsset(c.getAssets(), "fonts/BebasNeue-Regular.ttf");
        } catch (Exception e) {
            bebas = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        }
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        bold = Typeface.create("sans-serif", Typeface.BOLD);
    }

    static int dp(float v) {
        return Math.round(v * d);
    }

    static TextView text(Context c, CharSequence s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView label(Context c, String s, int color) {
        TextView t = text(c, s.toUpperCase(Locale.ROOT), 12, color, bold);
        t.setLetterSpacing(0.12f);
        return t;
    }

    static TextView display(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color, bebas);
        t.setLetterSpacing(0.03f);
        return t;
    }

    static Drawable fill(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    static Drawable stroke(int color, float r, boolean dashed) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0x00000000);
        g.setCornerRadius(dp(r));
        if (dashed) g.setStroke(dp(2), color, dp(6), dp(4));
        else g.setStroke(dp(2), color);
        return g;
    }

    /** Wraps a background so the view shows a touch ripple. */
    static Drawable press(Drawable base, float r) {
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), base, fill(0xFFFFFFFF, r));
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static LinearLayout.LayoutParams gap(LinearLayout.LayoutParams p, float l, float t, float r, float b) {
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    static void pad(View v, float l, float t, float r, float b) {
        v.setPadding(dp(l), dp(t), dp(r), dp(b));
    }

    static TextView button(Context c, String s, int bg, int fg, View.OnClickListener l) {
        TextView t = text(c, s, 14, fg, bold);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48));
        pad(t, 14, 0, 14, 0);
        t.setBackground(press(fill(bg, 4), 4));
        t.setOnClickListener(l);
        return t;
    }

    static TextView ghost(Context c, String s, int color, boolean dashed, View.OnClickListener l) {
        TextView t = text(c, s, 14, color, bold);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48));
        pad(t, 14, 0, 14, 0);
        t.setBackground(press(stroke(color, 4, dashed), 4));
        t.setOnClickListener(l);
        return t;
    }

    /** A card row: 64dp tall at least, rounded, optionally clickable. */
    static LinearLayout card(Context c, int bg, View.OnClickListener l) {
        LinearLayout r = row(c);
        pad(r, 14, 10, 14, 10);
        r.setMinimumHeight(dp(64));
        r.setBackground(l == null ? fill(bg, 4) : press(fill(bg, 4), 4));
        if (l != null) r.setOnClickListener(l);
        return r;
    }

    /** Two stacked lines for the left side of a card. */
    static LinearLayout titled(Context c, String title, String sub, int titleColor, int subColor) {
        LinearLayout v = col(c);
        v.addView(display(c, title, 24, titleColor));
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, sub, 13, subColor, regular);
            v.addView(s, gap(lp(WRAP, WRAP), 0, 3, 0, 0));
        }
        return v;
    }

    /** The black band at the top of every tab: small label, big title, optional control on the right. */
    static LinearLayout header(Context c, String label, String title, View right) {
        LinearLayout band = row(c);
        band.setBackgroundColor(BLACK);
        band.setGravity(Gravity.BOTTOM);
        pad(band, 20, 22, 20, 12);
        LinearLayout left = col(c);
        left.addView(label(c, label, OCHRE));
        // Long titles step down in size so they stay on one line.
        int n = title.length();
        TextView t = display(c, title, n <= 8 ? 56 : n <= 10 ? 46 : n <= 13 ? 38 : 30, CREAM);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        left.addView(t, gap(lp(MATCH, WRAP), 0, 4, 0, 0));
        band.addView(left, lp(0, WRAP, 1));
        if (right != null) band.addView(right);
        return band;
    }

    /** A tab page: header band on top, scrolling body on the red ground. */
    static LinearLayout page(Context c, View header, LinearLayout body) {
        LinearLayout page = col(c);
        page.setBackgroundColor(RED);
        page.addView(header, lp(MATCH, WRAP));
        ScrollView scroll = new ScrollView(c);
        scroll.setId(SCROLL_ID);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        pad(body, 20, 14, 20, 16);
        scroll.addView(body, new FrameLayout.LayoutParams(MATCH, WRAP));
        page.addView(scroll, lp(MATCH, 0, 1));
        return page;
    }

    static View iconButton(Context c, int icon, int color, boolean outlined, String description, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(press(outlined ? stroke(color, 4, false) : fill(CELL, 4), 4));
        Icon i = new Icon(c, icon, color);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(22), dp(22));
        p.gravity = Gravity.CENTER;
        f.addView(i, p);
        f.setContentDescription(description);
        f.setOnClickListener(l);
        f.setMinimumWidth(dp(46));
        f.setMinimumHeight(dp(46));
        return f;
    }

    // ---- dialogs ----

    interface TextResult {
        void done(String value);
    }

    interface NumberResult {
        void done(int value);
    }

    static void prompt(Context c, String title, String initial, TextResult r) {
        EditText e = new EditText(c);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText(initial == null ? "" : initial);
        e.setSelection(e.getText().length());
        FrameLayout wrap = new FrameLayout(c);
        pad(wrap, 20, 8, 20, 0);
        wrap.addView(e);
        new AlertDialog.Builder(c).setTitle(title).setView(wrap)
                .setPositiveButton("Save", (dlg, w) -> {
                    String v = e.getText().toString().trim();
                    if (!v.isEmpty()) r.done(v);
                })
                .setNegativeButton("Cancel", null).show();
    }

    static NumberPicker picker(Context c, int min, int max, int value) {
        NumberPicker p = new NumberPicker(c);
        p.setMinValue(min);
        p.setMaxValue(max);
        p.setValue(Math.max(min, Math.min(max, value)));
        p.setWrapSelectorWheel(false);
        return p;
    }

    static void pickNumber(Context c, String title, int min, int max, int value, NumberResult r) {
        NumberPicker p = picker(c, min, max, value);
        FrameLayout wrap = new FrameLayout(c);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP);
        lp.gravity = Gravity.CENTER;
        wrap.addView(p, lp);
        new AlertDialog.Builder(c).setTitle(title).setView(wrap)
                .setPositiveButton("Save", (dlg, w) -> {
                    p.clearFocus();
                    r.done(p.getValue());
                })
                .setNegativeButton("Cancel", null).show();
    }

    static void confirm(Context c, String title, String message, String yes, Runnable r) {
        new AlertDialog.Builder(c).setTitle(title).setMessage(message)
                .setPositiveButton(yes, (dlg, w) -> r.run())
                .setNegativeButton("Cancel", null).show();
    }

    // ---- icons, drawn on a 24-unit grid ----

    static final int I_SUN = 0, I_CAL = 1, I_BELL = 2, I_LINES = 3, I_BARS = 4, I_CHECK = 5, I_LEFT = 6,
            I_RIGHT = 7, I_MOON = 8, I_SWAP = 9, I_PLUS = 10, I_MINUS = 11, I_CLOSE = 12, I_GEAR = 13;

    static final class Icon extends View {
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Icon(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int color) {
            p.setColor(color);
            invalidate();
        }

        private void line(Canvas c, float x0, float y0, float x1, float y1) {
            c.drawLine(x0, y0, x1, y1, p);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            c.save();
            c.scale(s, s);
            p.setStrokeWidth(kind == I_CHECK || kind == I_LEFT || kind == I_RIGHT ? 2.6f : 2f);
            path.reset();
            switch (kind) {
                case I_SUN:
                    c.drawCircle(12, 12, 4.5f, p);
                    line(c, 12, 2.5f, 12, 5.5f);
                    line(c, 12, 18.5f, 12, 21.5f);
                    line(c, 2.5f, 12, 5.5f, 12);
                    line(c, 18.5f, 12, 21.5f, 12);
                    break;
                case I_CAL:
                    c.drawRoundRect(new RectF(4, 5, 20, 20), 1, 1, p);
                    line(c, 4, 10, 20, 10);
                    line(c, 8, 3, 8, 7);
                    line(c, 16, 3, 16, 7);
                    break;
                case I_BELL:
                    line(c, 4, 9, 4, 15);
                    line(c, 7, 6, 7, 18);
                    line(c, 17, 6, 17, 18);
                    line(c, 20, 9, 20, 15);
                    line(c, 7, 12, 17, 12);
                    break;
                case I_LINES:
                    line(c, 5, 7, 19, 7);
                    line(c, 5, 12, 19, 12);
                    line(c, 5, 17, 14, 17);
                    break;
                case I_BARS:
                    line(c, 5, 20, 5, 11);
                    line(c, 12, 20, 12, 4);
                    line(c, 19, 20, 19, 14);
                    break;
                case I_CHECK:
                    path.moveTo(5, 12);
                    path.lineTo(10, 17);
                    path.lineTo(19, 7);
                    c.drawPath(path, p);
                    break;
                case I_LEFT:
                    path.moveTo(15, 5);
                    path.lineTo(8, 12);
                    path.lineTo(15, 19);
                    c.drawPath(path, p);
                    break;
                case I_RIGHT:
                    path.moveTo(9, 5);
                    path.lineTo(16, 12);
                    path.lineTo(9, 19);
                    c.drawPath(path, p);
                    break;
                case I_MOON:
                    path.moveTo(20, 14.5f);
                    path.cubicTo(15, 16.5f, 8, 12.5f, 9.5f, 4);
                    path.cubicTo(3, 6, 2.5f, 15, 8, 19);
                    path.cubicTo(12.5f, 22, 18.5f, 19.5f, 20, 14.5f);
                    c.drawPath(path, p);
                    break;
                case I_SWAP:
                    path.moveTo(4, 8);
                    path.lineTo(18, 8);
                    path.lineTo(14, 4);
                    path.moveTo(20, 16);
                    path.lineTo(6, 16);
                    path.lineTo(10, 20);
                    c.drawPath(path, p);
                    break;
                case I_PLUS:
                    line(c, 12, 5, 12, 19);
                    line(c, 5, 12, 19, 12);
                    break;
                case I_MINUS:
                    line(c, 5, 12, 19, 12);
                    break;
                case I_CLOSE:
                    line(c, 6, 6, 18, 18);
                    line(c, 18, 6, 6, 18);
                    break;
                default:
                    c.drawCircle(12, 12, 3, p);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI * i / 4;
                        line(c, 12 + 6.5f * (float) Math.cos(a), 12 + 6.5f * (float) Math.sin(a),
                                12 + 9 * (float) Math.cos(a), 12 + 9 * (float) Math.sin(a));
                    }
            }
            c.restore();
        }
    }

    /** The meditation ring: a faint track and a progress arc that empties clockwise. */
    static final class Ring extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        float progress = 1f;

        Ring(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void set(float progress) {
            this.progress = Math.max(0f, Math.min(1f, progress));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = dp(10);
            p.setStrokeWidth(w);
            box.set(w / 2, w / 2, getWidth() - w / 2, getHeight() - w / 2);
            p.setColor(CELL);
            c.drawOval(box, p);
            p.setColor(JADE);
            c.drawArc(box, -90, 360f * progress, false, p);
        }
    }
}
