package com.hydra.panel.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hydra.panel.data.repo.PanelRepository
import com.hydra.panel.util.SessionStore
import java.util.concurrent.TimeUnit

/**
 * Фоновый мониторинг панели: раз в pollInterval минут проверяет
 *  1) активные алерты панели (alerts) — шлёт пуш по новым id;
 *  2) последние метрики серверов — локальные пороги CPU/RAM/latency.
 */
class AlertPollWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = SessionStore(ctx)
        if (!store.pushEnabled || !store.isLoggedIn) return Result.success()

        val repo = try { PanelRepository.get(ctx) } catch (_: Exception) { return Result.retry() }

        // 1) Алерты самой панели (cpu_high, disk_high, server_offline …)
        try {
            val active = repo.activeAlerts()
            val seen = store.seenAlertIds.toMutableSet()
            val firstRun = seen.isEmpty() && active.isNotEmpty()
            for (a in active) {
                val key = "${a.serverId ?: "panel"}|${a.triggeredAt ?: ""}|${(a.message ?: "").take(80)}"
                if (key in seen) continue
                seen += key
                if (!firstRun) {
                    AlertNotifier.postMessage(
                        ctx, key,
                        title = "⚠️ ${levelLabel(a.level)} · ${a.serverId ?: "панель"}",
                        text = a.message ?: "Алерт без описания",
                        big = true,
                    )
                }
            }
            // обрезаем хвост, чтобы prefs не разрастались
            store.seenAlertIds = if (seen.size > 400) seen.toList().takeLast(300).toSet() else seen
        } catch (_: Exception) { /* сессия могла протухнуть — следующая попытка по расписанию */ }

        // 2) Локальные пороги по последним метрикам
        try {
            val servers = repo.listServers()
            for (s in servers.take(20)) {
                val last = runCatching { repo.serverMetrics(s.id, limit = 1).firstOrNull() }.getOrNull() ?: continue
                notifyThreshold(store, s.id, "cpu", last.cpuPercent, store.alertCpuPct.toDouble(),
                    "Загрузка CPU %.0f%%".format(last.cpuPercent ?: 0.0))
                notifyThreshold(store, s.id, "ram", last.memoryPercent, store.alertRamPct.toDouble(),
                    "Потребление RAM %.0f%%".format(last.memoryPercent ?: 0.0))
                notifyThreshold(store, s.id, "lat", last.latencyMs, store.alertLatencyMs.toDouble(),
                    "Латентность агента %.0f ms".format(last.latencyMs ?: 0.0))
            }
        } catch (_: Exception) {}

        return Result.success()
    }

    private fun notifyThreshold(store: SessionStore, serverId: String, kind: String, value: Double?, threshold: Double, msg: String) {
        if (value == null || value < threshold) return
        val key = "thr|$serverId|$kind|${(value / 5).toInt()}" // дебаунс: новый пуш при росте на ~5%
        val seen = store.seenAlertIds.toMutableSet()
        if (key in seen) return
        seen += key
        store.seenAlertIds = if (seen.size > 400) seen.toList().takeLast(300).toSet() else seen
        AlertNotifier.postMessage(applicationContext, key, "🔥 $serverId", msg, big = false)
    }

    private fun levelLabel(level: String): String = when (level.lowercase()) {
        "critical", "crit", "error" -> "КРИТИЧЕСКИЙ алерт"
        "warning", "warn" -> "Предупреждение"
        else -> "Алерт"
    }

    companion object {
        private const val WORK_NAME = "hydra-alert-poll"

        /** Перепланировать периодический опрос (после логина/смены настроек). */
        fun schedule(ctx: Context, intervalMin: Int) {
            val safeInterval = intervalMin.coerceIn(15, 720) // ограничение PeriodicWorkRequest
            val req = PeriodicWorkRequestBuilder<AlertPollWorker>(safeInterval.toLong(), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInitialDelay(1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req
            )
            // разовая проверка сразу после логина
            WorkManager.getInstance(ctx).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<AlertPollWorker>().build()
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }
    }
}
