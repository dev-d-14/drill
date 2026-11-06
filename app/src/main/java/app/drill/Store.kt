package app.drill

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class Card(
    val id: String,
    val deck: String,
    val topic: String,
    val q: String,
    val a: String,
)

data class Snapshot(
    val card: Card?,
    val flipped: Boolean,
    val deck: String,
    val box: Int,
    val today: Int,
)

/**
 * Cards + Leitner-box progress. Built-in cards come from assets/cards.json,
 * your own cards live in files/custom.json (added or imported from the app).
 * Progress is kept in SharedPreferences.
 */
class Store(context: Context) {
    private val app = context.applicationContext
    private val sp = app.getSharedPreferences("drill", Context.MODE_PRIVATE)
    private val customFile: File get() = File(app.filesDir, "custom.json")

    // ---------- cards ----------

    fun cards(): List<Card> {
        cache?.let { return it }
        val merged = LinkedHashMap<String, Card>()
        runCatching {
            app.assets.open("cards.json").bufferedReader().use { parse(it.readText()) }
        }.getOrNull()?.forEach { merged[it.id] = it }
        runCatching {
            if (customFile.exists()) parse(customFile.readText()) else emptyList()
        }.getOrNull()?.forEach { merged[it.id] = it }
        return merged.values.toList().also { cache = it }
    }

    fun decks(): List<String> = listOf(MIXED) + cards().map { it.deck }.distinct()

    fun addCustom(deck: String, q: String, a: String) {
        val card = Card(
            id = "c-" + System.currentTimeMillis(),
            deck = deck.trim().lowercase().ifBlank { "custom" },
            topic = "Mine",
            q = q.trim(),
            a = a.trim(),
        )
        writeCustom(readCustom() + card)
    }

    /** Merges cards from a JSON array into custom.json. Returns how many were read. */
    fun importCustom(json: String): Int {
        val incoming = parse(json)
        val merged = LinkedHashMap<String, Card>()
        readCustom().forEach { merged[it.id] = it }
        incoming.forEach { merged[it.id] = it }
        writeCustom(merged.values.toList())
        return incoming.size
    }

    private fun readCustom(): List<Card> =
        runCatching { if (customFile.exists()) parse(customFile.readText()) else emptyList() }
            .getOrDefault(emptyList())

    private fun writeCustom(list: List<Card>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id).put("deck", it.deck).put("topic", it.topic)
                    .put("q", it.q).put("a", it.a)
            )
        }
        customFile.writeText(arr.toString(1))
        cache = null
    }

    private fun parse(text: String): List<Card> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val q = o.getString("q")
            Card(
                id = o.optString("id").ifBlank { "c-" + q.hashCode() },
                deck = o.optString("deck", "custom").lowercase(),
                topic = o.optString("topic", ""),
                q = q,
                a = o.getString("a"),
            )
        }
    }

    // ---------- settings / state ----------

    fun deck(): String {
        val d = sp.getString("deck", MIXED) ?: MIXED
        return if (d in decks()) d else MIXED
    }

    fun setDeck(deck: String) {
        sp.edit().putString("deck", deck).apply()
        setCurrent(pickNext(deck, null))
    }

    fun cycleDeck() {
        val all = decks()
        val next = all[(all.indexOf(deck()) + 1) % all.size]
        setDeck(next)
    }

    fun snapshot(): Snapshot {
        val deck = deck()
        var card = current(deck)
        if (card == null) {
            card = pickNext(deck, null)
            setCurrent(card)
        }
        return Snapshot(
            card = card,
            flipped = sp.getBoolean("flip", false),
            deck = deck,
            box = card?.let { box(it.id) } ?: 1,
            today = todayCount(),
        )
    }

    fun toggleFlip() {
        sp.edit().putBoolean("flip", !sp.getBoolean("flip", false)).apply()
    }

    /** got = true moves the card up a box; false sends it back to box 1. */
    fun grade(got: Boolean) {
        val card = current(deck()) ?: return
        val nb = if (got) minOf(5, box(card.id) + 1) else 1
        sp.edit()
            .putInt("b_${card.id}", nb)
            .putLong("d_${card.id}", System.currentTimeMillis() + INTERVALS[nb - 1])
            .apply()
        bumpToday()
        setCurrent(pickNext(deck(), card.id))
    }

    fun resetProgress() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("b_") || it.startsWith("d_") }.forEach { e.remove(it) }
        e.putInt("today", 0).apply()
        setCurrent(pickNext(deck(), null))
    }

    /** Number of cards per Leitner box (index 0 = box 1) for the selected deck. */
    fun boxCounts(): IntArray {
        val counts = IntArray(5)
        pool(deck()).forEach { counts[box(it.id) - 1]++ }
        return counts
    }

    fun poolSize(): Int = pool(deck()).size

    // ---------- scheduling ----------

    private fun pool(deck: String): List<Card> =
        cards().filter { deck == MIXED || it.deck == deck }

    private fun box(id: String): Int = sp.getInt("b_$id", 1).coerceIn(1, 5)
    private fun due(id: String): Long = sp.getLong("d_$id", 0L)

    private fun current(deck: String): Card? {
        val id = sp.getString("cur", null) ?: return null
        return pool(deck).firstOrNull { it.id == id }
    }

    private fun setCurrent(card: Card?) {
        sp.edit().putString("cur", card?.id).putBoolean("flip", false).apply()
    }

    /** Due cards first (lowest box wins, random among ties); otherwise whichever comes due soonest. */
    private fun pickNext(deck: String, excludeId: String?): Card? {
        val all = pool(deck)
        if (all.isEmpty()) return null
        val candidates = all.filter { it.id != excludeId }.ifEmpty { all }
        val now = System.currentTimeMillis()
        val ready = candidates.filter { due(it.id) <= now }
        if (ready.isNotEmpty()) {
            val lowest = ready.minOf { box(it.id) }
            return ready.filter { box(it.id) == lowest }.random()
        }
        return candidates.minByOrNull { due(it.id) }
    }

    // ---------- daily counter ----------

    private fun todayCount(): Int {
        val day = LocalDate.now().toEpochDay()
        return if (sp.getLong("day", -1L) == day) sp.getInt("today", 0) else 0
    }

    private fun bumpToday() {
        val day = LocalDate.now().toEpochDay()
        val n = if (sp.getLong("day", -1L) == day) sp.getInt("today", 0) else 0
        sp.edit().putLong("day", day).putInt("today", n + 1).apply()
    }

    companion object {
        const val MIXED = "mixed"

        @Volatile
        private var cache: List<Card>? = null

        private const val MIN = 60_000L
        private const val DAY = 24 * 60 * MIN

        // box 1 returns in 10 minutes; then 1, 3, 7 and 14 days
        private val INTERVALS = longArrayOf(10 * MIN, DAY, 3 * DAY, 7 * DAY, 14 * DAY)
    }
}
