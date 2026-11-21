package it.carlo.bilancio.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import it.carlo.bilancio.DayBalance
import it.carlo.bilancio.MainActivity
import it.carlo.bilancio.Repository
import it.carlo.bilancio.fmt
import it.carlo.bilancio.fmtSigned
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BilancioWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BilancioWidget()
}

private val BG = Color(0xFF0E1A2B)
private val FG = Color(0xFFFFFFFF)
private val MUTED = Color(0xFF9FB0C3)
private val IN = Color(0xFF4FC3F7)
private val OUT = Color(0xFFF5A623)
private val OK = Color(0xFF66BB6A)
private val WARN = Color(0xFFEF6C00)

class BilancioWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = Repository(context)
        val today = repo.todayBalance()
        val updated = repo.lastUpdate
        val hasKey = repo.credential.isNotBlank()
        provideContent { Content(today, updated, hasKey) }
    }

    @Composable
    private fun Content(d: DayBalance?, updated: Long, hasKey: Boolean) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(BG).cornerRadius(16.dp).padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text("Bilancio di oggi", style = TextStyle(color = ColorProvider(MUTED), fontSize = 12.sp))
            Spacer(GlanceModifier.height(6.dp))
            if (!hasKey) {
                Text("Apri l'app e inserisci la chiave athletedata", style = TextStyle(color = ColorProvider(FG), fontSize = 13.sp))
            } else {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Metric("Assunte", fmt(d?.intake), IN, GlanceModifier.defaultWeight())
                    Metric("Bruciate", fmt(d?.burned), OUT, GlanceModifier.defaultWeight())
                    val diff = d?.diff
                    val diffColor = if (diff == null) FG else if (diff <= 0) OK else WARN
                    Metric("Differenza", fmtSigned(diff), diffColor, GlanceModifier.defaultWeight())
                }
                Spacer(GlanceModifier.height(6.dp))
                val rem = d?.remaining
                val targetText = if (rem != null) "Obiettivo ${fmt(d?.target)} · ne restano ${fmt(rem)}" else "Obiettivo ${fmt(d?.target)}"
                Text(targetText, style = TextStyle(color = ColorProvider(MUTED), fontSize = 11.sp))
                val time = if (updated > 0) SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(updated)) else "—"
                Text("Aggiornato alle $time", style = TextStyle(color = ColorProvider(MUTED), fontSize = 10.sp))
            }
        }
    }

    @Composable
    private fun Metric(label: String, value: String, color: Color, modifier: GlanceModifier) {
        Column(modifier = modifier) {
            Text(value, style = TextStyle(color = ColorProvider(color), fontSize = 20.sp, fontWeight = FontWeight.Bold))
            Text(label, style = TextStyle(color = ColorProvider(MUTED), fontSize = 11.sp))
        }
    }
}
