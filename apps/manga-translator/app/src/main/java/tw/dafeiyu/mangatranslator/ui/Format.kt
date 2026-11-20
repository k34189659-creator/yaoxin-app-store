package tw.dafeiyu.mangatranslator.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtBytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.2f GB".format(Locale.US, n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.1f MB".format(Locale.US, n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%.0f KB".format(Locale.US, n / (1L shl 10).toDouble())
    else -> "$n B"
}

fun fmtDuration(ms: Long): String = when {
    ms >= 60_000 -> "%d 分 %02d 秒".format(Locale.US, ms / 60_000, (ms % 60_000) / 1000)
    ms >= 1000 -> "%.1f 秒".format(Locale.US, ms / 1000.0)
    else -> "$ms ms"
}

fun fmtTime(ms: Long): String =
    SimpleDateFormat("MM/dd HH:mm", Locale.TRADITIONAL_CHINESE).format(Date(ms))

fun fmtCost(usd: Double): String = when {
    usd <= 0.0 -> "—"
    usd < 0.01 -> "$%.6f".format(Locale.US, usd)
    else -> "$%.4f".format(Locale.US, usd)
}
