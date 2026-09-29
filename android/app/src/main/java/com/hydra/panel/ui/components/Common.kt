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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
            Text(text = message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
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

// ───────────────────────── Футуристичные компоненты ─────────────────────────

/** Неоновый градиент заголовка (primary → secondary). */
@Composable
fun neonGradient(): Brush = Brush.linearGradient(
    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
)

/** Заголовок экрана с неоновым градиентом и подзаголовком. */
@Composable
fun NeonHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Стеклянная карточка: полупрозрачный фон + тонкая светящаяся рамка. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    glow: Color? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val borderColor = glow ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    Surface(
        modifier = modifier
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** Светящийся круглый бейдж статуса с пульсацией для «живых» состояний. */
@Composable
fun GlowStatusDot(status: String?, pulse: Boolean = true, size: Dp = 10.dp) {
    val c = statusColor(status)
    val live = status?.lowercase() in setOf("active", "ok", "up", "running", "healthy")
    val alpha by animateFloatAsState(
        targetValue = if (pulse && live) 0.9f else 0.35f,
        animationSpec = if (pulse && live) infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse) else tween(300),
        label = "glowPulse",
    )
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 2.6f)) { drawCircle(c.copy(alpha = alpha * 0.35f), radius = size.toPx()) }
        Canvas(Modifier.size(size)) { drawCircle(c) }
    }
}

/** Статистическая плитка в стиле HUD: крупное число + подпись + опциональный спарклайн. */
@Composable
fun HudStat(
    label: String,
    value: String,
    sub: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    trend: List<Double>? = null,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier.fillMaxWidth(), glow = accent.copy(alpha = 0.35f), contentPadding = PaddingValues(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(label.uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (!trend.isNullOrEmpty()) {
            Spacer(Modifier.height(8.dp))
            SparkLine(trend, accent, Modifier.fillMaxWidth().height(40.dp))
        }
    }
}

/** Градиентная кнопка (primary → secondary) со скруглением capsule. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val shape = CircleShape
    val bg = if (enabled) Brush.horizontalGradient(listOf(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary,
    )) else Brush.horizontalGradient(listOf(
        MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant,
    ))
    Box(
        modifier.fillMaxWidth().height(50.dp).clip(shape)
            .background(bg)
            .then(if (enabled && !busy) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.onPrimary)
        else Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.2.sp)
    }
}

/** Пульсирующая подсветка ошибки (для MessageBar / полей формы). */
@Composable
fun ErrorBubble(message: String, modifier: Modifier = Modifier, onDismiss: () -> Unit = {}) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier.fillMaxWidth().clip(shape).border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), shape)
            .clickable(onClick = onDismiss),
        shape = shape,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⚠", color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Text(text = message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/** Тонкий разделитель-градиент. */
@Composable
fun NeonDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(
        Brush.horizontalGradient(listOf(
            Color.Transparent, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), Color.Transparent,
        ))))
}

/** Чип-бейдж (протокол, статус, тег). */
@Composable
fun ChipBadge(text: String, color: Color = MaterialTheme.colorScheme.secondary, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Box(modifier.clip(shape).background(color.copy(alpha = 0.14f)).border(1.dp, color.copy(alpha = 0.45f), shape)
        .padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}
