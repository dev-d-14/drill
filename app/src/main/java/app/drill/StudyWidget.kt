package app.drill

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width

/** Thermal-camera palette. */
object Palette {
    val Bg = Color(0xFF06124A)
    val Ink = Color(0xFFF4F7FF)
    val Warn = Color(0xFFFAE226)
    val Hot = Color(0xFFFA8E24)
    val Deep = Color(0xFF0A1A5E)
    val Deeper = Color(0xFF040C3A)
    val Soft = Color(0xFF9DB4F0)
    val Mute = Color(0xFF5E78C4)
    val Pale = Color(0xFFFFF3A0)
    val Navy = Color(0xFF06124A)
}

class StudyWidget : GlanceAppWidget() {
    // the card is drawn as one bitmap at the widget's actual size
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = Store(context).snapshot()
        provideContent { Root(context, snap) }
    }
}

class StudyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StudyWidget()
}

// ---------- actions ----------

class FlipAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Store(context).toggleFlip()
        StudyWidget().updateAll(context)
    }
}

class GradeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Store(context).grade(got = parameters[GRADE_KEY] == "got")
        StudyWidget().updateAll(context)
    }

    companion object {
        val GRADE_KEY = ActionParameters.Key<String>("grade")
    }
}

class DeckAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Store(context).cycleDeck()
        StudyWidget().updateAll(context)
    }
}

// ---------- UI ----------

@Composable
private fun Root(context: Context, snap: Snapshot) {
    val size = LocalSize.current
    val w = size.width.value
    val h = size.height.value
    val bmp = CardRenderer.render(context, snap, w, h)

    Box(modifier = GlanceModifier.fillMaxSize()) {
        Image(
            provider = ImageProvider(bmp),
            contentDescription = snap.card?.let { if (snap.flipped) it.a else it.q },
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxSize().clickable(actionRunCallback<FlipAction>()),
        )
        if (snap.card != null) {
            if (snap.flipped) {
                GradeRow(w, h)
            } else {
                // deck switcher sits over the deck label in the header
                Box(
                    modifier = GlanceModifier
                        .padding(
                            start = CardRenderer.PAD_L.dp,
                            top = CardRenderer.PAD_T.dp,
                        )
                        .width(86.dp)
                        .height(CardRenderer.HEADER_H.dp)
                        .clickable(actionRunCallback<DeckAction>()),
                ) {}
            }
        }
    }
}

@Composable
private fun GradeRow(w: Float, h: Float) {
    val contentW = w - CardRenderer.PAD_L - CardRenderer.PAD_R
    val left = CardRenderer.PAD_L + contentW * CardRenderer.BTN_START
    val btnW = (contentW * (1f - CardRenderer.BTN_START) - CardRenderer.BTN_GAP) / 2f
    val top = h - CardRenderer.PAD_B - CardRenderer.FOOTER_H
    Row(modifier = GlanceModifier.padding(start = left.dp, top = top.dp)) {
        GradeHit(btnW.dp, "again")
        Spacer(GlanceModifier.width(CardRenderer.BTN_GAP.dp))
        GradeHit(btnW.dp, "got")
    }
}

@Composable
private fun GradeHit(width: Dp, grade: String) {
    Box(
        modifier = GlanceModifier
            .width(width)
            .height(CardRenderer.FOOTER_H.dp)
            .clickable(actionRunCallback<GradeAction>(actionParametersOf(GradeAction.GRADE_KEY to grade))),
    ) {}
}
