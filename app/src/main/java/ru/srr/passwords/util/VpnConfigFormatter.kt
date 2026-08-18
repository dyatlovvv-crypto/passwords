package ru.srr.passwords.util

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Readable OpenVPN/WireGuard view: sections, bold keys, collapsed certificates.
 */
object VpnConfigFormatter {

    private val blockOpen = Regex("""^<([a-zA-Z0-9_-]+)>\s*$""")
    private val blockClose = Regex("""^</([a-zA-Z0-9_-]+)>\s*$""")

    private val blockTitles = mapOf(
        "ca" to "Сертификат CA",
        "cert" to "Клиентский сертификат",
        "key" to "Приватный ключ",
        "tls-auth" to "TLS Auth",
        "tls-crypt" to "TLS Crypt",
        "tls-crypt-v2" to "TLS Crypt v2"
    )

    fun format(
        raw: String,
        keyColor: Int,
        mutedColor: Int,
        headerColor: Int,
        expandCerts: Boolean = false
    ): CharSequence {
        if (raw.isBlank()) return raw
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').lines()
        val out = SpannableStringBuilder()

        fun appendHeader(text: String) {
            if (out.isNotEmpty()) out.append("\n")
            val start = out.length
            out.append("── ").append(text).append(" ──\n")
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(headerColor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(1.05f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        fun appendKeyValue(key: String, value: String?) {
            val start = out.length
            out.append(key)
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(keyColor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (!value.isNullOrBlank()) {
                out.append("  ")
                val vs = out.length
                out.append(value)
                out.setSpan(ForegroundColorSpan(mutedColor), vs, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            out.append('\n')
        }

        fun appendMuted(text: String) {
            val start = out.length
            out.append(text).append('\n')
            out.setSpan(ForegroundColorSpan(mutedColor), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(0.92f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // Summary first
        val remotes = mutableListOf<String>()
        var proto: String? = null
        var dev: String? = null
        var auth: String? = null
        var cipher: String? = null
        lines.forEach { rawLine ->
            val t = rawLine.trim()
            when {
                t.startsWith("remote ") -> remotes += t.removePrefix("remote ").trim()
                t.startsWith("proto ") -> proto = t.removePrefix("proto ").trim()
                t.startsWith("dev ") -> dev = t.removePrefix("dev ").trim()
                t.startsWith("auth ") && !t.startsWith("auth-") -> auth = t.removePrefix("auth ").trim()
                t.startsWith("cipher ") -> cipher = t.removePrefix("cipher ").trim()
            }
        }
        appendHeader("Обзор")
        if (remotes.isNotEmpty()) remotes.forEach { appendKeyValue("remote", it) }
        proto?.let { appendKeyValue("proto", it) }
        dev?.let { appendKeyValue("dev", it) }
        auth?.let { appendKeyValue("auth", it) }
        cipher?.let { appendKeyValue("cipher", it) }
        if (lines.any { it.trim() == "client" }) appendKeyValue("mode", "client")
        if (lines.any { it.trim() == "auth-user-pass" }) appendKeyValue("login", "логин/пароль")

        appendHeader("Параметры")
        var i = 0
        var wroteParam = false
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            when {
                trimmed.isEmpty() -> i++
                trimmed.startsWith("#") || trimmed.startsWith(";") -> {
                    appendMuted(trimmed)
                    wroteParam = true
                    i++
                }
                blockOpen.matches(trimmed) -> {
                    val tag = blockOpen.matchEntire(trimmed)!!.groupValues[1]
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size) {
                        val bt = lines[i].trim()
                        if (blockClose.matches(bt)) {
                            i++
                            break
                        }
                        if (bt.isNotEmpty()) body += bt
                        i++
                    }
                    appendHeader(blockTitles[tag.lowercase()] ?: "Блок <$tag>")
                    if (!expandCerts && body.size > 4) {
                        appendMuted("▾ свёрнуто · ${body.size} строк")
                        appendMuted("  ${body.first()}")
                        appendMuted("  …")
                        appendMuted("  ${body.last()}")
                        appendMuted("(полный текст ниже в «Сырой файл»)")
                    } else {
                        body.forEach { line ->
                            val s = out.length
                            out.append("  ").append(line).append('\n')
                            out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            out.setSpan(RelativeSizeSpan(0.88f), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            out.setSpan(ForegroundColorSpan(mutedColor), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    wroteParam = true
                }
                trimmed.startsWith("remote ") ||
                    trimmed.startsWith("proto ") ||
                    trimmed.startsWith("dev ") ||
                    (trimmed.startsWith("auth ") && !trimmed.startsWith("auth-")) ||
                    trimmed.startsWith("cipher ") ||
                    trimmed == "client" ||
                    trimmed == "auth-user-pass" -> {
                    // already in overview
                    i++
                }
                else -> {
                    val parts = trimmed.split(Regex("\\s+"), limit = 2)
                    appendKeyValue(parts[0], parts.getOrNull(1))
                    wroteParam = true
                    i++
                }
            }
        }
        if (!wroteParam) appendMuted("нет доп. параметров")

        appendHeader("Сырой файл")
        val rawStart = out.length
        out.append(raw.trimEnd()).append('\n')
        out.setSpan(TypefaceSpan("monospace"), rawStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(0.82f), rawStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(mutedColor), rawStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        return out
    }
}
