package ru.srr.passwords.util

import ru.srr.passwords.data.PasswordEntry
import java.util.Locale

/**
 * Maps Android app packages ↔ website domains for stronger autofill matching.
 */
object AppPackageMatcher {

    private val packageToHosts = mapOf(
        "ru.mvideo.mobile" to listOf("mvideo.ru"),
        "ru.mvideo.app" to listOf("mvideo.ru"),
        "ru.dns.shop.android" to listOf("dns-shop.ru"),
        "ru.citilink" to listOf("citilink.ru"),
        "ru.ozon.app.android" to listOf("ozon.ru"),
        "com.wildberries.ru" to listOf("wildberries.ru", "wb.ru"),
        "ru.wildberries.android" to listOf("wildberries.ru"),
        "ru.yandex.android" to listOf("yandex.ru", "ya.ru"),
        "ru.yandex.mail" to listOf("mail.yandex.ru", "yandex.ru"),
        "ru.yandex.searchplugin" to listOf("yandex.ru"),
        "com.yandex.browser" to listOf("yandex.ru"),
        "ru.yandex.taxi" to listOf("taxi.yandex.ru", "yandex.go"),
        "ru.ok.android" to listOf("ok.ru"),
        "com.vkontakte.android" to listOf("vk.com", "vk.ru"),
        "com.vk.im" to listOf("vk.com"),
        "org.telegram.messenger" to listOf("telegram.org", "web.telegram.org"),
        "org.thunderdog.challegram" to listOf("telegram.org"),
        "com.instagram.android" to listOf("instagram.com"),
        "com.facebook.katana" to listOf("facebook.com", "fb.com"),
        "com.facebook.lite" to listOf("facebook.com"),
        "com.twitter.android" to listOf("x.com", "twitter.com"),
        "com.google.android.gm" to listOf("gmail.com", "mail.google.com", "google.com"),
        "com.android.chrome" to listOf(), // browser: rely on webDomain
        "com.android.vending" to listOf("play.google.com"),
        "ru.sberbankmobile" to listOf("sberbank.ru", "online.sberbank.ru"),
        "com.idamob.tinkoff.android" to listOf("tbank.ru", "tinkoff.ru"),
        "ru.alfabank.mobile.android" to listOf("alfabank.ru", "alfa-bank.ru"),
        "ru.vtb24.mobilebanking.android" to listOf("vtb.ru"),
        "ru.raiffeisennews" to listOf("raiffeisen.ru"),
        "com.ionicframework.gazprombank" to listOf("gazprombank.ru"),
        "ru.gazprombank.android.mobilebank.app" to listOf("gazprombank.ru"),
        "ru.mos.ud" to listOf("mos.ru"),
        "ru.rostel" to listOf("rt.ru", "rostelecom.ru"),
        "ru.mts.mymts" to listOf("mts.ru"),
        "ru.beeline.services" to listOf("beeline.ru"),
        "ru.megafon.mlk" to listOf("megafon.ru"),
        "ru.hh.android" to listOf("hh.ru"),
        "ru.avito" to listOf("avito.ru"),
        "com.avito.android" to listOf("avito.ru"),
        "ru.auto.ara" to listOf("auto.ru"),
        "com.drive2" to listOf("drive2.ru", "www.drive2.ru"),
        "ru.autorus" to listOf("autorus.ru"),
        "com.tumblr" to listOf("tumblr.com"),
        "ru.drom.nosale" to listOf("drom.ru"),
        "ru.ivi.client" to listOf("ivi.ru"),
        "tv.okko.android.app" to listOf("okko.tv"),
        "ru.kinopoisk" to listOf("kinopoisk.ru"),
        "com.netflix.mediaclient" to listOf("netflix.com"),
        "com.spotify.music" to listOf("spotify.com"),
        "com.discord" to listOf("discord.com"),
        "com.valvesoftware.android.steam.community" to listOf("steampowered.com", "store.steampowered.com"),
        "com.whatsapp" to listOf("whatsapp.com", "web.whatsapp.com"),
        "com.ss.android.ugc.trill" to listOf("tiktok.com"),
        "com.zhiliaoapp.musically" to listOf("tiktok.com"),
        "ru.rutube.app" to listOf("rutube.ru"),
        "com.google.android.youtube" to listOf("youtube.com"),
        "ru.pikabu.android" to listOf("pikabu.ru"),
        "ru.habr.android" to listOf("habr.com"),
        "com.booking" to listOf("booking.com"),
        "com.airbnb.android" to listOf("airbnb.com"),
        "com.ubercab" to listOf("uber.com"),
        "ru.yandex.music" to listOf("music.yandex.ru"),
        "ru.yandex.disk" to listOf("disk.yandex.ru"),
        "com.microsoft.office.outlook" to listOf("outlook.com", "live.com"),
        "com.ebay.mobile" to listOf("ebay.com"),
        "com.amazon.mShop.android.shopping" to listOf("amazon.com", "amazon.ru")
    )

    fun hostsForPackage(pkg: String?): List<String> {
        if (pkg.isNullOrBlank()) return emptyList()
        packageToHosts[pkg]?.let { return it }
        // try without variants
        return packageToHosts.entries.firstOrNull { pkg.startsWith(it.key) }?.value.orEmpty()
    }

    fun packagesForHost(host: String?): List<String> {
        if (host.isNullOrBlank()) return emptyList()
        val h = host.removePrefix("www.").lowercase(Locale.ROOT)
        return packageToHosts.filter { (_, hosts) ->
            hosts.any { it == h || h.endsWith(".$it") || it.endsWith(".$h") }
        }.keys.toList()
    }

    fun brandToken(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        val last = pkg.substringAfterLast('.').lowercase(Locale.ROOT)
        val known = listOf(
            "mvideo", "dns", "ozon", "wildberries", "wb", "avito", "vkontakte", "vk",
            "telegram", "instagram", "facebook", "sberbank", "tinkoff", "alfabank",
            "yandex", "netflix", "spotify", "discord", "whatsapp", "tiktok", "youtube"
        )
        known.forEach { if (pkg.contains(it) || last.contains(it)) return it }
        return last.takeIf { it.length >= 3 && it != "android" && it != "mobile" && it != "app" }
    }

    fun entryPackages(entry: PasswordEntry): Set<String> {
        val out = linkedSetOf<String>()
        entry.packageList()
            .filter { it.contains('.') }
            .forEach { out += it }
        packagesForHost(FaviconResolver.extractHost(entry.url)).forEach { out += it }
        return out
    }
}
