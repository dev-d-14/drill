package app.drill

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The flashcard screen: thermal card in the middle, tap to flip, then Again / Got it. */
@Composable
fun StudyScreen(store: Store, tick: Int, onChange: () -> Unit) {
    val ctx = LocalContext.current
    val snap = remember(tick) { store.snapshot() }
    val decks = remember(tick) { store.decks() }
    val card = snap.card

    Column(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("DRILL", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black,
                fontSize = 20.sp, color = Palette.Warn)
            Spacer(Modifier.weight(1f))
            Text("TODAY ${snap.today}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, color = Palette.Pale)
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            decks.forEach { d ->
                FilterChip(
                    selected = d == snap.deck,
                    onClick = { store.setDeck(d); onChange() },
                    label = { Text(d.uppercase(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Palette.Warn,
                        selectedLabelColor = Palette.Navy,
                    ),
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val wDp = maxWidth.value
            val hDp = minOf(maxHeight.value, wDp * 0.85f)
            val density = LocalDensity.current.density

            if (card == null) {
                Text("No cards in this deck. Add some in the CARDS tab.", color = Palette.Soft)
            } else {
                val key = listOf(card.id, snap.box, snap.today, wDp.toInt(), hDp.toInt(), density)
                val front = remember(key) { CardRenderer.render(ctx, snap.copy(flipped = false), wDp, hDp, false).asImageBitmap() }
                val back = remember(key) { CardRenderer.render(ctx, snap.copy(flipped = true), wDp, hDp, false).asImageBitmap() }

                val rot by animateFloatAsState(
                    targetValue = if (snap.flipped) 180f else 0f,
                    animationSpec = tween(320),
                    label = "flip",
                )
                val showBack = rot > 90f
                Image(
                    bitmap = if (showBack) back else front,
                    contentDescription = if (snap.flipped) card.a else card.q,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .size(wDp.dp, hDp.dp)
                        .graphicsLayer {
                            rotationY = if (showBack) rot - 180f else rot
                            cameraDistance = 14f * density
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { store.toggleFlip(); onChange() },
                )
            }
        }

        Text(
            if (card == null) "" else if (snap.flipped) "HOW DID YOU DO?" else "TAP THE CARD TO FLIP",
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Palette.Soft,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .alpha(if (snap.flipped) 1f else 0.35f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { store.grade(got = false); onChange() },
                enabled = snap.flipped && card != null,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.5.dp, Palette.Hot),
            ) { Text("AGAIN", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Palette.Hot) }
            Button(
                onClick = { store.grade(got = true); onChange() },
                enabled = snap.flipped && card != null,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Warn, contentColor = Palette.Navy,
                    disabledContainerColor = Palette.Warn, disabledContentColor = Palette.Navy,
                ),
            ) { Text("GOT IT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(4.dp))
    }
}
