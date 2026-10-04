package com.hydra.panel.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.hydra.panel.MainActivity
import com.hydra.panel.R

/** Локальные пуш-уведомления о критических событиях панели. */
object AlertNotifier {

    const val CH_ALERTS = "hydra_alerts"
    private const val CH_SERVICE = "hydra_service"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Алерты серверов", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Критические уведомления: CPU/RAM/диск, недоступность серверов, новые алерты панели"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Фоновый мониторинг", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Индикатор фонового опроса панели"
            }
        )
    }

    private fun notifId(seed: String): Int = (seed.hashCode() and 0x7fffffff) % 1_000_000 + 10

    fun postMessage(ctx: Context, idSeed: String, title: String, text: String, big: Boolean = false) {
        ensureChannels(ctx)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, notifId(idSeed), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_hydra)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setColor(0xFFE5484D.toInt())
        if (big) b.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(notifId(idSeed), b.build())
        } catch (_: Exception) { /* нет разрешения POST_NOTIFICATIONS — пропускаем */ }
    }

    fun postService(ctx: Context, text: String) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_hydra)
            .setContentTitle("Hydra Monitor")
            .setContentText(text)
            .setOngoing(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(2, n)
        } catch (_: Exception) {}
    }
}
