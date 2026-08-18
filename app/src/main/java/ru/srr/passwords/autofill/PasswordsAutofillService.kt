package ru.srr.passwords.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import kotlinx.coroutines.runBlocking
import ru.srr.passwords.R
import ru.srr.passwords.auth.AuthGate
import ru.srr.passwords.data.PasswordEntry
import ru.srr.passwords.data.PasswordKind
import ru.srr.passwords.data.PasswordRepository
import ru.srr.passwords.util.AppPackageMatcher
import ru.srr.passwords.util.FaviconResolver
import ru.srr.passwords.util.SiteDisplayName
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prefer inline chip above keyboard. Menu presentation is invisible (0dp) so
 * dropdown next to the field does not appear. Auth → picker → Dataset fill.
 */
class PasswordsAutofillService : AutofillService() {

    private val io = Executors.newSingleThreadExecutor()
    private val piSeq = AtomicInteger(2000)

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        io.execute {
            try {
                if (cancellationSignal.isCanceled) return@execute
                val response = buildFillResponse(request)
                dbg("fill done ok=${response != null} flags=${request.flags}")
                callback.onSuccess(response)
            } catch (e: Exception) {
                Log.e(TAG, "fill failed", e)
                dbg("fill EX ${e.message}")
                try {
                    callback.onSuccess(null)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun buildFillResponse(request: FillRequest): FillResponse? {
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            dbg("no structure")
            return null
        }
        val parser = LoginStructureParser(structure)
        dbg(
            "pkg=${parser.appPackage} act=${parser.activityClass} domain=${parser.webDomain} " +
                "user=${parser.usernameId != null} pass=${parser.passwordId != null} " +
                "submit=${parser.submitId != null} search=${parser.focusedIsSearch}"
        )

        // Track typed credentials for apps that never fire system save UI (Drive2 etc.)
        trackManualCredentials(request, parser)

        if (!parser.isLoginForm()) {
            maybeOfferManualSave(parser)
            dbg("skip: not a login form")
            return null
        }

        val usernameId = parser.usernameId
        val passwordId = parser.passwordId
        val fieldIds = listOfNotNull(usernameId, passwordId)
        if (fieldIds.isEmpty()) {
            dbg("no login targets")
            return null
        }

        val domain = parser.webDomain?.removePrefix("www.").orEmpty()
        val pkg = parser.appPackage.orEmpty()
        // Include every username-like id + currently focused field so the chip
        // shows on phone/email, not only on password.
        val userIds = buildList {
            addAll(parser.usernameIds)
            parser.usernameId?.let { add(it) }
            val focused = parser.focusedId
            if (focused != null &&
                !parser.focusedIsSearch &&
                focused != passwordId
            ) {
                add(focused)
            }
        }.distinct()
        dbg("fieldIds user=${userIds.size} pass=${passwordId != null} focusUser=${parser.focusedId != null && parser.focusedId != passwordId}")

        val clientState = Bundle().apply {
            putParcelable(KEY_USERNAME_ID, usernameId)
            putParcelable(KEY_PASSWORD_ID, passwordId)
            putParcelableArrayList(KEY_USERNAME_IDS, ArrayList(userIds))
            putString(KEY_DOMAIN, domain)
            putString(KEY_PACKAGE, pkg)
        }

        val saveInfo = buildSaveInfo(usernameId, passwordId, fieldIds, parser.submitId)

        // Prefer specs presence over FLAG_SUPPORTS_INLINE_SUGGESTIONS (0x80):
        // some IMEs send specs before the flag is set.
        val specs = inlineSpecs(request)
        val wantsInline =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && specs.isNotEmpty()

        val repo = PasswordRepository.get(this)
        runBlocking { repo.load() }
        val candidates = rankMatches(repo.entries(), domain, pkg).take(MAX_INLINE)

        val response = FillResponse.Builder()
            .setClientState(clientState)
            .setSaveInfo(saveInfo)

        // When IME has no inline specs yet, still return auth datasets with an
        // invisible menu presentation. Returning null / save-only makes Android
        // wait for a focus change; menu datasets let it re-request with inline
        // on the same focused field once the keyboard is ready.
        var added = 0
        candidates.forEachIndexed { index, entry ->
            val title = SiteDisplayName.primary(entry)
            val subtitle = entry.username.ifBlank { domain.ifBlank { getString(R.string.app_name) } }
            val authPi = authPendingIntent(entry.id, domain)
            if (wantsInline) {
                val inline = buildInline(specs, index, authPi, title, subtitle) ?: return@forEachIndexed
                response.addDataset(inlineAuthDataset(userIds, passwordId, authPi, inline, title))
            } else {
                response.addDataset(menuAuthDataset(userIds, passwordId, authPi, title))
            }
            added++
        }

        val pickTitle = getString(R.string.app_name)
        val pickSub = if (domain.isNotBlank()) domain else getString(R.string.autofill_pick_any)
        val pickPi = authPendingIntent(entryId = null, domain = domain)
        if (wantsInline) {
            val pickInline = buildInline(
                specs,
                added.coerceAtMost(specs.lastIndex.coerceAtLeast(0)),
                pickPi,
                pickTitle,
                pickSub
            )
            if (pickInline != null) {
                response.addDataset(inlineAuthDataset(userIds, passwordId, pickPi, pickInline, pickTitle))
                added++
            }
        } else {
            response.addDataset(menuAuthDataset(userIds, passwordId, pickPi, pickTitle))
            added++
        }

        dbg(
            if (wantsInline) {
                "inline datasets=$added domain=$domain matched=${candidates.size} flags=${request.flags}"
            } else {
                "menu datasets=$added (await inline) domain=$domain flags=${request.flags}"
            }
        )
        scheduleDelayedSaveOffer(pkg, domain)
        if (added == 0) {
            return FillResponse.Builder()
                .setClientState(clientState)
                .setSaveInfo(saveInfo)
                .build()
        }
        return response.build()
    }

    private fun trackManualCredentials(request: FillRequest, parser: LoginStructureParser) {
        var bestUser = ""
        var bestPass = ""
        for (ctx in request.fillContexts) {
            val valued = LoginStructureParser(ctx.structure, readValues = true)
            if (!valued.isLoginForm()) continue
            val user = valued.usernameValue?.trim().orEmpty()
            val pass = valued.passwordValue?.trim().orEmpty()
                .replace("\u200B", "")
            if (user.length > bestUser.length) bestUser = user
            if (pass.length > bestPass.length) bestPass = pass
            if (bestUser.isBlank()) {
                val scraped = scrapeUsernameFallback(ctx.structure)
                if (scraped.length > bestUser.length) bestUser = scraped
            }
        }
        if (!parser.isLoginForm() && bestUser.isBlank() && bestPass.length < 2) {
            dbg("track skip: no login credentials")
            return
        }
        if (bestUser.isBlank() && bestPass.length < 2) {
            dbg("track skip: empty fields")
            return
        }
        PendingManualSave.remember(
            pkg = parser.appPackage.orEmpty(),
            domain = parser.webDomain?.removePrefix("www.").orEmpty(),
            username = bestUser,
            password = bestPass
        )
        dbg("pending save tracked userLen=${bestUser.length} passLen=${bestPass.length}")
        scheduleDelayedSaveOffer(
            parser.appPackage.orEmpty(),
            parser.webDomain?.removePrefix("www.").orEmpty()
        )
    }

    private fun scrapeUsernameFallback(structure: android.app.assist.AssistStructure): String {
        var found = ""
        fun walk(node: android.app.assist.AssistStructure.ViewNode) {
            val text = node.text?.toString()?.trim().orEmpty()
            val hint = (node.hint ?: "").lowercase()
            val isPass = hint.contains("pass") || hint.contains("парол") ||
                (node.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
            if (!isPass && text.length in 3..80 && !text.contains('•')) {
                if (text.length > found.length) found = text
            }
            for (i in 0 until node.childCount) walk(node.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode)
        }
        return found
    }

    private val offerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var offerRunnable: Runnable? = null

    private fun scheduleDelayedSaveOffer(pkg: String, domain: String) {
        if (pkg.isBlank() && domain.isBlank()) return
        offerRunnable?.let { offerHandler.removeCallbacks(it) }
        val r = Runnable {
            val pending = PendingManualSave.consume(pkg, domain) ?: return@Runnable
            dbg("delayed offer save userLen=${pending.username.length} passLen=${pending.password.length}")
            launchSaveOffer(pending)
        }
        offerRunnable = r
        // After login navigation many apps never fire another fill — offer anyway.
        offerHandler.postDelayed(r, 4_000L)
    }

    private fun maybeOfferManualSave(parser: LoginStructureParser) {
        val pending = PendingManualSave.consume(
            pkg = parser.appPackage.orEmpty(),
            domain = parser.webDomain?.removePrefix("www.").orEmpty()
        ) ?: run {
            dbg("offer skip: no pending")
            return
        }
        offerRunnable?.let { offerHandler.removeCallbacks(it) }
        dbg("offer manual save for ${pending.domain.ifBlank { pending.pkg }}")
        launchSaveOffer(pending)
    }

    private fun launchSaveOffer(pending: PendingManualSave.Candidate) {
        AuthGate.suppressBriefly(120_000L)
        try {
            startActivity(
                Intent(this, AutofillSaveOfferActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(AutofillSaveOfferActivity.EXTRA_USERNAME, pending.username)
                    putExtra(AutofillSaveOfferActivity.EXTRA_PASSWORD, pending.password)
                    putExtra(AutofillSaveOfferActivity.EXTRA_DOMAIN, pending.domain)
                    putExtra(AutofillSaveOfferActivity.EXTRA_PACKAGE, pending.pkg)
                }
            )
        } catch (e: Exception) {
            dbg("offer start EX ${e.message}")
        }
    }

    private fun buildSaveInfo(
        usernameId: AutofillId?,
        passwordId: AutofillId?,
        fieldIds: List<AutofillId>,
        @Suppress("UNUSED_PARAMETER") submitId: AutofillId?
    ): SaveInfo {
        var saveType = 0
        if (usernameId != null) saveType = saveType or SaveInfo.SAVE_DATA_TYPE_USERNAME
        if (passwordId != null) saveType = saveType or SaveInfo.SAVE_DATA_TYPE_PASSWORD
        if (saveType == 0) saveType = SaveInfo.SAVE_DATA_TYPE_GENERIC

        val required = when {
            passwordId != null -> arrayOf(passwordId)
            else -> fieldIds.toTypedArray()
        }
        val optional = buildList {
            if (usernameId != null && usernameId != passwordId) add(usernameId)
            fieldIds.filter { it != passwordId && it != usernameId }.forEach { add(it) }
        }
        val builder = SaveInfo.Builder(saveType, required)
        if (optional.isNotEmpty()) builder.setOptionalIds(optional.toTypedArray())
        // Prefer finish/invisible — triggerId on WebView submit is unreliable
        builder.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
        return builder.build()
    }

    /**
     * Invisible menu only — first fill before IME supports inline.
     * Keeps the session eligible for an automatic inline re-request
     * without requiring the user to switch fields.
     */
    private fun menuAuthDataset(
        usernameIds: List<AutofillId>,
        passwordId: AutofillId?,
        authPi: PendingIntent,
        @Suppress("UNUSED_PARAMETER") title: String
    ): Dataset {
        val emptyMenu = RemoteViews(packageName, R.layout.autofill_empty)
        val placeholder = AutofillValue.forText(PLACEHOLDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val presentations = Presentations.Builder()
                .setMenuPresentation(emptyMenu)
                .build()
            val ds = Dataset.Builder(presentations)
            ds.setAuthentication(authPi.intentSender)
            for (id in usernameIds.distinct()) {
                ds.setValue(id, placeholder)
            }
            passwordId?.let { ds.setValue(it, placeholder) }
            return ds.build()
        }
        val ds = Dataset.Builder(emptyMenu)
        ds.setAuthentication(authPi.intentSender)
        for (id in usernameIds.distinct()) {
            ds.setValue(id, placeholder)
        }
        passwordId?.let { ds.setValue(it, placeholder) }
        return ds.build()
    }

    /**
     * Invisible menu + real inline. Unpinned so chip returns after clear.
     * Non-empty placeholder: empty values are dropped by OEMs for that field,
     * so the chip would only appear on password.
     */
    private fun inlineAuthDataset(
        usernameIds: List<AutofillId>,
        passwordId: AutofillId?,
        authPi: PendingIntent,
        inline: InlinePresentation,
        title: String
    ): Dataset {
        val emptyMenu = RemoteViews(packageName, R.layout.autofill_empty)
        val label = RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.title, title)
        }
        val placeholder = AutofillValue.forText(PLACEHOLDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val presentations = Presentations.Builder()
                .setMenuPresentation(emptyMenu)
                .setInlinePresentation(inline)
                .build()
            val ds = Dataset.Builder(presentations)
            ds.setAuthentication(authPi.intentSender)
            for (id in usernameIds.distinct()) {
                ds.setValue(id, placeholder)
            }
            passwordId?.let { ds.setValue(it, placeholder) }
            return ds.build()
        }
        val ds = Dataset.Builder(label)
        ds.setAuthentication(authPi.intentSender)
        @Suppress("DEPRECATION")
        ds.setInlinePresentation(inline)
        for (id in usernameIds.distinct()) {
            @Suppress("DEPRECATION")
            ds.setValue(id, placeholder, emptyMenu, inline)
        }
        passwordId?.let {
            @Suppress("DEPRECATION")
            ds.setValue(it, placeholder, emptyMenu, inline)
        }
        return ds.build()
    }

    private fun rankMatches(
        entries: List<PasswordEntry>,
        domainRaw: String,
        pkg: String
    ): List<PasswordEntry> {
        val domain = domainRaw.lowercase(Locale.ROOT).removePrefix("www.")
        val token = domain.substringBefore('.').takeIf { it.length >= 3 }
        val hostsFromPkg = AppPackageMatcher.hostsForPackage(pkg)
            .map { it.lowercase(Locale.ROOT).removePrefix("www.") }
            .toSet()
        val list = entries.filter {
            !it.isDeleted() &&
                it.kind != PasswordKind.CONFIG_VPN &&
                it.kind != PasswordKind.WIFI &&
                (it.username.isNotBlank() || it.password.isNotBlank())
        }
        return list.map { e ->
            val host = FaviconResolver.extractHost(e.url)?.removePrefix("www.")
            val title = SiteDisplayName.primary(e).lowercase(Locale.ROOT)
            val packages = e.packageList()
            val blob = listOf(e.title, e.url, e.username, e.notes, title).joinToString(" ").lowercase(Locale.ROOT)
            val score = when {
                pkg.isNotBlank() && packages.any { it.equals(pkg, true) } -> 120
                host != null && host in hostsFromPkg -> 115
                host != null && domain.isNotBlank() && host == domain -> 100
                host != null && domain.isNotBlank() &&
                    (domain.endsWith(".$host") || host.endsWith(".$domain")) -> 85
                token != null && blob.contains(token) -> 60
                domain.isNotBlank() && blob.contains(domain) -> 55
                else -> 0
            }
            e to score
        }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<PasswordEntry, Int>> { it.second }
                    .thenByDescending { it.first.modifiedAt }
            )
            .map { it.first }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        io.execute {
            try {
                dbg("onSaveRequest")
                PendingManualSave.clear()
                doSave(request)
                callback.onSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "save failed", e)
                dbg("save EX ${e.message}")
                try {
                    callback.onSuccess()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun doSave(request: SaveRequest) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return
        val parser = LoginStructureParser(structure, readValues = true)
        val username = parser.usernameValue?.trim().orEmpty()
        val password = parser.passwordValue?.trim().orEmpty()
        if (username.isBlank() && password.isBlank()) {
            dbg("save skip empty")
            return
        }
        val domain = parser.webDomain?.removePrefix("www.").orEmpty()
        val pkg = parser.appPackage?.takeIf { it != packageName }.orEmpty()
        val title = when {
            domain.isNotBlank() -> domain
            pkg.isNotBlank() -> AppPackageMatcher.brandToken(pkg)?.replaceFirstChar { it.uppercase() } ?: pkg
            else -> "Сохранённый вход"
        }
        val url = if (domain.isNotBlank()) "https://$domain" else ""
        val packages = buildString {
            if (pkg.isNotBlank()) append(pkg)
            AppPackageMatcher.packagesForHost(domain).forEach { p ->
                if (isNotEmpty()) append(',')
                if (!contains(p)) append(p)
            }
        }

        val repo = PasswordRepository.get(this)
        runBlocking { repo.load() }
        val existing = repo.entries().firstOrNull { e ->
            e.deletedAt == null &&
                e.kind == PasswordKind.LOGIN &&
                e.username.equals(username, ignoreCase = true) &&
                (
                    (domain.isNotBlank() && FaviconResolver.extractHost(e.url)?.removePrefix("www.") == domain) ||
                        (pkg.isNotBlank() && e.containsPackage(pkg))
                    )
        }

        when {
            existing == null -> {
                runBlocking {
                    repo.upsert(
                        PasswordEntry(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            username = username,
                            password = password,
                            url = url,
                            notes = "Сохранено из автозаполнения",
                            kind = PasswordKind.LOGIN,
                            faviconUrl = null,
                            modifiedAt = System.currentTimeMillis()
                        ).withPackageCsv(packages)
                    )
                }
                dbg("save new $title")
            }
            password.isBlank() || password == existing.password -> dbg("save noop same")
            else -> {
                AuthGate.suppressBriefly()
                startActivity(
                    Intent(this, AutofillUpdateConfirmActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(AutofillUpdateConfirmActivity.EXTRA_ENTRY_ID, existing.id)
                        putExtra(
                            AutofillUpdateConfirmActivity.EXTRA_TITLE,
                            SiteDisplayName.primary(existing).ifBlank { title }
                        )
                        putExtra(AutofillUpdateConfirmActivity.EXTRA_USERNAME, username)
                        putExtra(AutofillUpdateConfirmActivity.EXTRA_PASSWORD, password)
                        putExtra(AutofillUpdateConfirmActivity.EXTRA_URL, url.ifBlank { existing.url })
                        putExtra(
                            AutofillUpdateConfirmActivity.EXTRA_APP_PACKAGE,
                            packages.ifBlank { existing.packagesSafe() }
                        )
                    }
                )
            }
        }
    }

    private fun authPendingIntent(entryId: String?, domain: String): PendingIntent {
        val intent = Intent(this, AutofillPickerActivity::class.java).apply {
            putExtra(AutofillPickerActivity.EXTRA_HIGHLIGHT_ID, entryId)
            putExtra(AutofillPickerActivity.EXTRA_DOMAIN, domain)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getActivity(this, piSeq.getAndIncrement(), intent, flags)
    }

    private fun inlineSpecs(request: FillRequest): List<InlinePresentationSpec> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return request.inlineSuggestionsRequest?.inlinePresentationSpecs.orEmpty()
    }

    private fun buildInline(
        specs: List<InlinePresentationSpec>,
        index: Int,
        attribution: PendingIntent,
        title: String,
        subtitle: String
    ): InlinePresentation? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || specs.isEmpty()) return null
        return try {
            val spec = specs[index.coerceAtMost(specs.lastIndex)]
            val versions = UiVersions.getVersions(spec.style)
            if (!versions.contains(UiVersions.INLINE_UI_VERSION_1)) return null
            val slice = InlineSuggestionUi
                .newContentBuilder(attribution)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setContentDescription(title)
                .build()
                .slice
            // Unpinned: pinned+auth often invisible on Gboard
            InlinePresentation(slice, spec, false)
        } catch (e: Exception) {
            dbg("inline fail ${e.message}")
            null
        }
    }

    private fun dbg(msg: String) {
        Log.e(TAG, msg)
        try {
            File(filesDir, "autofill-debug.log").appendText("${System.currentTimeMillis()} $msg\n")
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "PasswordsAutofill"
        private const val MAX_INLINE = 4
        /** Zero-width space — keeps field eligible for suggestions without visible text. */
        private const val PLACEHOLDER = "\u200B"
        const val KEY_USERNAME_ID = "username_id"
        const val KEY_USERNAME_IDS = "username_ids"
        const val KEY_PASSWORD_ID = "password_id"
        const val KEY_DOMAIN = "domain"
        const val KEY_PACKAGE = "package"
    }
}
