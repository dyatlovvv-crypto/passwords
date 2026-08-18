package ru.srr.passwords.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.srr.passwords.data.PasswordEntry
import ru.srr.passwords.data.PasswordKind
import java.util.concurrent.TimeUnit

/**
 * Loads site icons via GET only (favicon). Never uploads vault data.
 */
object IconLoader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val cache = mutableMapOf<String, Bitmap>()
    private val jobs = mutableMapOf<ImageView, Job>()

    fun bind(scope: CoroutineScope, imageView: ImageView, entry: PasswordEntry) {
        jobs[imageView]?.cancel()
        when (entry.kind) {
            PasswordKind.WIFI -> {
                imageView.setImageResource(ru.srr.passwords.R.drawable.ic_cat_wifi)
                return
            }
            PasswordKind.CONFIG_VPN -> {
                imageView.setImageResource(ru.srr.passwords.R.drawable.ic_cat_vpn)
                return
            }
            else -> Unit
        }
        val label = SiteDisplayName.primary(entry)
        val url = entry.faviconUrl ?: FaviconResolver.resolve(entry.url, entry.title, entry.kind)
        if (url.isNullOrBlank()) {
            imageView.setImageBitmap(letterBitmap(label))
            return
        }
        cache[url]?.let {
            imageView.setImageBitmap(it)
            return
        }
        imageView.setImageBitmap(letterBitmap(label))
        val job = scope.launch {
            val bmp = withContext(Dispatchers.IO) { download(url) }
            if (bmp != null) {
                cache[url] = bmp
                imageView.setImageBitmap(bmp)
            }
        }
        jobs[imageView] = job
    }

    /** GET only — downloads image bytes, never POSTs credentials. */
    private fun download(url: String): Bitmap? = try {
        val req = Request.Builder().url(url).get()
            .header("User-Agent", "PasswordsApp/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    } catch (_: Exception) {
        null
    }

    fun letterBitmap(title: String): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorFor(title) }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 22f, 22f, bg)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val letter = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val y = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(letter, size / 2f, y, text)
        return bmp
    }

    private fun colorFor(title: String): Int {
        val palette = intArrayOf(
            0xFF007AFF.toInt(), 0xFF34C759.toInt(), 0xFFFF9500.toInt(),
            0xFFFF3B30.toInt(), 0xFF5856D6.toInt(), 0xFF64D2FF.toInt(),
            0xFFFF2D55.toInt(), 0xFFAF52DE.toInt()
        )
        return palette[kotlin.math.abs(title.hashCode()) % palette.size]
    }
}
