package com.hydra.panel.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.background
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydra.panel.ui.theme.StatusAmber
import com.hydra.panel.ui.theme.StatusGray
import com.hydra.panel.ui.theme.StatusGreen
import com.hydra.panel.ui.theme.StatusRed

/** Цвет по статусу сервера/ключа/сессии. */
fun statusColor(status: String?): Color = when (status?.lowercase()) {
    "active", "ok", "up", "running", "healthy" -> StatusGreen
    "offline", "down", "error", "critical", "blocked", "expired" -> StatusRed
    "unknown", "degraded", "warning", "pending" -> StatusAmber
    else -> StatusGray
}


/** Status color with alpha applied (для фонов карточек). */
fun statusTint(status: String?, alpha: Float): Color = statusColor(status).copy(alpha = alpha)


/** Состояние асинхронного действия: busy + показ результата в Snackbar. */
class ActionRunner(val scope: CoroutineScope, private val host: SnackbarHostState) {
    var busy by mutableStateOf(false)
        private set

    fun run(block: suspend () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            val outcome = runCatching { block() }
            busy = false
            val msg = outcome.exceptionOrNull()?.let { "Ошибка: ${it.message ?: "сеть"}" }
                ?: outcome.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "Готово"
            host.showSnackbar(msg)
        }
    }
}

@Composable
fun rememberActionRunner(host: SnackbarHostState): ActionRunner {
    val scope = rememberCoroutineScope()
    return remember(host) { ActionRunner(scope, host) }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
fun MessageBar(message: String?, isError: Boolean = false, onDismiss: () -> Unit = {}) {
    if (message.isNullOrBlank()) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable(onClick = onDismiss),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
            Text("✕", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun LoadingBlock(text: String = "Загрузка…") {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatusDot(status: String?, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(statusColor(status)))
}

@Composable
fun StatCard(label: String, value: String, sub: String? = null, color: Color? = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = color ?: MaterialTheme.colorScheme.onSurface)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MonoText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun ActionButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    if (danger) {
        OutlinedButton(onClick, modifier, enabled = enabled && !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Text(label, color = MaterialTheme.colorScheme.error)
        }
    } else {
        Button(onClick, modifier, enabled = enabled && !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text(label)
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Спарклайн по списку Double. */
@Composable
fun SparkLine(data: List<Double>, color: Color, modifier: Modifier = Modifier) {
    val pts = data.filter { it.isFinite() }
    if (pts.size < 2) {
        Box(modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Text("мало данных", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val maxV = (pts.maxOrNull() ?: 1.0).coerceAtLeast(1e-9)
    val minV = pts.minOrNull() ?: 0.0
    Canvas(modifier.fillMaxWidth().height(48.dp)) {
        val w = size.width; val h = size.height
        val stepX = w / (pts.size - 1)
        val path = Path().apply {
            pts.forEachIndexed { i, v ->
                val x = i * stepX
                val y = h - ((v - minV) / maxV.coerceAtLeast(1e-9)).toFloat().coerceIn(0f, 1f) * h * 0.9f - 2
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}

/** Кликабельная карточка (в Material3 нет Card с onClick без экспериментального API). */
@Composable
fun ClickableCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(content = content)
    }
}

/** Карточка-контейнер с колонкой внутри (обёртка над M3 Card + Column). */
@Composable
fun VCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.material3.Card(modifier) { Column(content = content) }
}

/** Карточка с настраиваемыми цветами контейнера. */
@Composable
fun VCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Card(
        modifier = modifier,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = containerColor),
    ) { Column(content = content) }
}
