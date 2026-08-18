package ru.srr.passwords.util

import ru.srr.passwords.data.PasswordKind
import java.net.URI
import java.util.Locale

object FaviconResolver {
    fun resolve(url: String, title: String, kind: PasswordKind): String? {
        if (kind == PasswordKind.WIFI || kind == PasswordKind.CONFIG_VPN) return null
        val host = extractHost(url) ?: guessHost(title) ?: return null
        return "https://www.google.com/s2/favicons?sz=128&domain_url=https://$host"
    }

    fun extractHost(url: String): String? {
        if (url.isBlank()) return null
        val normalized = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "https://$url"
        }
        return try {
            URI(normalized).host?.removePrefix("www.")?.lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        }
    }

    private fun guessHost(title: String): String? {
        val t = title.trim().lowercase(Locale.ROOT)
        val known = mapOf(
            "авито" to "avito.ru",
            "avito" to "avito.ru",
            "альфа" to "alfabank.ru",
            "alfa" to "alfabank.ru",
            "дзен" to "dzen.ru",
            "yandex" to "yandex.ru",
            "яндекс" to "yandex.ru",
            "google" to "google.com",
            "github" to "github.com",
            "vk" to "vk.com",
            "вк" to "vk.com",
            "telegram" to "telegram.org",
            "mail.ru" to "mail.ru",
            "gmail" to "gmail.com",
            "apple" to "apple.com",
            "icloud" to "icloud.com",
            "netflix" to "netflix.com",
            "spotify" to "spotify.com",
            "steam" to "steampowered.com",
            "discord" to "discord.com",
            "instagram" to "instagram.com",
            "facebook" to "facebook.com",
            "twitter" to "x.com",
            "x.com" to "x.com",
            "ozon" to "ozon.ru",
            "wildberries" to "wildberries.ru",
            "wb" to "wildberries.ru",
            "sber" to "sberbank.ru",
            "тинькофф" to "tbank.ru",
            "tinkoff" to "tbank.ru",
            "youtube" to "youtube.com"
        )
        known.forEach { (k, v) -> if (t.contains(k)) return v }
        if (t.contains('.') && !t.contains(' ')) return t.removePrefix("www.")
        return null
    }
}
