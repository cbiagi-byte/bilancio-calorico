package it.carlo.bilancio

import java.text.NumberFormat
import java.util.Locale

private val nf: NumberFormat = NumberFormat.getIntegerInstance(Locale.ITALY)

fun fmt(v: Int?): String = if (v == null) "—" else nf.format(v)

fun fmtSigned(v: Int?): String = when {
    v == null -> "—"
    v > 0 -> "+" + nf.format(v)
    else -> nf.format(v)
}
