package com.hydra.panel.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

object Format {

    private val sqlFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Парсит даты в форматах панели: 'YYYY-MM-DD HH:MM:SS', ISO, 'now'. */
    fun parseDateTime(raw: String?): Date? {
        if (raw.isNullOrBlank() || raw == "never") return null
        val s = raw.replace('T', ' ').substringBefore('.').let {
            if (it.length == 10) "$it 00:00:00" else it
        }
        return runCatching { sqlFmt.parse(s) }.getOrNull()
            ?: runCatching { isoFmt.parse(raw) }.getOrNull()
    }

    fun dateTime(raw: String?): String =
        parseDateTime(raw)?.let { sqlFmt.format(it) } ?: raw ?: "—"

    fun shortDate(raw: String?): String =
        parseDateTime(raw)?.let { dayFmt.format(it) } ?: raw ?: "—"

    /** ISO-8601 / "YYYY-MM-DD HH:MM:SS" epoch string -> millis (null if unparsable) */
    fun parseIso(raw: String?): Long? {
        val t = raw?.trim()?.removeSuffix("Z") ?: return null
        Regex("^(\\d{4})-(\\d{2})-(\\d{2])[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?$").find(t)?.let { m ->
            val g = m.groupValues
            val dt = java.time.LocalDateTime.of(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), if (g.size > 6 && g[6].isNotEmpty()) g[6].toInt() else 0)
            return dt.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        return t.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it }
    }

    fun relative(raw: String?): String {
        val d = parseDateTime(raw) ?: return raw ?: "—"
        val diff = System.currentTimeMillis() - d.time
        val min = diff / 60_000
        return when {
            diff < 0 -> "будет ${relative((-diff).toString())}" // редко: будущая дата
            min < 1 -> "только что"
            min < 60 -> "${min} мин назад"
            min < 60 * 24 -> "${min / 60} ч назад"
            min < 60 * 24 * 30 -> "${min / (60 * 24)} дн назад"
            else -> shortDate(raw)
        }
    }

    fun daysLeft(raw: String?): Long? {
        val d = parseDateTime(raw) ?: return null
        return (d.time - System.currentTimeMillis()) / 86_400_000L
    }

    fun mbps(v: Double?): String = when {
        v == null -> "—"
        v >= 1000 -> "%.1f Гбит/с".format(v / 1000)
        else -> "%.0f Мбит/с".format(v)
    }

    fun percent(v: Double?): String = v?.let { "%.0f%%".format(it) } ?: "—"

    /** Число с одним знаком после запятой, без хвостового .0 */
    fun num(v: Double?): String {
        if (v == null) return "—"
        return if (kotlin.math.abs(v - kotlin.math.round(v)) < 0.05) v.roundToIntStr() else "%.1f".format(v)
    }

    private fun Double.roundToIntStr(): String = toInt().toString()

    fun bytes(kb: Double?): String = when {
        kb == null -> "—"
        kb > 1024 -> "%.1f МБ".format(kb / 1024)
        else -> "%.0f КБ".format(kb)
    }

    /** Склонение: 1 сервер, 2 сервера, 5 серверов. */
    fun plural(n: Int, one: String, few: String, many: String = few + "ов"): String {
        val m10 = n % 10; val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> "$n $one"
            m10 in 2..4 && m100 !in 12..14 -> "$n $few"
            else -> "$n $many"
        }
    }
}
