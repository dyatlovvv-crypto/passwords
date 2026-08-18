package ru.srr.passwords.autofill

import android.app.Activity
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.service.autofill.Dataset
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.srr.passwords.R
import ru.srr.passwords.auth.AuthGate
import ru.srr.passwords.data.PasswordEntry
import ru.srr.passwords.data.PasswordKind
import ru.srr.passwords.data.PasswordRepository
import ru.srr.passwords.databinding.ActivityAutofillPickerBinding
import ru.srr.passwords.databinding.ItemAutofillPickBinding
import ru.srr.passwords.util.BiometricGate
import ru.srr.passwords.util.FaviconResolver
import ru.srr.passwords.util.IconLoader
import ru.srr.passwords.util.SiteDisplayName
import java.util.Locale

/**
 * Dataset-level auth: return Dataset in EXTRA_AUTHENTICATION_RESULT for immediate fill.
 * Prefer AutofillIds from EXTRA_CLIENT_STATE (same ids as authenticated Dataset).
 */
class AutofillPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutofillPickerBinding
    private var usernameIds: List<AutofillId> = emptyList()
    private var passwordId: AutofillId? = null
    private var domain: String = ""
    private var preselectedEntryId: String? = null
    private var unlocked = false
    private var allEntries: List<PasswordEntry> = emptyList()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        AuthGate.suppressBriefly(120_000L)
        binding = ActivityAutofillPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preselectedEntryId = intent.getStringExtra(EXTRA_HIGHLIGHT_ID)?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(EXTRA_ENTRY_ID)?.takeIf { it.isNotBlank() }
        domain = intent.getStringExtra(EXTRA_DOMAIN).orEmpty()
        resolveFieldIds(intent)

        if (usernameIds.isEmpty() && passwordId == null) {
            Log.e(TAG, "no autofill ids")
            finishCanceled()
            return
        }

        binding.btnClose.setOnClickListener { finishCanceled() }
        binding.subtitle.text = if (domain.isNotBlank()) {
            getString(R.string.autofill_pick_for, domain)
        } else {
            getString(R.string.autofill_pick_any)
        }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (unlocked) submitList(s?.toString().orEmpty())
            }
        })

        // Always ask biometric + show list (never silent re-fill after clear)
        binding.root.alpha = 0f
        BiometricGate.authenticate(
            activity = this,
            title = getString(R.string.auth_title),
            subtitle = "",
            onSuccess = {
                unlocked = true
                AuthGate.unlock()
                AuthGate.suppressBriefly(120_000L)
                binding.root.alpha = 1f
                lifecycleScope.launch { loadAndShowList() }
            },
            onFail = { finishCanceled() }
        )
    }

    private fun resolveFieldIds(intent: Intent) {
        // 1) Client state from original FillResponse — must match authenticated Dataset ids
        val clientState = intent.getBundleExtra(AutofillManager.EXTRA_CLIENT_STATE)
        if (clientState != null) {
            val many = if (Build.VERSION.SDK_INT >= 33) {
                clientState.getParcelableArrayList(PasswordsAutofillService.KEY_USERNAME_IDS, AutofillId::class.java)
            } else {
                @Suppress("DEPRECATION")
                clientState.getParcelableArrayList(PasswordsAutofillService.KEY_USERNAME_IDS)
            }.orEmpty()
            val one = BundleCompat.getParcelable(
                clientState, PasswordsAutofillService.KEY_USERNAME_ID, AutofillId::class.java
            )
            usernameIds = (many + listOfNotNull(one)).distinct()
            passwordId = BundleCompat.getParcelable(
                clientState, PasswordsAutofillService.KEY_PASSWORD_ID, AutofillId::class.java
            )
            if (domain.isBlank()) {
                domain = clientState.getString(PasswordsAutofillService.KEY_DOMAIN).orEmpty()
            }
            Log.e(TAG, "clientState users=${usernameIds.size} pass=${passwordId != null}")
        }

        // 2) Fallback: re-parse AssistStructure
        if (usernameIds.isEmpty() || passwordId == null) {
            val structure: AssistStructure? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE)
            }
            if (structure != null) {
                val parser = LoginStructureParser(structure)
                if (usernameIds.isEmpty()) usernameIds = parser.usernameIds.toList()
                if (passwordId == null) passwordId = parser.passwordId
                if (domain.isBlank()) {
                    domain = parser.webDomain?.removePrefix("www.").orEmpty()
                }
                Log.e(TAG, "structure users=${usernameIds.size} pass=${passwordId != null}")
            }
        }
    }

    private fun finishCanceled() {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, null as Dataset?)
        )
        finish()
    }

    private suspend fun loadAndShowList() {
        val entries = withContext(Dispatchers.IO) {
            val repo = PasswordRepository.get(this@AutofillPickerActivity)
            repo.load()
            repo.entries().filter {
                !it.isDeleted() &&
                    it.kind != PasswordKind.CONFIG_VPN &&
                    it.kind != PasswordKind.WIFI &&
                    (it.username.isNotBlank() || it.password.isNotBlank())
            }
        }
        allEntries = rankForDomain(entries, domain, preselectedEntryId)
        binding.root.alpha = 1f
        submitList(binding.searchField.text?.toString().orEmpty())
    }

    private fun submitList(query: String) {
        val q = query.trim().lowercase(Locale.ROOT)
        val filtered = if (q.isEmpty()) allEntries else allEntries.filter {
            SiteDisplayName.primary(it).lowercase(Locale.ROOT).contains(q) ||
                it.username.lowercase(Locale.ROOT).contains(q) ||
                it.url.lowercase(Locale.ROOT).contains(q)
        }
        binding.empty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.list.adapter = Adapter(filtered) { deliverDataset(it) }
    }

    private fun deliverDataset(entry: PasswordEntry) {
        val presentation = RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.title, SiteDisplayName.primary(entry))
        }
        val dataset = Dataset.Builder(presentation)
        val login = entry.username
        for (id in usernameIds) {
            @Suppress("DEPRECATION")
            dataset.setValue(id, AutofillValue.forText(login), presentation)
        }
        passwordId?.let { id ->
            @Suppress("DEPRECATION")
            dataset.setValue(id, AutofillValue.forText(entry.password), presentation)
        }
        val built = dataset.build()
        val reply = Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, built)
        Log.e(
            TAG,
            "deliver users=${usernameIds.size} pass=${passwordId != null} " +
                "userLen=${login.length} passLen=${entry.password.length} title=${entry.title}"
        )
        PendingManualSave.suppressAfterAutofill()
        setResult(Activity.RESULT_OK, reply)
        finish()
    }

    private fun rankForDomain(
        entries: List<PasswordEntry>,
        domainRaw: String,
        highlightId: String?
    ): List<PasswordEntry> {
        val domain = domainRaw.lowercase(Locale.ROOT).removePrefix("www.")
        val token = domain.substringBefore('.').takeIf { it.length >= 3 }
        return entries.sortedWith(
            compareByDescending<PasswordEntry> { e ->
                when {
                    highlightId != null && e.id == highlightId -> 1000
                    else -> {
                        val host = FaviconResolver.extractHost(e.url)?.removePrefix("www.")
                        val title = SiteDisplayName.primary(e).lowercase(Locale.ROOT)
                        val blob = listOf(e.title, e.url, e.username, title).joinToString(" ").lowercase(Locale.ROOT)
                        when {
                            host == domain -> 100
                            host != null && (domain.endsWith(".$host") || host.endsWith(".$domain")) -> 85
                            token != null && blob.contains(token) -> 40
                            else -> 0
                        }
                    }
                }
            }.thenByDescending { it.modifiedAt }
        )
    }

    private inner class Adapter(
        private val items: List<PasswordEntry>,
        private val onClick: (PasswordEntry) -> Unit
    ) : RecyclerView.Adapter<Adapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemAutofillPickBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = items[position]
            holder.b.title.text = SiteDisplayName.primary(e)
            holder.b.subtitle.text = e.username.ifBlank { SiteDisplayName.secondary(e) }
            IconLoader.bind(lifecycleScope, holder.b.icon, e)
            holder.b.root.setOnClickListener { onClick(e) }
        }

        inner class VH(val b: ItemAutofillPickBinding) : RecyclerView.ViewHolder(b.root)
    }

    companion object {
        private const val TAG = "AutofillPicker"
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_HIGHLIGHT_ID = "highlight_id"
        const val EXTRA_DOMAIN = "domain"
    }
}
