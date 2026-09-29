package com.hydra.panel.util

/**
 * Минимальный извлекатель текста/полей из HTML-ответов панели (Jinja-страницы).
 * Нужен как fallback там, где у панели нет JSON-API: логи, конфиги, backup-настройки.
 */
object HtmlParser {

    /** Убирает теги и HTML-сущности, возвращает читаемый текст. */
    fun textContent(html: String): String {
        val noScript = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        val text = noScript.replace(Regex("(?s)<[^>]+>"), "\n")
        return decodeEntities(text)
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** Самый большой <pre> на странице — туда панель рендерит логи. */
    fun extractByClassPre(html: String): String? {
        var best: String? = null
        for (m in Regex("(?is)<pre[^>]*>(.*?)</pre>").findAll(html)) {
            val inner = decodeEntities(m.groupValues[1].replace(Regex("(?s)<[^>]+>"), ""))
            if (best == null || inner.length > best!!.length) best = inner
        }
        return best?.trim()?.ifBlank { null }
    }

    /** Значения form-полей: name → value (текстовые input и textarea). */
    fun formFields(html: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (m in Regex("(?is)<input[^>]*>").findAll(html)) {
            val tag = m.value
            if (Regex("(?i)type=[\"'](password|checkbox|radio|submit)").containsMatchIn(tag)) continue
            val name = Regex("(?i)name=[\"']([^\"']+)[\"']").find(tag)?.groupValues?.get(1) ?: continue
            val value = Regex("(?i)value=[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1) ?: ""
            if (!out.containsKey(name)) out[name] = decodeEntities(value)
        }
        for (m in Regex("(?is)<textarea[^>]*name=[\"']([^\"']+)[\"'][^>]*>(.*?)</textarea>").findAll(html)) {
            out[m.groupValues[1]] = decodeEntities(m.groupValues[2])
        }
        return out
    }

    private val entities = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&#34;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ",
    )

    fun decodeEntities(s: String): String {
        var out = s
        // сначала числовые, потом именованные (чтобы &amp;#39; не разворачивался дважды криво)
        out = Regex("&#(\\d+);").replace(out) { mr ->
            mr.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: mr.value
        }
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { mr ->
            mr.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: mr.value
        }
        for ((k, v) in entities) out = out.replace(k, v)
        return out
    }
}
