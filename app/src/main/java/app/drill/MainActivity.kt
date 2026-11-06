package app.drill

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Palette.Warn,
                    onPrimary = Palette.Navy,
                    secondary = Palette.Soft,
                    background = Palette.Bg,
                    onBackground = Palette.Ink,
                    surface = Palette.Deep,
                    onSurface = Palette.Ink,
                    surfaceVariant = Palette.Deeper,
                    onSurfaceVariant = Palette.Soft,
                    outline = Palette.Soft,
                ),
            ) {
                Screen()
            }
        }
    }
}

private val Mono = FontFamily.Monospace

private fun refresh(scope: CoroutineScope, ctx: Context) {
    scope.launch { StudyWidget().updateAll(ctx) }
}

@Composable
private fun Label(text: String) {
    Text(text = text, fontFamily = Mono, fontSize = 11.sp, color = Palette.Soft, fontWeight = FontWeight.Bold)
}

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { Store(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }

    var newDeck by remember { mutableStateOf("ml") }
    var newQ by remember { mutableStateOf("") }
    var newA by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            message = if (text == null) {
                "Could not read that file."
            } else {
                runCatching { "Imported ${store.importCustom(text)} cards." }
                    .getOrElse { "That is not a valid cards file." }
            }
            tick++
            refresh(scope, ctx)
        }
    }

    // reading `tick` makes these re-run after every change
    val decks = remember(tick) { store.decks() }
    val deck = remember(tick) { store.deck() }
    val counts = remember(tick) { store.boxCounts() }
    val total = remember(tick) { store.poolSize() }

    Column(
        modifier = Modifier
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Drill", fontSize = 44.sp, fontWeight = FontWeight.Black, color = Palette.Ink)
            Text("ML and system design, one card at a time.", color = Palette.Pale, fontSize = 16.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("DECK")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                decks.forEach { d ->
                    FilterChip(
                        selected = d == deck,
                        onClick = {
                            store.setDeck(d)
                            tick++
                            refresh(scope, ctx)
                        },
                        label = { Text(d.uppercase(), fontFamily = Mono, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Palette.Warn,
                            selectedLabelColor = Palette.Navy,
                        ),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("PROGRESS  ·  $total CARDS")
            counts.forEachIndexed { i, n ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("BOX ${i + 1}", fontFamily = Mono, fontSize = 13.sp, color = Palette.Ink)
                    Spacer(Modifier.padding(start = 12.dp))
                    Text(
                        text = "●".repeat(minOf(n, 28)),
                        fontFamily = Mono,
                        fontSize = 13.sp,
                        color = if (i == 0) Palette.Warn else Palette.Ink,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text("$n", fontFamily = Mono, fontSize = 13.sp, color = Palette.Soft)
                }
            }
            Text(
                "Box 1 comes back in 10 min, then 1, 3, 7 and 14 days. AGAIN sends a card back to box 1.",
                color = Palette.Soft,
                fontSize = 13.sp,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("ADD A CARD")
            OutlinedTextField(
                value = newDeck,
                onValueChange = { newDeck = it },
                label = { Text("Deck (ml, design, or your own)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newQ,
                onValueChange = { newQ = it },
                label = { Text("Question") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newA,
                onValueChange = { newA = it },
                label = { Text("Answer") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    if (newQ.isNotBlank() && newA.isNotBlank()) {
                        store.addCustom(newDeck, newQ, newA)
                        newQ = ""
                        newA = ""
                        message = "Card saved."
                        tick++
                        refresh(scope, ctx)
                    } else {
                        message = "Write both a question and an answer."
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Warn,
                    contentColor = Palette.Navy,
                ),
            ) { Text("Save card", fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
            ) { Text("Import cards from a JSON file", color = Palette.Ink) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("WIDGET")
            Button(
                onClick = {
                    val awm = AppWidgetManager.getInstance(ctx)
                    if (awm.isRequestPinAppWidgetSupported) {
                        awm.requestPinAppWidget(ComponentName(ctx, StudyWidgetReceiver::class.java), null, null)
                    } else {
                        message = "Long-press your home screen, tap Widgets, and pick Drill."
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Ink,
                    contentColor = Palette.Bg,
                ),
            ) { Text("Add to home screen", fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = {
                    store.resetProgress()
                    message = "Progress reset."
                    tick++
                    refresh(scope, ctx)
                },
            ) { Text("Reset progress", color = Palette.Ink) }
        }

        if (message.isNotEmpty()) {
            Text(message, fontFamily = Mono, color = Palette.Pale, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}
