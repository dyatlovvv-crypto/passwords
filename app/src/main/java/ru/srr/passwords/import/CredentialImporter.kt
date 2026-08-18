package ru.srr.passwords.import

import org.json.JSONArray
import org.json.JSONObject
import ru.srr.passwords.data.PasswordEntry
import ru.srr.passwords.data.PasswordKind
import ru.srr.passwords.util.SiteDisplayName
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.regex.Pattern

object CredentialImporter {

    fun looksLikeCredentials(fileName: String, bytes: ByteArray): Boolean {
        val name = fileName.substringAfterLast('/').substringAfterLast(':').lowercase()
        if (name.endsWith(".ovpn") || name.endsWith(".conf") || name.endsWith(".tblk")) return false
        if (name.endsWith(".csv") || name.endsWith(".json")) return true
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
        if (text.startsWith("{") || text.startsWith("[")) return true
        val first = text.lineSequence().firstOrNull().orEmpty().lowercase()
        val hits = listOf("title", "username", "password", "url", "otpauth", "login").count { first.contains(it) }
        return hits >= 2
    }

    fun parse(fileName: String, bytes: ByteArray): List<PasswordEntry> {
        val name = fileName.lowercase()
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        val fromJson = if (name.endsWith(".json") || text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) {
            parseJson(text)
        } else emptyList()
        if (fromJson.isNotEmpty()) return fromJson

        if (name.endsWith(".xml") || text.trimStart().startsWith("<")) {
            val xml = parseXmlWifi(text)
            if (xml.isNotEmpty()) return xml
        }

        if (name.endsWith(".csv") || text.contains(',') || text.contains(';')) {
            val csv = parseCsv(text)
            if (csv.isNotEmpty()) return csv
        }

        val wifiConf = parseWpaSupplicant(text)
        if (wifiConf.isNotEmpty()) return wifiConf

        return parseLooseText(text)
    }

    private fun parseCsv(text: String): List<PasswordEntry> {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val delimiter = detectDelimiter(lines.first())
        val header = splitCsvLine(lines.first(), delimiter).map { it.trim().lowercase().trim('"') }
        val rows = if (looksLikeHeader(header)) lines.drop(1) else lines
        val headers = if (looksLikeHeader(header)) header else emptyList()

        return rows.mapNotNull { line ->
            val cols = splitCsvLine(line, delimiter)
            if (cols.all { it.isBlank() }) return@mapNotNull null
            fun col(vararg names: String): String {
                if (headers.isEmpty()) return ""
                val i = headerIndex(headers, *names)
                if (i >= 0 && i < cols.size) return cols[i].trim().trim('"')
                return ""
            }

            val title = col("title", "name", "title / url", "login_uri", "ssid", "network", "имя")
                .ifBlank { cols.getOrNull(0)?.trim()?.trim('"').orEmpty() }
            val url = col("url", "login_uri", "website", "origin", "сайт", "uri")
                .ifBlank { if (headers.isEmpty()) cols.getOrNull(1)?.trim()?.trim('"').orEmpty() else "" }
            val username = col("username", "user", "login", "login_username", "email", "account", "имя пользователя")
                .ifBlank { if (headers.isEmpty()) cols.getOrNull(2)?.trim()?.trim('"').orEmpty() else "" }
            val password = col("password", "login_password", "pass", "пароль", "psk", "preSharedKey")
                .ifBlank { if (headers.isEmpty()) cols.getOrNull(3)?.trim()?.trim('"').orEmpty() else "" }
            val notes = col("notes", "note", "comment", "заметки")
            val otp = col("otpauth", "totp", "otp", "one-time password")
            val kindCol = col("kind", "type")
            val passkeyCol = col("passkey", "credential_id")
            val ssid = col("ssid", "network")

            val kind = kindFrom(
                kindCol = kindCol,
                title = title,
                url = url,
                otp = otp,
                wifiHint = ssid.isNotBlank() || headers.any { it == "ssid" }
            )
            val isPasskey = kindCol.equals("PASSKEY", true) ||
                passkeyCol.isNotBlank() && passkeyCol != "0" && !passkeyCol.equals("false", true)

            if (title.isBlank() && username.isBlank() && password.isBlank() && kind != PasswordKind.CONFIG_VPN) {
                return@mapNotNull null
            }

            val resolvedTitle = SiteDisplayName.normalizeTitle(title, url, username, kind)
            PasswordEntry(
                id = UUID.randomUUID().toString(),
                title = resolvedTitle,
                username = if (kind == PasswordKind.WIFI) title.ifBlank { username } else username.ifBlank {
                    if (SiteDisplayName.looksLikeLogin(title)) title else ""
                },
                password = password,
                url = if (kind == PasswordKind.WIFI || kind == PasswordKind.CONFIG_VPN) "" else url,
                notes = notes,
                otpSecret = otp,
                kind = if (kind == PasswordKind.PASSKEY) PasswordKind.LOGIN else kind,
                isPasskey = isPasskey || kind == PasswordKind.PASSKEY
            )
        }
    }

    private fun headerIndex(headers: List<String>, vararg names: String): Int {
        names.forEach { raw ->
            val n = raw.lowercase()
            val exact = headers.indexOfFirst { it == n }
            if (exact >= 0) return exact
        }
        names.forEach { raw ->
            val n = raw.lowercase()
            val fuzzy = headers.indexOfFirst { header ->
                header == n ||
                    header.startsWith("$n ") ||
                    header.startsWith("$n/") ||
                    header.endsWith(" $n") ||
                    header.endsWith("/$n")
            }
            if (fuzzy >= 0) return fuzzy
        }
        return -1
    }

    private fun kindFrom(
        kindCol: String,
        title: String,
        url: String,
        otp: String,
        wifiHint: Boolean
    ): PasswordKind {
        val k = kindCol.trim()
        when {
            k.equals("CONFIG_VPN", true) || k.equals("vpn", true) -> return PasswordKind.CONFIG_VPN
            k.equals("WIFI", true) -> return PasswordKind.WIFI
            k.equals("CODE", true) -> return PasswordKind.CODE
            k.equals("PASSKEY", true) -> return PasswordKind.PASSKEY
            k.equals("LOGIN", true) -> return PasswordKind.LOGIN
        }
        if (wifiHint || title.startsWith("WIFI:", true)) return PasswordKind.WIFI
        if (title.endsWith(".ovpn", true) || title.endsWith(".conf", true)) return PasswordKind.CONFIG_VPN
        if (url.endsWith(".ovpn", true) || url.endsWith(".conf", true)) return PasswordKind.CONFIG_VPN
        if (otp.isNotBlank() && url.isBlank()) return PasswordKind.CODE
        return PasswordKind.LOGIN
    }

    private fun looksLikeHeader(cols: List<String>): Boolean {
        val joined = cols.joinToString(" ")
        return listOf("url", "password", "username", "login", "ssid", "name", "title", "uri")
            .any { joined.contains(it) }
    }

    private fun detectDelimiter(line: String): Char {
        val commas = line.count { it == ',' }
        val semis = line.count { it == ';' }
        val tabs = line.count { it == '\t' }
        return when {
            tabs >= commas && tabs >= semis && tabs > 0 -> '\t'
            semis > commas -> ';'
            else -> ','
        }
    }

    private fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        cur.append('"'); i++
                    } else inQuotes = !inQuotes
                }
                c == delimiter && !inQuotes -> {
                    result += cur.toString(); cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        result += cur.toString()
        return result
    }

    private fun parseJson(text: String): List<PasswordEntry> {
        val trimmed = text.trim()
        return try {
            when {
                trimmed.startsWith("[") -> parseJsonArray(JSONArray(trimmed))
                else -> {
                    val obj = JSONObject(trimmed)
                    when {
                        obj.has("items") -> parseBitwarden(obj.getJSONArray("items"))
                        obj.has("accounts") -> parseGenericAccounts(obj.getJSONArray("accounts"))
                        obj.has("passwords") -> parseGenericAccounts(obj.getJSONArray("passwords"))
                        obj.has("entries") -> parseGenericAccounts(obj.getJSONArray("entries"))
                        obj.has("Vault") -> parseAppleLike(obj)
                        else -> parseGenericAccounts(JSONArray().put(obj))
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseJsonArray(arr: JSONArray): List<PasswordEntry> {
        val out = mutableListOf<PasswordEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += fromJsonObject(o)
        }
        return out.filter { it.title.isNotBlank() || it.password.isNotBlank() }
    }

    private fun parseBitwarden(items: JSONArray): List<PasswordEntry> {
        val out = mutableListOf<PasswordEntry>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val login = item.optJSONObject("login")
            val card = item.optJSONObject("card")
            val name = item.optString("name")
            val notes = item.optString("notes")
            val type = item.optInt("type", 1)
            when (type) {
                1 -> { // login
                    val uris = login?.optJSONArray("uris")
                    val url = uris?.optJSONObject(0)?.optString("uri").orEmpty()
                    out += PasswordEntry(
                        id = UUID.randomUUID().toString(),
                        title = name,
                        username = login?.optString("username").orEmpty(),
                        password = login?.optString("password").orEmpty(),
                        url = url,
                        notes = notes,
                        otpSecret = login?.optString("totp").orEmpty(),
                        kind = if (!login?.optString("totp").isNullOrBlank()) PasswordKind.CODE else PasswordKind.LOGIN
                    )
                }
                else -> {
                    if (name.isNotBlank()) {
                        out += PasswordEntry(
                            id = UUID.randomUUID().toString(),
                            title = name,
                            notes = notes + (card?.toString() ?: ""),
                            kind = PasswordKind.LOGIN
                        )
                    }
                }
            }
        }
        return out
    }

    private fun parseGenericAccounts(arr: JSONArray): List<PasswordEntry> {
        val out = mutableListOf<PasswordEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += fromJsonObject(o)
        }
        return out
    }

    private fun parseAppleLike(obj: JSONObject): List<PasswordEntry> {
        val arr = obj.optJSONArray("Vault") ?: obj.optJSONArray("entries") ?: return emptyList()
        return parseGenericAccounts(arr)
    }

    private fun fromJsonObject(o: JSONObject): PasswordEntry {
        val title = firstString(o, "name", "title", "Title", "label", "ssid", "SSID", "network")
        val username = firstString(o, "username", "user", "login", "email", "account")
        val password = firstString(o, "password", "pass", "Password", "psk", "preSharedKey")
        val url = firstString(o, "url", "uri", "website", "origin", "login_uri")
        val notes = firstString(o, "notes", "note", "comment")
        val otp = firstString(o, "totp", "otp", "otpauth")
        val isPasskey = firstString(o, "passkey", "credentialId").isNotBlank() ||
            o.optBoolean("isPasskey", false)
        val kindRaw = firstString(o, "kind", "type")
        val kind = kindFrom(
            kindCol = kindRaw,
            title = title,
            url = url,
            otp = otp,
            wifiHint = o.has("ssid") || o.has("SSID") || kindRaw.equals("wifi", true)
        )
        return PasswordEntry(
            id = UUID.randomUUID().toString(),
            title = SiteDisplayName.normalizeTitle(title, url, username, kind),
            username = if (kind == PasswordKind.WIFI) title.ifBlank { username } else username.ifBlank {
                if (SiteDisplayName.looksLikeLogin(title)) title else ""
            },
            password = password,
            url = if (kind == PasswordKind.WIFI || kind == PasswordKind.CONFIG_VPN) "" else url,
            notes = notes,
            otpSecret = otp,
            kind = if (kind == PasswordKind.PASSKEY) PasswordKind.LOGIN else kind,
            isPasskey = isPasskey || kind == PasswordKind.PASSKEY
        )
    }

    private fun firstString(o: JSONObject, vararg keys: String): String {
        keys.forEach { k ->
            if (o.has(k) && !o.isNull(k)) {
                val v = o.opt(k)
                when (v) {
                    is String -> if (v.isNotBlank()) return v
                    is Number -> return v.toString()
                    is JSONObject -> {
                        val nested = firstString(v, "username", "password", "uri", "url")
                        if (nested.isNotBlank()) return nested
                    }
                }
            }
        }
        return ""
    }

    private fun parseXmlWifi(text: String): List<PasswordEntry> {
        val out = mutableListOf<PasswordEntry>()
        // Android WifiConfigStore / legacy wifi XML
        val networkBlocks = Pattern.compile("<Network>([\\s\\S]*?)</Network>", Pattern.CASE_INSENSITIVE)
            .matcher(text)
        while (networkBlocks.find()) {
            val block = networkBlocks.group(1) ?: continue
            val ssid = xmlString(block, "SSID")?.trim('"') ?: continue
            val psk = xmlString(block, "PreSharedKey")?.trim('"').orEmpty()
            out += PasswordEntry(
                id = UUID.randomUUID().toString(),
                title = ssid,
                username = ssid,
                password = psk,
                kind = PasswordKind.WIFI
            )
        }
        val ssidTags = Pattern.compile("<ssid[^>]*>([^<]+)</ssid>", Pattern.CASE_INSENSITIVE).matcher(text)
        val pskTags = Pattern.compile("<psk[^>]*>([^<]+)</psk>", Pattern.CASE_INSENSITIVE).matcher(text)
        val ssids = mutableListOf<String>()
        val psks = mutableListOf<String>()
        while (ssidTags.find()) ssids += ssidTags.group(1)?.trim()?.trim('"').orEmpty()
        while (pskTags.find()) psks += pskTags.group(1)?.trim()?.trim('"').orEmpty()
        ssids.forEachIndexed { i, s ->
            if (out.none { it.title == s }) {
                out += PasswordEntry(
                    id = UUID.randomUUID().toString(),
                    title = s,
                    username = s,
                    password = psks.getOrNull(i).orEmpty(),
                    kind = PasswordKind.WIFI
                )
            }
        }
        return out
    }

    private fun xmlString(block: String, name: String): String? {
        val p = Pattern.compile(
            "<string\\s+name=\"$name\"[^>]*>([\\s\\S]*?)</string>",
            Pattern.CASE_INSENSITIVE
        )
        val m = p.matcher(block)
        return if (m.find()) m.group(1)?.trim() else null
    }

    private fun parseWpaSupplicant(text: String): List<PasswordEntry> {
        if (!text.contains("network={") && !text.contains("ssid=")) return emptyList()
        val out = mutableListOf<PasswordEntry>()
        val blocks = text.split("network=").drop(1)
        blocks.forEach { raw ->
            val block = raw.substringAfter("{").substringBefore("}")
            val ssid = Regex("ssid=\"([^\"]+)\"").find(block)?.groupValues?.get(1)
                ?: Regex("ssid=(\\S+)").find(block)?.groupValues?.get(1)
                ?: return@forEach
            val psk = Regex("psk=\"([^\"]+)\"").find(block)?.groupValues?.get(1)
                ?: Regex("psk=(\\S+)").find(block)?.groupValues?.get(1)
                ?: ""
            out += PasswordEntry(
                id = UUID.randomUUID().toString(),
                title = ssid,
                username = ssid,
                password = psk,
                kind = PasswordKind.WIFI
            )
        }
        return out
    }

    private fun parseLooseText(text: String): List<PasswordEntry> {
        val out = mutableListOf<PasswordEntry>()
        // WIFI:T:WPA;S:MyNet;P:secret;;
        val wifiQr = Pattern.compile("WIFI:([^\\n]+)", Pattern.CASE_INSENSITIVE).matcher(text)
        while (wifiQr.find()) {
            val body = wifiQr.group(1) ?: continue
            val ssid = Regex("S:([^;]*)").find(body)?.groupValues?.get(1).orEmpty()
            val pass = Regex("P:([^;]*)").find(body)?.groupValues?.get(1).orEmpty()
            if (ssid.isNotBlank()) {
                out += PasswordEntry(
                    id = UUID.randomUUID().toString(),
                    title = ssid,
                    username = ssid,
                    password = pass,
                    kind = PasswordKind.WIFI
                )
            }
        }

        val blocks = text.split(Regex("\\n\\s*\\n"))
        blocks.forEach { block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) return@forEach
            var title = ""
            var user = ""
            var pass = ""
            var url = ""
            lines.forEach { line ->
                val lower = line.lowercase()
                when {
                    lower.startsWith("url:") || lower.startsWith("сайт:") -> url = line.substringAfter(':').trim()
                    lower.startsWith("user") || lower.startsWith("login") || lower.startsWith("email") || lower.startsWith("имя") ->
                        user = line.substringAfter(':').trim()
                    lower.startsWith("pass") || lower.startsWith("пароль") ->
                        pass = line.substringAfter(':').trim()
                    lower.startsWith("ssid") || lower.startsWith("wifi") || lower.startsWith("сеть") ->
                        title = line.substringAfter(':').trim()
                    title.isBlank() && !line.contains(':') -> title = line
                }
            }
            if (pass.isNotBlank() || user.isNotBlank()) {
                val kind = if (title.isNotBlank() && url.isBlank() && (block.contains("ssid", true) || block.contains("wifi", true)))
                    PasswordKind.WIFI else PasswordKind.LOGIN
                out += PasswordEntry(
                    id = UUID.randomUUID().toString(),
                    title = title.ifBlank { user.ifBlank { url } },
                    username = user.ifBlank { if (kind == PasswordKind.WIFI) title else "" },
                    password = pass,
                    url = url,
                    kind = kind
                )
            }
        }
        return out.distinctBy { "${it.title}|${it.username}|${it.password}" }
    }

    fun readStream(stream: InputStream): ByteArray = stream.readBytes()

    fun readText(stream: InputStream): String =
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
}
