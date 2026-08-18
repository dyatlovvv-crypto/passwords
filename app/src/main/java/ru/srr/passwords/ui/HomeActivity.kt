package ru.srr.passwords.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.autofill.AutofillManager
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.srr.passwords.R
import ru.srr.passwords.auth.AuthGate
import ru.srr.passwords.data.HomeCategory
import ru.srr.passwords.data.PasswordRepository
import ru.srr.passwords.databinding.ActivityHomeBinding
import ru.srr.passwords.databinding.ItemCategoryBinding
import ru.srr.passwords.databinding.PopupMoreBinding
import ru.srr.passwords.import.CredentialExporter
import ru.srr.passwords.import.ImportRouter
import ru.srr.passwords.util.LiquidGlass
import ru.srr.passwords.util.SearchFieldStyle
import ru.srr.passwords.util.VpnFilePicker
import java.io.File

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var repo: PasswordRepository
    private var popup: PopupWindow? = null

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAny(uri)
    }

    private val vpnConfigLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) importAny(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = PasswordRepository.get(this)
        LiquidGlass.polishBar(binding.bottomBar)

        val adapter = CategoryAdapter { cat -> openCategory(cat) }
        binding.categoryGrid.layoutManager = GridLayoutManager(this, 2)
        binding.categoryGrid.adapter = adapter
        SearchFieldStyle.apply(binding.searchField)

        lifecycleScope.launch {
            repo.state.collectLatest {
                adapter.submit(
                    listOf(
                        CatUi(HomeCategory.ALL, getString(R.string.all), R.drawable.ic_cat_all, R.drawable.bg_icon_blue, repo.count(HomeCategory.ALL)),
                        CatUi(HomeCategory.PASSKEYS, getString(R.string.passkeys), R.drawable.ic_cat_passkeys, R.drawable.bg_icon_green, repo.count(HomeCategory.PASSKEYS)),
                        CatUi(HomeCategory.CODES, getString(R.string.codes), R.drawable.ic_cat_codes, R.drawable.bg_icon_yellow, repo.count(HomeCategory.CODES)),
                        CatUi(HomeCategory.CONFIG_VPN, getString(R.string.config_vpn), R.drawable.ic_cat_vpn, R.drawable.bg_icon_vpn, repo.count(HomeCategory.CONFIG_VPN)),
                        CatUi(HomeCategory.SECURITY, getString(R.string.security), R.drawable.ic_cat_security, R.drawable.bg_icon_red, repo.count(HomeCategory.SECURITY)),
                        CatUi(HomeCategory.DELETED, getString(R.string.deleted), R.drawable.ic_cat_deleted, R.drawable.bg_icon_orange, repo.count(HomeCategory.DELETED))
                    )
                )
            }
        }

        binding.btnMore.setOnClickListener { showMore() }
        binding.btnAdd.setOnClickListener {
            startActivity(Intent(this, EditPasswordActivity::class.java))
        }
        binding.btnFolder.setOnClickListener { openVpnPicker() }
        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty().trim()
                if (q.length >= 2) {
                    startActivity(
                        Intent(this@HomeActivity, PasswordListActivity::class.java)
                            .putExtra(PasswordListActivity.EXTRA_CATEGORY, HomeCategory.ALL.name)
                            .putExtra(PasswordListActivity.EXTRA_QUERY, q)
                    )
                    binding.searchField.setText("")
                }
            }
        })
    }

    private fun openVpnPicker() {
        AuthGate.suppressBriefly()
        vpnConfigLauncher.launch(VpnFilePicker.createIntent())
    }

    private fun importVpnConfig(uri: Uri) {
        importAny(uri)
    }

    private fun importAny(uri: Uri) {
        lifecycleScope.launch {
            try {
                when (val outcome = withContext(Dispatchers.IO) { ImportRouter.ingest(this@HomeActivity, uri) }) {
                    is ImportRouter.Outcome.Passwords -> {
                        repo.addAll(outcome.entries)
                        Toast.makeText(
                            this@HomeActivity,
                            getString(R.string.import_ok, outcome.entries.size),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is ImportRouter.Outcome.Vpn -> {
                        repo.upsert(outcome.entry)
                        Toast.makeText(
                            this@HomeActivity,
                            getString(R.string.vpn_import_ok, outcome.entry.title),
                            Toast.LENGTH_LONG
                        ).show()
                        openCategory(HomeCategory.CONFIG_VPN)
                    }
                    ImportRouter.Outcome.Empty -> {
                        Toast.makeText(this@HomeActivity, R.string.import_fail, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@HomeActivity,
                    e.message ?: getString(R.string.import_fail),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openCategory(cat: HomeCategory) {
        startActivity(
            Intent(this, PasswordListActivity::class.java)
                .putExtra(PasswordListActivity.EXTRA_CATEGORY, cat.name)
                .putExtra(PasswordListActivity.EXTRA_QUERY, binding.searchField.text?.toString().orEmpty())
        )
    }

    private fun showMore() {
        popup?.dismiss()
        val content = PopupMoreBinding.inflate(LayoutInflater.from(this))
        val window = PopupWindow(
            content.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 16f
            isOutsideTouchable = true
        }
        content.rowGenerated.setOnClickListener {
            window.dismiss()
            startActivity(Intent(this, GeneratedPasswordsActivity::class.java))
        }
        content.rowImport.setOnClickListener {
            window.dismiss()
            AuthGate.suppressBriefly()
            importLauncher.launch(arrayOf("*/*"))
        }
        content.rowAutofill.setOnClickListener {
            window.dismiss()
            openAutofillSettings()
        }
        content.rowExport.setOnClickListener {
            window.dismiss()
            AuthGate.suppressBriefly()
            exportData()
        }
        window.showAsDropDown(binding.btnMore, -180, 8, Gravity.END)
        popup = window
    }

    private fun openAutofillSettings() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_autofill)
            .setMessage(R.string.autofill_hint)
            .setPositiveButton("Настройки") { _, _ ->
                AuthGate.suppressBriefly()
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    try {
                        startActivity(Intent("android.settings.REQUEST_SET_AUTOFILL_SERVICE"))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportData() {
        lifecycleScope.launch {
            val entries = repo.entries()
            val csv = CredentialExporter.toCsv(entries)
            val file = File(cacheDir, "passwords-export.csv")
            file.writeText(csv)
            val uri = FileProvider.getUriForFile(this@HomeActivity, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Пароли")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.menu_export)))
            Toast.makeText(this@HomeActivity, R.string.export_ok, Toast.LENGTH_SHORT).show()
        }
    }

    data class CatUi(
        val category: HomeCategory,
        val label: String,
        val icon: Int,
        val bg: Int,
        val count: Int
    )

    private class CategoryAdapter(
        private val onClick: (HomeCategory) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.VH>() {
        private var items: List<CatUi> = emptyList()

        fun submit(list: List<CatUi>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.label.text = item.label
            holder.b.count.text = item.count.toString()
            holder.b.icon.setImageResource(item.icon)
            holder.b.iconWrap.setBackgroundResource(item.bg)
            holder.b.root.setOnClickListener { onClick(item.category) }
        }

        class VH(val b: ItemCategoryBinding) : RecyclerView.ViewHolder(b.root)
    }
}
