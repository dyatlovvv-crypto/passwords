package ru.srr.passwords.dump

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import ru.srr.passwords.data.PasswordRepository
import ru.srr.passwords.import.CredentialExporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plaintext vault copy for recovery. Survives app uninstall if written to Downloads. */
object VaultDump {
    const val ACTION = "ru.srr.passwords.DUMP_VAULT"
    const val LATEST_CSV = "passwords-dump-latest.csv"
    const val LATEST_JSON = "passwords-dump-latest.json"

    fun write(context: Context): Result {
        val repo = PasswordRepository.get(context)
        runBlocking { repo.load() }
        val entries = repo.entries()
        val csv = CredentialExporter.toCsv(entries)
        val json = Gson().toJson(repo.state.value)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        File(context.filesDir, LATEST_CSV).writeText(csv)
        File(context.filesDir, LATEST_JSON).writeText(json)
        File(context.filesDir, "passwords-dump-$stamp.csv").writeText(csv)
        File(context.filesDir, "passwords-dump-$stamp.json").writeText(json)

        writeDownloads(context, LATEST_CSV, csv, "text/csv")
        writeDownloads(context, LATEST_JSON, json, "application/json")
        writeDownloads(context, "passwords-dump-$stamp.csv", csv, "text/csv")
        writeDownloads(context, "passwords-dump-$stamp.json", json, "application/json")

        android.util.Log.i("VaultDump", "wrote ${entries.size} entries stamp=$stamp")
        return Result(count = entries.size, stamp = stamp)
    }

    private fun writeDownloads(context: Context, name: String, text: String, mime: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(name)
                )
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                File(dir, name).writeText(text)
            }
        } catch (e: Exception) {
            android.util.Log.w("VaultDump", "Downloads write failed for $name", e)
        }
    }

    data class Result(val count: Int, val stamp: String)
}
