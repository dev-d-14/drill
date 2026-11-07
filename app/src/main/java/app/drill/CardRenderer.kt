package app.drill

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Draws a whole card (thermal background, text, heat bar, buttons) into one bitmap.
 * Glance can't do letter-spacing, textures or custom shapes, so the widget shows this
 * image and lays invisible tap areas on top. Layout constants (dp) must match StudyWidget.
 */
object CardRenderer {
    // keep in sync with StudyWidget's layout
    const val PAD_L = 18f
    const val PAD_T = 17f
    const val PAD_R = 40f
    const val PAD_B = 16f
    const val HEADER_H = 28f
    const val FOOTER_H = 28f
    const val BTN_W = 76f         // buttons are anchored to the right edge at fixed size
    const val BTN_GAP = 8f

    private const val WHITE = 0xFFF4F7FF.toInt()
    private const val YELLOW = 0xFFFAE226.toInt()
    private const val ORANGE = 0xFFFA8E24.toInt()
    private const val NAVY = 0xFF06124A.toInt()
    private const val SHADOW = 0xE6020632.toInt()

    // hottest point of each deck's thermal artwork, in the 330x248 design space
    private val SPOTS = mapOf(
        "ml" to (290f to 140f), "design" to (238f to 115f), "dsa" to (214f to 138f),
        "cs" to (270f to 161f), "hr" to (211f to 118f),
    )
    private val TEMPS = floatArrayOf(41.2f, 36.4f, 31.0f, 26.3f, 21.5f)

    private val BAR_COLORS = intArrayOf(
        0xFF04084A.toInt(), 0xFF0A1E8C.toInt(), 0xFF1C4ED6.toInt(), 0xFF2869E2.toInt(),
        0xFF78C850.toInt(), 0xFFFAE226.toInt(), 0xFFFA8E24.toInt(), 0xFFEC2A4E.toInt(),
        0xFFFFD6D6.toInt(), 0xFFFFFCF6.toInt(),
    )
    private val BAR_POS = floatArrayOf(0f, .20f, .36f, .48f, .52f, .58f, .70f, .82f, .92f, 1f)

    private val cache = HashMap<Int, Bitmap>()

    private fun bgRes(deck: String, flipped: Boolean): Int {
        if (flipped) return R.drawable.thermal_back
        return when (deck) {
            "design" -> R.drawable.thermal_design
            "dsa" -> R.drawable.thermal_dsa
            "cs" -> R.drawable.thermal_cs
            "hr" -> R.drawable.thermal_hr
            else -> R.drawable.thermal_ml
        }
    }

    fun render(ctx: Context, st: Snapshot, wDp: Float, hDp: Float, withButtons: Boolean = true): Bitmap {
        val density = ctx.resources.displayMetrics.density
        val px = minOf(density, 1100f / maxOf(wDp, hDp))
        val w = (wDp * px).toInt().coerceAtLeast(2)
        val h = (hDp * px).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fun d(v: Float) = v * px

        c.clipPath(Path().apply {
            addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), d(16f), d(16f), Path.Direction.CW)
        })

        val card = st.card
        val deck = card?.deck ?: "ml"

        // ---- thermal background (crop to fill, anchored to the right)
        val opts = BitmapFactory.Options().apply { inScaled = false }
        val key = bgRes(deck, st.flipped || card == null)
        val bg = cache.getOrPut(key) { BitmapFactory.decodeResource(ctx.resources, key, opts) }
        val sc = maxOf(w / bg.width.toFloat(), h / bg.height.toFloat())
        val dw = bg.width * sc
        val dh = bg.height * sc
        val ox = w - dw
        val oy = (h - dh) / 2f
        c.drawBitmap(bg, null, RectF(ox, oy, ox + dw, oy + dh), Paint(Paint.FILTER_BITMAP_FLAG))

        if (card == null) {
            drawPlate(c, ::d, w, h, "No cards in this deck. Open the CARDS tab to add or import some.")
            drawHud(c, ::d, w, h, st.box)
            return bmp
        }

        if (!st.flipped) {
            val scrim = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, w * 0.72f, 0f,
                    intArrayOf(0x9E040A46.toInt(), 0x59040A46, 0x00040A46),
                    floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP,
                )
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), scrim)
        }

        drawHeader(c, ::d, w, card, st)
        if (st.flipped) {
            drawAnswer(c, ::d, w, h, card)
            drawBackFooter(c, ::d, w, h, st, withButtons)
        } else {
            drawQuestion(c, ::d, w, h, card)
            drawFrontFooter(c, ::d, w, h, st)
            drawSpot(c, ::d, deck, st.box, sc, ox, oy)
        }
        drawHud(c, ::d, w, h, st.box)
        return bmp
    }

    // ------------------------------------------------------------ pieces

    private fun text(color: Int, sizeDp: Float, px: Float, face: Typeface, spacing: Float = 0f, shadow: Boolean = true) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizeDp * px
            typeface = face
            letterSpacing = spacing
            if (shadow) setShadowLayer(3f * px, 0f, 1f * px, SHADOW)
        }

    private fun pxOf(d: (Float) -> Float) = d(1f)

    private val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val MONO: Typeface = Typeface.MONOSPACE
    private val SANS_BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val SANS_MED: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun drawHeader(c: Canvas, d: (Float) -> Float, w: Int, card: Card, st: Snapshot) {
        val px = pxOf(d)
        val cy = d(PAD_T + HEADER_H / 2f)
        // crosshair icon
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE; style = Paint.Style.STROKE; strokeWidth = d(1.4f)
        }
        val cx = d(PAD_L + 9f)
        c.drawCircle(cx, cy, d(4.6f), line)
        c.drawLine(cx, cy - d(9f), cx, cy - d(4.6f), line); c.drawLine(cx, cy + d(4.6f), cx, cy + d(9f), line)
        c.drawLine(cx - d(9f), cy, cx - d(4.6f), cy, line); c.drawLine(cx + d(4.6f), cy, cx + d(9f), cy, line)

        var x = d(PAD_L + 27f)
        val deckP = text(YELLOW, 10.5f, px, MONO_BOLD, 0.12f)
        val label = (if (st.flipped) "ANSWER" else card.deck.uppercase())
        val base = cy + deckP.textSize * 0.35f
        c.drawText(label, x, base, deckP)
        x += deckP.measureText(label) + d(10f)
        if (!st.flipped) {
            val topicP = text(WHITE, 10.5f, px, MONO_BOLD, 0.12f)
            val maxW = w - d(PAD_R) - d(70f) - x
            var t = card.topic.uppercase()
            while (t.length > 3 && topicP.measureText(t) > maxW) t = t.dropLast(2)
            c.drawText(if (t.length < card.topic.length) "$t…" else t, x, base, topicP)
        }
        val codeP = text(WHITE, 10f, px, MONO, 0.12f).apply { alpha = 220; textAlign = Paint.Align.RIGHT }
        c.drawText(card.id.uppercase(), w - d(PAD_R), base, codeP)
    }

    private fun areaTop() = PAD_T + HEADER_H + 8f

    private fun fitLayout(
        text: String, paint: TextPaint, widthPx: Int, maxHeightPx: Float, sizesDp: List<Float>, px: Float,
    ): StaticLayout {
        var layout: StaticLayout? = null
        for (s in sizesDp) {
            paint.textSize = s * px
            layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.12f)
                .build()
            if (layout.height <= maxHeightPx) break
        }
        return layout!!
    }

    private fun drawQuestion(c: Canvas, d: (Float) -> Float, w: Int, h: Int, card: Card) {
        val px = pxOf(d)
        val contentW = w - d(PAD_L + PAD_R)
        val top = d(areaTop())
        val bottom = h - d(PAD_B + FOOTER_H + 8f)
        val paint = text(WHITE, 19f, px, SANS_BOLD).apply { setShadowLayer(8f * px, 0f, 2f * px, SHADOW) }
        val start = if (card.q.length <= 70) 19f else 16.5f
        val sizes = generateSequence(start) { if (it > 11f) it - 1f else null }.toList()
        val layout = fitLayout(card.q, paint, (contentW * 0.60f).toInt(), bottom - top, sizes, px)
        c.save()
        c.translate(d(PAD_L), top + (bottom - top - layout.height) / 2f)
        layout.draw(c)
        c.restore()
    }

    private fun drawPlate(c: Canvas, d: (Float) -> Float, w: Int, h: Int, body: String, size: List<Float>? = null) {
        val px = pxOf(d)
        val rect = RectF(d(PAD_L), d(areaTop()), w - d(PAD_R), h - d(PAD_B + FOOTER_H + 8f))
        c.drawRoundRect(rect, d(6f), d(6f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6050E46.toInt() })
        c.drawRoundRect(rect, d(6f), d(6f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xBFF4F7FF.toInt(); style = Paint.Style.STROKE; strokeWidth = d(1.3f)
        })
        val paint = text(WHITE, 15f, px, SANS_MED, shadow = false)
        val sizes = size ?: generateSequence(15f) { if (it > 10f) it - 0.5f else null }.toList()
        val inner = (rect.width() - d(26f)).toInt()
        val layout = fitLayout(body, paint, inner, rect.height() - d(20f), sizes, px)
        c.save()
        c.translate(rect.left + d(13f), rect.top + (rect.height() - layout.height) / 2f)
        layout.draw(c)
        c.restore()
    }

    private fun drawAnswer(c: Canvas, d: (Float) -> Float, w: Int, h: Int, card: Card) {
        val start = when {
            card.a.length <= 110 -> 15f
            card.a.length <= 170 -> 13.5f
            else -> 12.5f
        }
        drawPlate(c, d, w, h, card.a, generateSequence(start) { if (it > 10f) it - 0.5f else null }.toList())
    }

    private fun drawFrontFooter(c: Canvas, d: (Float) -> Float, w: Int, h: Int, st: Snapshot) {
        val px = pxOf(d)
        val base = h - d(PAD_B + FOOTER_H / 2f) + d(10.5f) * 0.35f
        c.drawText("HEAT ${6 - st.box}/5", d(PAD_L), base, text(WHITE, 10.5f, px, MONO, 0.12f))
        val r = text(WHITE, 10.5f, px, MONO, 0.12f).apply { textAlign = Paint.Align.RIGHT }
        c.drawText("TAP TO FLIP · %02d".format(st.today), w - d(PAD_R), base, r)
    }

    private fun drawBackFooter(c: Canvas, d: (Float) -> Float, w: Int, h: Int, st: Snapshot, withButtons: Boolean) {
        val px = pxOf(d)
        val cy = h - d(PAD_B + FOOTER_H / 2f)
        c.drawText("HEAT ${6 - st.box}/5", d(PAD_L), cy + d(10.5f) * 0.35f, text(WHITE, 10.5f, px, MONO, 0.12f))

        if (!withButtons) return
        val btnW = d(BTN_W)
        val left = w - d(PAD_R) - btnW * 2 - d(BTN_GAP)
        val top = h - d(PAD_B + FOOTER_H)
        val bottom = h - d(PAD_B)

        fun button(x: Float, label: String, fill: Int, stroke: Int, ink: Int) {
            val r = RectF(x, top, x + btnW, bottom)
            c.drawRoundRect(r, d(6f), d(6f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            c.drawRoundRect(r, d(6f), d(6f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke; style = Paint.Style.STROKE; strokeWidth = d(1.3f)
            })
            val p = text(ink, 10.5f, px, MONO_BOLD, 0.14f, shadow = false).apply { textAlign = Paint.Align.CENTER }
            c.drawText(label, r.centerX(), r.centerY() + p.textSize * 0.35f, p)
        }
        button(left, "AGAIN", 0xEB050E46.toInt(), ORANGE, ORANGE)
        button(left + btnW + d(BTN_GAP), "GOT IT", YELLOW, YELLOW, NAVY)
    }

    private fun drawSpot(c: Canvas, d: (Float) -> Float, deck: String, box: Int, sc: Float, ox: Float, oy: Float) {
        val px = pxOf(d)
        val (sx, sy) = SPOTS[deck] ?: SPOTS.getValue("ml")
        val x = sx * 2f * sc + ox     // design css px -> 660px artwork -> canvas
        val y = sy * 2f * sc + oy
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE; style = Paint.Style.STROKE; strokeWidth = d(1.3f)
            setShadowLayer(d(2f), 0f, 0f, 0x80000000.toInt())
        }
        c.drawRect(x - d(9f), y - d(9f), x + d(9f), y + d(9f), line)
        c.drawLine(x, y - d(14f), x, y + d(14f), line)
        c.drawLine(x - d(14f), y, x + d(14f), y, line)
        val label = "%.1f°".format(TEMPS[(box - 1).coerceIn(0, 4)])
        val p = text(WHITE, 9.5f, px, MONO_BOLD, 0.04f)
        if (sx > 190f) {
            p.textAlign = Paint.Align.RIGHT
            c.drawText(label, x - d(13f), y + d(9.5f) * 0.35f, p)
        } else {
            c.drawText(label, x + d(13f), y + d(9.5f) * 0.35f, p)
        }
    }

    /** Corner brackets and the heat scale (box 1 = hottest, box 5 = coolest). */
    private fun drawHud(c: Canvas, d: (Float) -> Float, w: Int, h: Int, box: Int) {
        val px = pxOf(d)
        val br = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xD9F4F7FF.toInt(); style = Paint.Style.STROKE; strokeWidth = d(1.4f)
        }
        val i = d(7f); val L = d(11f)
        c.drawLines(floatArrayOf(i, i, i + L, i, i, i, i, i + L), br)
        c.drawLines(floatArrayOf(w - i, i, w - i - L, i, w - i, i, w - i, i + L), br)
        c.drawLines(floatArrayOf(i, h - i, i + L, h - i, i, h - i, i, h - i - L), br)
        c.drawLines(floatArrayOf(w - i, h - i, w - i - L, h - i, w - i, h - i, w - i, h - i - L), br)

        val right = w - d(13f)
        val left = right - d(9f)
        val top = d(44f)
        val bottom = h - d(38f)
        val bar = RectF(left, top, right, bottom)
        c.drawRoundRect(bar, d(4.5f), d(4.5f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, bottom, 0f, top, BAR_COLORS, BAR_POS, Shader.TileMode.CLAMP)
        })
        c.drawRoundRect(bar, d(4.5f), d(4.5f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x8CF4F7FF.toInt(); style = Paint.Style.STROKE; strokeWidth = d(1f)
        })

        val heat = 6 - box.coerceIn(1, 5)
        val pos = (heat - 1) / 4f
        val my = bottom - pos * (bottom - top)
        val tri = Path().apply {
            moveTo(left - d(4f), my); lineTo(left - d(12f), my - d(5f)); lineTo(left - d(12f), my + d(5f)); close()
        }
        c.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE; setShadowLayer(d(3f), 0f, 0f, 0xB3000000.toInt())
        })

        val lab = text(WHITE, 7.5f, px, MONO, 0.04f).apply { textAlign = Paint.Align.RIGHT }
        c.drawText("41°", right + d(3f), top - d(4f), lab)
        c.drawText("21°", right + d(3f), bottom + d(10f), lab)
    }
}
