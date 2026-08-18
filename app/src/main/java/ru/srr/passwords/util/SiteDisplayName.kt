package ru.srr.passwords.util

import ru.srr.passwords.data.PasswordEntry
import ru.srr.passwords.data.PasswordKind
import java.util.Locale

/**
 * First list line = site/brand name (Авито, Дзен…).
 * Second line = login / phone / email.
 */
object SiteDisplayName {

    private val brandByHost = mapOf(
        "avito.ru" to "Авито",
        "m.avito.ru" to "Авито",
        "alfabank.ru" to "АО Альфа-Банк",
        "alfa-bank.ru" to "АО Альфа-Банк",
        "click.alfabank.ru" to "АО Альфа-Банк",
        "dzen.ru" to "Дзен",
        "zen.yandex.ru" to "Дзен",
        "yandex.ru" to "Яндекс",
        "ya.ru" to "Яндекс",
        "mail.yandex.ru" to "Яндекс",
        "passport.yandex.ru" to "Яндекс",
        "vk.com" to "ВКонтакте",
        "m.vk.com" to "ВКонтакте",
        "vk.ru" to "ВКонтакте",
        "instagram.com" to "Instagram",
        "www.instagram.com" to "Instagram",
        "facebook.com" to "Facebook",
        "fb.com" to "Facebook",
        "x.com" to "X",
        "twitter.com" to "X",
        "telegram.org" to "Telegram",
        "web.telegram.org" to "Telegram",
        "t.me" to "Telegram",
        "google.com" to "Google",
        "accounts.google.com" to "Google",
        "gmail.com" to "Gmail",
        "mail.ru" to "Mail.ru",
        "e.mail.ru" to "Mail.ru",
        "ok.ru" to "Одноклассники",
        "ozon.ru" to "Ozon",
        "wildberries.ru" to "Wildberries",
        "wb.ru" to "Wildberries",
        "sberbank.ru" to "СберБанк",
        "online.sberbank.ru" to "СберБанк",
        "tbank.ru" to "Т-Банк",
        "tinkoff.ru" to "Т-Банк",
        "youtube.com" to "YouTube",
        "github.com" to "GitHub",
        "apple.com" to "Apple",
        "icloud.com" to "iCloud",
        "netflix.com" to "Netflix",
        "spotify.com" to "Spotify",
        "discord.com" to "Discord",
        "steampowered.com" to "Steam",
        "store.steampowered.com" to "Steam",
        "whatsapp.com" to "WhatsApp",
        "web.whatsapp.com" to "WhatsApp",
        "tiktok.com" to "TikTok",
        "pinterest.com" to "Pinterest",
        "linkedin.com" to "LinkedIn",
        "amazon.com" to "Amazon",
        "amazon.ru" to "Amazon",
        "ebay.com" to "eBay",
        "reddit.com" to "Reddit",
        "twitch.tv" to "Twitch",
        "microsoft.com" to "Microsoft",
        "live.com" to "Microsoft",
        "outlook.com" to "Outlook",
        "hh.ru" to "HeadHunter",
        "youla.ru" to "Юла",
        "dns-shop.ru" to "DNS",
        "mvideo.ru" to "М.Видео",
        "citilink.ru" to "Ситилинк",
        "gosuslugi.ru" to "Госуслуги",
        "esia.gosuslugi.ru" to "Госуслуги",
        "nalog.ru" to "ФНС",
        "mos.ru" to "mos.ru",
        "rutube.ru" to "Rutube",
        "okko.tv" to "Okko",
        "ivi.ru" to "IVI",
        "kinopoisk.ru" to "Кинопоиск",
        "drive2.ru" to "Drive2",
        "habr.com" to "Habr",
        "pikabu.ru" to "Пикабу",
        "booking.com" to "Booking",
        "airbnb.com" to "Airbnb",
        "uber.com" to "Uber",
        "citymobil.ru" to "Ситимобил",
        "yandex.go" to "Яндекс Go"
    )

    private val brandKeywords = listOf(
        "авито" to "Авито",
        "avito" to "Авито",
        "альфа" to "АО Альфа-Банк",
        "alfa" to "АО Альфа-Банк",
        "дзен" to "Дзен",
        "dzen" to "Дзен",
        "вконтакте" to "ВКонтакте",
        "instagram" to "Instagram",
        "инстаграм" to "Instagram",
        "телеграм" to "Telegram",
        "telegram" to "Telegram",
        "яндекс" to "Яндекс",
        "yandex" to "Яндекс",
        "одноклассники" to "Одноклассники",
        "госуслуги" to "Госуслуги",
        "сбер" to "СберБанк",
        "тинькофф" to "Т-Банк",
        "т-банк" to "Т-Банк",
        "wildberries" to "Wildberries",
        "вайлдберриз" to "Wildberries"
    )

    fun primary(entry: PasswordEntry): String {
        if (entry.kind == PasswordKind.WIFI) {
            return entry.title.ifBlank { entry.username }.ifBlank { "Wi-Fi" }
        }
        if (entry.kind == PasswordKind.CONFIG_VPN) {
            return entry.title.ifBlank { "vpn-config" }
        }
        fromUrl(entry.url)?.let { return it }
        fromKnownTitle(entry.title)?.let { return it }
        if (!looksLikeLogin(entry.title) && entry.title.isNotBlank()) return entry.title.trim()
        fromUrl(entry.notes)?.let { return it }
        return entry.title.ifBlank { entry.username.ifBlank { "Без названия" } }
    }

    fun secondary(entry: PasswordEntry): String {
        if (entry.kind == PasswordKind.WIFI) {
            return if (entry.password.isNotBlank()) "••••••••" else entry.notes.ifBlank { "Сеть Wi-Fi" }
        }
        if (entry.kind == PasswordKind.CONFIG_VPN) {
            return entry.username.ifBlank { "VPN config" }
        }
        val login = entry.username.trim()
        if (login.isNotBlank()) return login
        val host = FaviconResolver.extractHost(entry.url)
        if (!host.isNullOrBlank() && primary(entry) != host) return host
        return entry.url.ifBlank { entry.notes }.trim()
    }

    /** Prefer site brand as stored title when importing/saving. */
    fun normalizeTitle(rawTitle: String, url: String, username: String, kind: PasswordKind): String {
        if (kind == PasswordKind.WIFI) return rawTitle.ifBlank { username }
        if (kind == PasswordKind.CONFIG_VPN) return rawTitle.ifBlank { "vpn-config" }
        fromUrl(url)?.let { return it }
        fromKnownTitle(rawTitle)?.let { return it }
        if (!looksLikeLogin(rawTitle) && rawTitle.isNotBlank()) return rawTitle.trim()
        val host = FaviconResolver.extractHost(url)
        if (!host.isNullOrBlank()) return prettifyHost(host)
        return rawTitle.ifBlank { username.ifBlank { "Без названия" } }
    }

    private fun fromUrl(url: String): String? {
        val host = FaviconResolver.extractHost(url) ?: return null
        brandByHost[host]?.let { return it }
        // try parent domain: accounts.google.com -> google.com
        val parts = host.split('.')
        if (parts.size >= 3) {
            val parent = parts.takeLast(2).joinToString(".")
            brandByHost[parent]?.let { return it }
            brandByHost[parts.takeLast(3).joinToString(".")]?.let { return it }
        }
        return prettifyHost(host)
    }

    private fun fromKnownTitle(title: String): String? {
        val t = title.trim().lowercase(Locale.ROOT)
        if (t.isBlank() || looksLikeLogin(title)) return null
        brandKeywords.forEach { (k, v) -> if (t.contains(k)) return v }
        brandByHost.entries.forEach { (host, name) ->
            if (t.contains(host) || t.contains(host.substringBefore('.'))) return name
        }
        return null
    }

    private fun prettifyHost(host: String): String {
        val base = host.removePrefix("www.").substringBefore('.')
        if (base.isBlank()) return host
        return base.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    fun looksLikeLogin(value: String): Boolean {
        val v = value.trim()
        if (v.isBlank()) return false
        if (v.contains('@')) return true
        val digits = v.filter { it.isDigit() }
        if (digits.length >= 10 && digits.length >= v.length / 2) return true
        if (v.matches(Regex("^\\+?[0-9\\s()-]{7,}$"))) return true
        return false
    }
}
