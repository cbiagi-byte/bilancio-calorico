package it.carlo.bilancio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** One day of the energy balance. Null = no data for that part. */
data class DayBalance(
    val date: String,
    val intake: Int?,
    val protein: Int?,
    val target: Int?,
    val resting: Int?,
    val scanwatch: Int?,
    val run: Int?,
    val partial: Boolean,
    val weight: Double?,
) {
    /**
     * From 2026-11-21 Carlo wears the Garmin CIRQA band outside runs, so Garmin's daily active kcal
     * (here `run`) cover the whole day. Before that, Withings active kcal (which include the runs it
     * imports from the Fenix) were the day's activity, with the Fenix as fallback.
     */
    val burned: Int? get() = resting?.let {
        if (date >= GARMIN_ONLY_FROM) it + (run ?: 0) else it + (scanwatch ?: run ?: 0)
    }
    val diff: Int? get() = if (intake != null && burned != null) intake - burned!! else null
    val remaining: Int? get() = if (intake != null && target != null) target - intake else null

    fun toJson(): JSONObject = JSONObject()
        .put("date", date).put("intake", intake ?: JSONObject.NULL).put("protein", protein ?: JSONObject.NULL)
        .put("target", target ?: JSONObject.NULL).put("resting", resting ?: JSONObject.NULL)
        .put("scanwatch", scanwatch ?: JSONObject.NULL).put("run", run ?: JSONObject.NULL)
        .put("partial", partial).put("weight", weight ?: JSONObject.NULL)

    companion object {
        const val GARMIN_ONLY_FROM = "2026-11-21"
        private fun JSONObject.intOrNull(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
        private fun JSONObject.dblOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k)
        fun fromJson(o: JSONObject) = DayBalance(
            o.getString("date"), o.intOrNull("intake"), o.intOrNull("protein"), o.intOrNull("target"),
            o.intOrNull("resting"), o.intOrNull("scanwatch"), o.intOrNull("run"),
            o.optBoolean("partial"), o.dblOrNull("weight"),
        )
    }
}

class Repository(private val context: Context) {

    private val prefs = context.getSharedPreferences("bilancio", Context.MODE_PRIVATE)
    private val zone = ZoneId.of("Europe/San_Marino")

    var credential: String
        get() = prefs.getString("credential", "") ?: ""
        set(v) = prefs.edit().putString("credential", v.trim()).apply()

    val lastUpdate: Long get() = prefs.getLong("last_update", 0L)
    val lastError: String? get() = prefs.getString("last_error", null)

    fun today(): String = LocalDate.now(zone).toString()

    fun cached(): List<DayBalance> {
        val raw = prefs.getString("days", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { DayBalance.fromJson(arr.getJSONObject(it)) }.sortedBy { it.date }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun todayBalance(): DayBalance? = cached().lastOrNull { it.date == today() }

    /**
     * Refresh from athletedata. A short refresh reads the last 14 days; a full one 90 days.
     * The full history is refreshed at most once a day unless forced.
     */
    fun refresh(forceFull: Boolean = false) {
        val cred = credential
        if (cred.isBlank()) throw McpClient.McpException("Inserisci la chiave athletedata nelle impostazioni")
        val todayDate = LocalDate.now(zone)
        val lastFull = prefs.getString("last_full", "") ?: ""
        val full = forceFull || lastFull != todayDate.toString() || cached().isEmpty()
        val days = if (full) 90L else 14L
        val start = todayDate.minusDays(days - 1)
        val client = McpClient(cred)
        try {
            val metrics = fetchMetrics(client, start, todayDate)
            val intake = fetchIntake(client, start, todayDate)
            val scanwatch = fetchScanwatch(client, start, todayDate)
            val targets = fetchTargets(client)

            val dates = (metrics.keys + intake.keys).filter { it >= start.toString() && it <= todayDate.toString() }.toSortedSet()
            val fresh = dates.map { d ->
                val m = metrics[d]
                val i = intake[d]
                val dow = LocalDate.parse(d).dayOfWeek.value // 1 = Monday
                DayBalance(
                    date = d,
                    intake = i?.first,
                    protein = i?.second,
                    target = targets[dow],
                    resting = m?.resting,
                    scanwatch = scanwatch[d],
                    run = m?.run,
                    partial = m?.partial ?: (d == todayDate.toString()),
                    weight = m?.weight,
                )
            }
            // Merge with cache: fresh days replace cached ones, older cached days are kept.
            val merged = cached().associateBy { it.date }.toMutableMap()
            fresh.forEach { merged[it.date] = it }
            val keepFrom = todayDate.minusDays(120).toString()
            val list = merged.values.filter { it.date >= keepFrom }.sortedBy { it.date }
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            prefs.edit()
                .putString("days", arr.toString())
                .putLong("last_update", System.currentTimeMillis())
                .remove("last_error")
                .apply()
            if (full) prefs.edit().putString("last_full", todayDate.toString()).apply()
        } catch (e: Exception) {
            prefs.edit().putString("last_error", e.message ?: e.toString()).apply()
            throw e
        }
    }

    private data class Metric(val resting: Int?, val run: Int?, val partial: Boolean, val weight: Double?)

    private fun JSONObject.numOrNull(k: String): Double? =
        if (!has(k) || isNull(k)) null else optString(k).toDoubleOrNull()

    private fun fetchMetrics(c: McpClient, start: LocalDate, end: LocalDate): Map<String, Metric> {
        val out = HashMap<String, Metric>()
        var cursor: String? = null
        var pages = 0
        do {
            val args = JSONObject().put("start", start.toString()).put("end", end.toString())
            cursor?.let { args.put("cursor", it) }
            val res = c.callTool("get_daily_metrics", args)
            val rows = res.optJSONArray("rows") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val date = r.optString("date").take(10)
                if (date.length != 10) continue
                out[date] = Metric(
                    resting = r.numOrNull("bmrKcal")?.roundToInt(),
                    run = r.numOrNull("activeKcal")?.roundToInt(),
                    partial = r.optBoolean("burnPartial", false),
                    weight = r.numOrNull("weight")?.takeIf { it >= 70.0 },
                )
            }
            cursor = res.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
            pages++
        } while (cursor != null && pages < 8)
        return out
    }

    /** date -> (kcal, protein g) from Yazio daily totals, read in 28-day windows. */
    private fun fetchIntake(c: McpClient, start: LocalDate, end: LocalDate): Map<String, Pair<Int, Int>> {
        val out = HashMap<String, Pair<Int, Int>>()
        var winEnd = end
        while (!winEnd.isBefore(start)) {
            val winStart = maxOf(start, winEnd.minusDays(27))
            val args = JSONObject()
                .put("start_date", winStart.toString())
                .put("end_date", winEnd.toString())
                .put("timezone", "Europe/San_Marino")
                .put("limit", 1)
            val res = c.callTool("food_log_get", args)
            val totals = res.optJSONArray("daily_totals") ?: JSONArray()
            for (i in 0 until totals.length()) {
                val t = totals.getJSONObject(i)
                val d = t.optString("date").take(10)
                if (d < winStart.toString() || d > winEnd.toString()) continue
                out[d] = Pair(
                    t.numOrNull("calories")?.roundToInt() ?: 0,
                    t.numOrNull("protein")?.roundToInt() ?: 0,
                )
            }
            winEnd = winStart.minusDays(1)
        }
        return out
    }

    private fun fetchScanwatch(c: McpClient, start: LocalDate, end: LocalDate): Map<String, Int> {
        val out = HashMap<String, Int>()
        val res = try {
            c.callTool(
                "withings_get_activity",
                JSONObject().put("startdateymd", start.toString()).put("enddateymd", end.toString())
            )
        } catch (_: Exception) {
            return out
        }
        val arr = res.optJSONArray("activities") ?: res.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val d = (a.optString("date").ifBlank { a.optString("day") }).take(10)
            val kcal = a.numOrNull("active_calories") ?: a.numOrNull("activeCalories")
                ?: a.numOrNull("calories") ?: a.numOrNull("activeKcal")
            val total = a.numOrNull("total_calories") ?: 0.0
            // Skip obviously broken days (active > 3000 and not below the day's total).
            val broken = kcal != null && kcal > 3000 && total > 0 && kcal >= total
            if (d.length == 10 && kcal != null && !broken) out[d] = kcal.roundToInt()
        }
        return out
    }

    /** ISO day of week (1 = Monday) -> kcal target. */
    private fun fetchTargets(c: McpClient): Map<Int, Int> {
        val res = try {
            c.callTool("nutrition_targets_get", JSONObject())
        } catch (_: Exception) {
            return emptyMap()
        }
        val t = res.optJSONObject("targets") ?: return emptyMap()
        val base = t.numOrNull("kcal")?.roundToInt()
        val days = t.optJSONObject("days")
        val keys = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val out = HashMap<Int, Int>()
        keys.forEachIndexed { idx, k ->
            val v = days?.optJSONObject(k)?.numOrNull("kcal")?.roundToInt() ?: base
            if (v != null) out[idx + 1] = v
        }
        return out
    }
}
