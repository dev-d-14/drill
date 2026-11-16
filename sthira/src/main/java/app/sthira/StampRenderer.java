package app.sthira;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Draws the day as a perforated maroon postage stamp, the same design as the Ashtavakra widget:
 * cream sheet, maroon panel, halftone sun, print grain, punched edges.
 */
final class StampRenderer {

    static final int CREAM = 0xFFF0E2D3;
    static final int MAROON = 0xFF7A0E22;
    private static final int INK = 0xFF5C0918;
    static final int PENDING = 0, DONE = 1, NEXT = 2, EVENT = 3;

    static final class Line {
        final String text;
        final int state, minute;

        Line(String text, int state, int minute) {
            this.text = text;
            this.state = state;
            this.minute = minute;
        }
    }

    static final class Model {
        String number = "", unit = "", footLeft = "", footRight = "";
        List<Line> lines = new ArrayList<>();
    }

    private StampRenderer() {
    }

    /**
     * @param width  bitmap width in pixels
     * @param height bitmap height in pixels
     * @param k      pixels per dp
     */
    static Bitmap render(int width, int height, float k, Model model) {
        int w = Math.max(width, 64);
        int h = Math.max(height, 64);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float m = 3 * k;    // gap between the bitmap edge and the sheet
        float r = 4.2f * k; // perforation radius
        float b = 11 * k;   // cream border width

        // Cream sheet and maroon panel.
        paint.setColor(CREAM);
        canvas.drawRect(m, m, w - m, h - m, paint);
        float px0 = m + b, py0 = m + b, px1 = w - m - b, py1 = h - m - b;
        paint.setColor(MAROON);
        canvas.drawRect(px0, py0, px1, py1, paint);

        // Halftone sun rising out of the bottom-right corner of the panel.
        canvas.save();
        canvas.clipRect(px0, py0, px1, py1);
        paint.setColor(CREAM);
        paint.setAlpha(40);
        float pitch = 5.2f * k;
        float reach = Math.min(px1 - px0, py1 - py0) * 0.95f;
        int n = (int) (reach / pitch) + 2;
        for (int i = -n; i <= n; i++) {
            for (int j = -n; j <= n; j++) {
                float x = px1 + (i - j) * pitch * 0.7071f;
                float y = py1 + (i + j) * pitch * 0.7071f;
                if (x < px0 || x > px1 || y < py0 || y > py1) continue;
                float dist = (float) Math.hypot(x - px1, y - py1) / reach;
                if (dist >= 1f) continue;
                float rad = pitch * 0.52f * (float) Math.pow(1f - dist, 0.9);
                if (rad < 0.35f * k) continue;
                canvas.drawCircle(x, y, rad, paint);
            }
        }
        canvas.restore();

        // Print grain: cream flecks on the panel, ink flecks on the border.
        Random random = new Random(7);
        int flecks = (int) ((w / k) * (h / k) * 0.05f);
        for (int i = 0; i < flecks; i++) {
            float x = m + random.nextFloat() * (w - 2 * m);
            float y = m + random.nextFloat() * (h - 2 * m);
            float rr = (0.25f + random.nextFloat() * 0.55f) * k;
            boolean inside = x > px0 && x < px1 && y > py0 && y < py1;
            if (inside) {
                paint.setColor(CREAM);
                paint.setAlpha(18 + random.nextInt(42));
            } else {
                paint.setColor(INK);
                paint.setAlpha(25 + random.nextInt(55));
            }
            canvas.drawCircle(x, y, rr, paint);
        }
        paint.setAlpha(255);

        float pad = 10 * k;
        float x0 = px0 + pad, x1 = px1 - pad;
        float top = py0 + pad * 0.75f;
        float innerH = py1 - py0;
        float numSize = Math.max(26 * k, Math.min(54 * k, innerH * 0.2f));

        Typeface black = Typeface.create("sans-serif-black", Typeface.NORMAL);
        Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

        // Day of the month, set like a denomination.
        TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(CREAM);
        text.setTypeface(black);
        text.setTextSize(numSize);
        Rect bounds = new Rect();
        text.getTextBounds("0123456789", 0, 10, bounds);
        float numBase = top - bounds.top;
        canvas.drawText(model.number, x0, numBase, text);

        // Month and weekday under the number.
        float labelSize = numSize * 0.30f;
        text.setTypeface(bold);
        text.setTextSize(labelSize);
        text.setLetterSpacing(0.10f);
        float labelBase = numBase + numSize * 0.16f + labelSize * 0.85f;
        canvas.drawText(model.unit, x0 + numSize * 0.04f, labelBase, text);
        text.setLetterSpacing(0f);
        float headBottom = labelBase + labelSize * 0.35f;

        // Title block, top right.
        text.setTextAlign(Paint.Align.RIGHT);
        float devaSize = numSize * 0.50f;
        text.setTextSize(devaSize);
        canvas.drawText("स्थिर", x1, top + devaSize * 0.80f, text);
        text.setTypeface(black);
        text.setTextSize(numSize * 0.36f);
        text.setLetterSpacing(0.14f);
        canvas.drawText("TODAY", x1, numBase, text);

        // Footer: progress on the left, what's next on the right, under a thin rule.
        float footSize = Math.max(8.5f * k, numSize * 0.2f);
        text.setTypeface(bold);
        text.setTextSize(footSize);
        text.setLetterSpacing(0.16f);
        float footBase = py1 - pad * 0.9f;
        canvas.drawText(model.footRight, x1, footBase, text);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(model.footLeft, x0, footBase, text);
        float ruleY = footBase - footSize - pad * 0.5f;
        paint.setColor(CREAM);
        canvas.drawRect(x0, ruleY, x1, ruleY + Math.max(1f, 0.8f * k), paint);
        text.setLetterSpacing(0f);

        // The day's lines, sized to fill the space between the header and the rule.
        float bodyTop = headBottom + pad * 0.7f;
        float bodyBottom = ruleY - pad * 0.5f;
        float avail = Math.max(1f, bodyBottom - bodyTop);
        List<Line> lines = new ArrayList<>(model.lines);
        int fit = Math.max(1, (int) (avail / (15 * k)));
        if (lines.size() > fit) {
            int extra = lines.size() - (fit - 1);
            lines = new ArrayList<>(lines.subList(0, fit - 1));
            lines.add(new Line("+ " + extra + " more", EVENT, 0));
        }
        if (lines.isEmpty()) lines.add(new Line("Nothing planned", EVENT, 0));
        float rowH = Math.min(avail / lines.size(), 34 * k);
        float size = Math.min(rowH * 0.56f, 18 * k);
        canvas.save();
        canvas.clipRect(px0, py0, px1, bodyBottom);
        Path tick = new Path();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            float cy = bodyTop + rowH * (i + 0.5f);
            boolean next = line.state == NEXT;
            if (next) {
                paint.setColor(CREAM);
                canvas.drawRoundRect(new RectF(x0 - 5 * k, cy - rowH * 0.44f, x1 + 5 * k, cy + rowH * 0.44f), 3 * k, 3 * k, paint);
            }
            int ink = next ? MAROON : CREAM;
            float mx = x0 + size * 0.45f, mr = size * 0.36f;
            paint.setColor(ink);
            if (line.state == DONE) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(mx, cy, mr, paint);
                paint.setColor(MAROON);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, size * 0.11f));
                tick.reset();
                tick.moveTo(mx - mr * 0.5f, cy);
                tick.lineTo(mx - mr * 0.1f, cy + mr * 0.4f);
                tick.lineTo(mx + mr * 0.55f, cy - mr * 0.4f);
                canvas.drawPath(tick, paint);
                paint.setStyle(Paint.Style.FILL);
            } else if (line.state == EVENT) {
                canvas.drawRect(mx - size * 0.07f, cy - mr, mx + size * 0.07f, cy + mr, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, size * 0.11f));
                canvas.drawCircle(mx, cy, mr * 0.9f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            text.setColor(ink);
            text.setAlpha(line.state == DONE ? 170 : 255);
            text.setTypeface(next ? bold : medium);
            text.setTextSize(size);
            float tx = x0 + size * 1.25f;
            CharSequence shown = TextUtils.ellipsize(line.text, text, Math.max(1f, x1 - tx), TextUtils.TruncateAt.END);
            canvas.drawText(shown, 0, shown.length(), tx, cy + size * 0.36f, text);
        }
        text.setAlpha(255);
        canvas.restore();

        // Punch the perforations: half-circle bites along every edge of the sheet.
        Paint punch = new Paint(Paint.ANTI_ALIAS_FLAG);
        punch.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        float sheetW = w - 2 * m, sheetH = h - 2 * m;
        int across = Math.max(3, Math.round(sheetW / (r * 3.1f)));
        int down = Math.max(3, Math.round(sheetH / (r * 3.1f)));
        for (int i = 0; i < across; i++) {
            float x = m + (i + 0.5f) * sheetW / across;
            canvas.drawCircle(x, m, r, punch);
            canvas.drawCircle(x, h - m, r, punch);
        }
        for (int i = 0; i < down; i++) {
            float y = m + (i + 0.5f) * sheetH / down;
            canvas.drawCircle(m, y, r, punch);
            canvas.drawCircle(w - m, y, r, punch);
        }
        return bitmap;
    }
}
