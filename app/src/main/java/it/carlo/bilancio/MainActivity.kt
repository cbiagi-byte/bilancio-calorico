package it.carlo.bilancio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import it.carlo.bilancio.widget.BilancioWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

private val BG = Color(0xFF0E1A2B)
private val PANEL = Color(0xFF16263C)
private val FG = Color(0xFFFFFFFF)
private val MUTED = Color(0xFF9FB0C3)
private val IN = Color(0xFF4FC3F7)
private val OUT = Color(0xFFF5A623)
private val OK = Color(0xFF66BB6A)
private val WARN = Color(0xFFEF6C00)
private val GRID = Color(0x33FFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = BG, surface = PANEL, primary = OUT)) {
                Screen()
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Screen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { Repository(context) }
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf(repo.cached()) }
    var updated by remember { mutableStateOf(repo.lastUpdate) }
    var error by remember { mutableStateOf(repo.lastError) }
    var loading by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(repo.credential.isBlank()) }
    var period by remember { mutableIntStateOf(14) }

    fun reload(full: Boolean) {
        if (repo.credential.isBlank()) { showSettings = true; return }
        loading = true
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                try { repo.refresh(forceFull = full); null } catch (e: Exception) { e.message ?: e.toString() }
            }
            days = repo.cached(); updated = repo.lastUpdate; error = err; loading = false
            BilancioWidget().updateAll(context)
        }
    }

    LaunchedEffect(Unit) {
        App.schedule(context)
        val stale = System.currentTimeMillis() - repo.lastUpdate > 15 * 60 * 1000
        if (repo.credential.isNotBlank() && stale) reload(false)
    }

    Column(
        modifier = Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bilancio calorico", color = FG, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Chiudi" else "Impostazioni", color = MUTED) }
        }

        if (showSettings) Settings(repo) { showSettings = false; reload(true) }

        val today = days.lastOrNull { it.date == repo.today() }
        TodayCards(today)

        Row(verticalAlignment = Alignment.CenterVertically) {
            val t = if (updated > 0) SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(Date(updated)) else "mai"
            Text(if (loading) "Aggiornamento in corso…" else "Aggiornato: $t", color = MUTED, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Button(
                onClick = { reload(false) }, enabled = !loading,
                colors = ButtonDefaults.buttonColors(containerColor = PANEL, contentColor = FG),
            ) { Text("Aggiorna") }
        }
        error?.let { Text(it, color = WARN, fontSize = 12.sp) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 14, 30, 90).forEach { p ->
                FilterChip(selected = period == p, onClick = { period = p }, label = { Text("$p giorni") })
            }
        }

        val cutoff = LocalDate.parse(repo.today()).minusDays(period.toLong() - 1).toString()
        val window = days.filter { it.date >= cutoff }
        Card("Assunte e bruciate") { BarsChart(window) }
        Summary(window)
        Card("Peso (kg)") { WeightChart(window) }
        Card("Dettaglio per giorno") { DayTable(window.reversed()) }
        Text(
            "Bruciate = riposo (Garmin) + attività ScanWatch + corsa (Fenix). Stime dei dispositivi: la verifica vera è la media settimanale del peso.",
            color = MUTED, fontSize = 11.sp,
        )
    }
}

@Composable
private fun Settings(repo: Repository, onSaved: () -> Unit) {
    var value by remember { mutableStateOf(repo.credential) }
    Card("Collegamento athletedata") {
        Text(
            "Incolla l'URL MCP copiato da athletedata.health/dashboard/mcp (https://mcp.athletedata.health/mcp?apiKey=…) oppure solo la chiave sk_soma_…",
            color = MUTED, fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value, onValueChange = { value = it }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            label = { Text("URL o chiave") }, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { repo.credential = value; onSaved() }, enabled = value.isNotBlank()) { Text("Salva e aggiorna") }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(PANEL, RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        Text(title, color = MUTED, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun TodayCards(d: DayBalance?) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Big("Assunte", fmt(d?.intake), "obiettivo ${fmt(d?.target)}", IN, Modifier.weight(1f))
        Big("Bruciate", fmt(d?.burned), if (d?.partial == true) "finora" else "", OUT, Modifier.weight(1f))
        val diff = d?.diff
        Big("Differenza", fmtSigned(diff), if (diff != null && diff <= 0) "deficit" else "", if (diff == null) FG else if (diff <= 0) OK else WARN, Modifier.weight(1f))
    }
    if (d != null) {
        Text(
            "Riposo ${fmt(d.resting)} · ScanWatch ${if (d.scanwatch == null) "n.d." else fmt(d.scanwatch)} · Corsa ${fmt(d.run)} · Proteine ${fmt(d.protein)} g",
            color = MUTED, fontSize = 12.sp,
        )
    }
}

@Composable
private fun Big(label: String, value: String, sub: String, color: Color, modifier: Modifier) {
    Column(modifier = modifier.background(PANEL, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(label, color = MUTED, fontSize = 12.sp)
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = MUTED, fontSize = 11.sp)
    }
}

@Composable
private fun Summary(window: List<DayBalance>) {
    val complete = window.filter { it.diff != null && !it.partial }
    val avg = if (complete.isEmpty()) null else complete.map { it.diff!! }.average().toInt()
    val weeklyKg = avg?.let { it * 7 / 7700.0 }
    Card("Media dei giorni completi") {
        Text(
            if (avg == null) "Servono giorni con pasti registrati e consumo completo."
            else "Differenza media ${fmtSigned(avg)} kcal/giorno su ${complete.size} giorni" +
                (weeklyKg?.let { " · circa ${String.format(Locale.ITALY, "%+.2f", it)} kg a settimana" } ?: ""),
            color = FG, fontSize = 14.sp,
        )
    }
}

@Composable
private fun BarsChart(window: List<DayBalance>) {
    if (window.isEmpty()) { Text("Nessun dato", color = MUTED); return }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Legend("Assunte", IN); Legend("Bruciate", OUT)
    }
    Spacer(Modifier.height(6.dp))
    val maxV = (window.maxOf { maxOf(it.intake ?: 0, it.burned ?: 0) }).coerceAtLeast(1000)
    Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
        val n = window.size
        val slot = size.width / n
        val bw = (slot * 0.38f).coerceAtLeast(1.5f)
        for (k in 1..3) {
            val y = size.height * (1 - k / 3f)
            drawLine(GRID, Offset(0f, y), Offset(size.width, y), 1f)
        }
        window.forEachIndexed { i, d ->
            val x = i * slot + slot * 0.1f
            d.intake?.let {
                val h = size.height * it / maxV
                drawRect(IN, Offset(x, size.height - h), Size(bw, h))
            }
            d.burned?.let {
                val h = size.height * it / maxV
                drawRect(if (d.partial) OUT.copy(alpha = 0.5f) else OUT, Offset(x + bw + 1f, size.height - h), Size(bw, h))
            }
        }
    }
    val f = DateTimeFormatter.ofPattern("dd/MM")
    Row {
        Text(LocalDate.parse(window.first().date).format(f), color = MUTED, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text("max ${fmt(maxV)} kcal", color = MUTED, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text(LocalDate.parse(window.last().date).format(f), color = MUTED, fontSize = 10.sp)
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(4.dp))
        Text(label, color = MUTED, fontSize = 11.sp)
    }
}

@Composable
private fun WeightChart(window: List<DayBalance>) {
    val pts = window.filter { it.weight != null }
    if (pts.size < 2) { Text("Servono almeno due pesate nel periodo", color = MUTED, fontSize = 12.sp); return }
    val minW = pts.minOf { it.weight!! } - 0.5
    val maxW = pts.maxOf { it.weight!! } + 0.5
    val first = LocalDate.parse(window.first().date)
    val span = (LocalDate.parse(window.last().date).toEpochDay() - first.toEpochDay()).coerceAtLeast(1)
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        val path = Path()
        pts.forEachIndexed { i, d ->
            val x = size.width * (LocalDate.parse(d.date).toEpochDay() - first.toEpochDay()) / span
            val y = size.height * (1 - ((d.weight!! - minW) / (maxW - minW))).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(OK, 4f, Offset(x, y))
        }
        drawPath(path, OK, style = Stroke(width = 3f))
    }
    Row {
        Text("${String.format(Locale.ITALY, "%.1f", pts.first().weight)} kg", color = MUTED, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text("ultimo ${String.format(Locale.ITALY, "%.1f", pts.last().weight)} kg", color = FG, fontSize = 11.sp)
    }
}

@Composable
private fun DayTable(rows: List<DayBalance>) {
    val f = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.ITALY)
    Row {
        Cell("Giorno", MUTED, 1.4f); Cell("Assunte", MUTED, 1f); Cell("Bruciate", MUTED, 1f); Cell("Diff.", MUTED, 1f)
    }
    rows.forEach { d ->
        Row(modifier = Modifier.padding(vertical = 3.dp)) {
            Cell(LocalDate.parse(d.date).format(f) + if (d.partial) " *" else "", FG, 1.4f)
            Cell(fmt(d.intake), IN, 1f)
            Cell(fmt(d.burned), OUT, 1f)
            val diff = d.diff
            Cell(fmtSigned(diff), if (diff == null) MUTED else if (diff <= 0) OK else WARN, 1f)
        }
    }
    Text("* giornata in corso", color = MUTED, fontSize = 10.sp)
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(text: String, color: Color, weight: Float) {
    Text(text, color = color, fontSize = 12.sp, modifier = Modifier.weight(weight))
}
