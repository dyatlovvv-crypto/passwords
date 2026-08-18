package ru.srr.passwords.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.srr.passwords.R
import ru.srr.passwords.auth.AuthGate
import ru.srr.passwords.data.HomeCategory
import ru.srr.passwords.data.PasswordEntry
import ru.srr.passwords.data.PasswordKind
import ru.srr.passwords.data.PasswordRepository
import ru.srr.passwords.databinding.ActivityListBinding
import ru.srr.passwords.databinding.ItemPasswordBinding
import ru.srr.passwords.databinding.PopupListMoreBinding
import ru.srr.passwords.import.CredentialExporter
import ru.srr.passwords.import.ImportRouter
import ru.srr.passwords.util.IconLoader
import ru.srr.passwords.util.LiquidGlass
import ru.srr.passwords.util.SearchFieldStyle
import ru.srr.passwords.util.SecurityAnalyzer
import ru.srr.passwords.util.SiteDisplayName
import ru.srr.passwords.util.SwipeRevealTouchListener
import ru.srr.passwords.util.VpnFilePicker
import ru.srr.passwords.ui.theme.PasswordsElevation
import java.io.File

class PasswordListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityListBinding
    private lateinit var repo: PasswordRepository
    private lateinit var category: HomeCategory
    private lateinit var adapter: PasswordAdapter
    private var query: String = ""
    private var sortAsc = true
    private var selecting = false
    private val selectedIds = linkedSetOf<String>()
    private var popup: PopupWindow? = null
    private var openSwipeId: String? = null

    private val vpnConfigLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) importAny(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = PasswordRepository.get(this)
        category = HomeCategory.valueOf(intent.getStringExtra(EXTRA_CATEGORY) ?: HomeCategory.ALL.name)
        query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        LiquidGlass.polishBar(binding.bottomBar)

        binding.title.text = titleFor(category)
        binding.searchField.hint = searchHintFor(category)
        binding.searchField.setText(query)
        binding.btnBack.setOnClickListener {
            if (selecting) setSelecting(false) else finish()
        }
        binding.btnAdd.setOnClickListener { openAdd() }
        binding.btnSort.setOnClickListener { toggleSort() }
        binding.btnSort.setOnLongClickListener {
            toggleSort()
            true
        }
        binding.btnMore.setOnClickListener { showMore() }
        binding.btnDone.setOnClickListener { setSelecting(false) }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelected() }
        binding.btnExportSelected.setOnClickListener { exportSelected() }

        val actionWidth = resources.displayMetrics.density * 76f
        adapter = PasswordAdapter(
            scope = lifecycleScope,
            category = category,
            actionWidthPx = actionWidth,
            allProvider = { repo.entries() },
            isSelecting = { selecting },
            isSelected = { id -> id in selectedIds },
            openSwipeId = { openSwipeId },
            onOpenSwipe = { id ->
                val prev = openSwipeId
                openSwipeId = id
                if (prev != null && prev != id) adapter.closeSwipeById(prev)
            },
            onCloseSwipe = { id ->
                if (openSwipeId == id) openSwipeId = null
            },
            onClick = { entry ->
                closeOpenSwipe()
                if (selecting) {
                    toggleSelected(entry.id)
                } else {
                    startActivity(
                        Intent(this, PasswordDetailActivity::class.java)
                            .putExtra(PasswordDetailActivity.EXTRA_ID, entry.id)
                    )
                }
            },
            onDeleteClick = { entry ->
                showMoveToDeletedDialog(listOf(entry.id)) {
                    adapter.closeSwipeById(entry.id)
                    if (openSwipeId == entry.id) openSwipeId = null
                }
            }
        )
        binding.empty.text = emptyTextFor(category)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) closeOpenSwipe()
            }
        })
        SearchFieldStyle.apply(binding.searchField)
        updateBarButtons()
        updateSelectionUi()

        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                refresh()
            }
        })

        lifecycleScope.launch {
            repo.state.collectLatest { refresh() }
        }
    }

    private fun openAdd() {
        when (category) {
            HomeCategory.CONFIG_VPN -> openVpnPicker()
            HomeCategory.PASSKEYS -> startActivity(
                Intent(this, EditPasswordActivity::class.java)
                    .putExtra(EditPasswordActivity.EXTRA_KIND, PasswordKind.PASSKEY.name)
            )
            HomeCategory.CODES -> startActivity(
                Intent(this, EditPasswordActivity::class.java)
                    .putExtra(EditPasswordActivity.EXTRA_KIND, PasswordKind.CODE.name)
            )
            else -> startActivity(Intent(this, EditPasswordActivity::class.java))
        }
    }

    private fun toggleSort() {
        sortAsc = !sortAsc
        updateBarButtons()
        refresh()
        Toast.makeText(this, if (sortAsc) R.string.sort_az else R.string.sort_za, Toast.LENGTH_SHORT).show()
    }

    private fun showMore() {
        popup?.dismiss()
        val content = PopupListMoreBinding.inflate(LayoutInflater.from(this))
        val window = PopupWindow(
            content.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 16f
            isOutsideTouchable = true
        }
        content.rowSelect.setOnClickListener {
            window.dismiss()
            setSelecting(true)
        }
        window.showAsDropDown(binding.btnMore, -120, 8, Gravity.END)
        popup = window
    }

    private fun closeOpenSwipe() {
        val id = openSwipeId ?: return
        openSwipeId = null
        adapter.closeSwipeById(id)
    }

    private fun showMoveToDeletedDialog(ids: List<String>, onCancel: (() -> Unit)? = null) {
        if (ids.isEmpty()) return
        val message = if (ids.size == 1) {
            getString(R.string.confirm_delete_one)
        } else {
            getString(R.string.confirm_delete_many, ids.size)
        }
        val action = if (ids.size == 1) {
            getString(R.string.delete_password)
        } else {
            getString(R.string.delete_passwords)
        }

        val dialog = Dialog(this, R.style.Theme_Passwords_IosAlert)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_ios_delete)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setCancelable(true)
        dialog.setOnCancelListener { onCancel?.invoke() }

        dialog.findViewById<TextView>(R.id.message).text = message
        dialog.findViewById<TextView>(R.id.btnDelete).apply {
            text = action
            setOnClickListener {
                dialog.dismiss()
                lifecycleScope.launch {
                    ids.forEach { repo.softDelete(it) }
                    selectedIds.clear()
                    setSelecting(false)
                }
            }
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            // Soft dim like iOS alert, glass card sits on top
            setDimAmount(0.45f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // Blur behind dialog panel when OEM supports it
                runCatching { setBackgroundBlurRadius(48) }
            }
        }
        dialog.show()
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
                when (val outcome = withContext(Dispatchers.IO) {
                    ImportRouter.ingest(this@PasswordListActivity, uri)
                }) {
                    is ImportRouter.Outcome.Passwords -> {
                        repo.addAll(outcome.entries)
                        Toast.makeText(
                            this@PasswordListActivity,
                            getString(R.string.import_ok, outcome.entries.size),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    is ImportRouter.Outcome.Vpn -> {
                        repo.upsert(outcome.entry)
                        Toast.makeText(
                            this@PasswordListActivity,
                            getString(R.string.vpn_import_ok, outcome.entry.title),
                            Toast.LENGTH_LONG
                        ).show()
                        if (category != HomeCategory.CONFIG_VPN) {
                            startActivity(
                                Intent(this@PasswordListActivity, PasswordListActivity::class.java)
                                    .putExtra(EXTRA_CATEGORY, HomeCategory.CONFIG_VPN.name)
                            )
                        }
                    }
                    ImportRouter.Outcome.Empty -> {
                        Toast.makeText(this@PasswordListActivity, R.string.import_fail, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@PasswordListActivity,
                    e.message ?: getString(R.string.vpn_import_fail),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setSelecting(enabled: Boolean) {
        selecting = enabled
        if (!enabled) selectedIds.clear()
        closeOpenSwipe()
        updateSelectionUi()
        adapter.notifyDataSetChanged()
        refresh()
    }

    private fun toggleSelected(id: String) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
        updateSelectionUi()
        adapter.notifyDataSetChanged()
    }

    private fun updateBarButtons() {
        binding.btnSort.setImageResource(if (sortAsc) R.drawable.ic_sort_az else R.drawable.ic_sort_za)
        binding.btnSort.contentDescription = getString(if (sortAsc) R.string.sort_az else R.string.sort_za)
        binding.btnSort.background = getDrawable(R.drawable.bg_bar_btn)
        binding.btnAdd.background = getDrawable(R.drawable.bg_bar_btn)
        binding.btnSort.backgroundTintList = null
        binding.btnAdd.backgroundTintList = null
    }

    private fun updateSelectionUi() {
        val securityLike = category == HomeCategory.SECURITY
        val noAdd = category == HomeCategory.SECURITY || category == HomeCategory.DELETED
        if (selecting) {
            binding.btnMore.visibility = View.GONE
            binding.btnDone.visibility = View.VISIBLE
            binding.btnAdd.visibility = View.GONE
            binding.btnSort.visibility = View.GONE
            binding.btnDeleteSelected.visibility = View.VISIBLE
            binding.btnExportSelected.visibility = View.VISIBLE
            binding.btnDeleteSelected.text = getString(R.string.delete_selected, selectedIds.size)
            binding.btnExportSelected.text = getString(R.string.export_selected, selectedIds.size)
            val enabled = selectedIds.isNotEmpty()
            binding.btnDeleteSelected.isEnabled = enabled
            binding.btnExportSelected.isEnabled = enabled
            binding.btnDeleteSelected.alpha = if (enabled) 1f else 0.4f
            binding.btnExportSelected.alpha = if (enabled) 1f else 0.4f
            binding.subtitle.text = getString(R.string.selected_count, selectedIds.size)
        } else {
            binding.btnMore.visibility = View.VISIBLE
            binding.btnDone.visibility = View.GONE
            binding.btnAdd.visibility = if (noAdd) View.GONE else View.VISIBLE
            binding.btnSort.visibility = if (securityLike) View.GONE else View.VISIBLE
            binding.btnDeleteSelected.visibility = View.GONE
            binding.btnExportSelected.visibility = View.GONE
        }
    }

    private fun confirmDeleteSelected() {
        showMoveToDeletedDialog(selectedIds.toList())
    }

    private fun exportSelected() {
        if (selectedIds.isEmpty()) return
        lifecycleScope.launch {
            val entries = repo.entries().filter { it.id in selectedIds }
            val csv = CredentialExporter.toCsv(entries)
            val file = File(cacheDir, "passwords-selected.csv")
            file.writeText(csv)
            AuthGate.suppressBriefly()
            val uri = FileProvider.getUriForFile(this@PasswordListActivity, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.passwords_export_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.menu_export)))
            Toast.makeText(this@PasswordListActivity, R.string.export_ok, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refresh() {
        var items = repo.filter(category, query)
        items = if (sortAsc) {
            items.sortedBy { SiteDisplayName.primary(it).lowercase() }
        } else {
            items.sortedByDescending { SiteDisplayName.primary(it).lowercase() }
        }
        adapter.submit(items)
        if (!selecting) {
            binding.subtitle.text = countText(items.size)
        } else {
            binding.subtitle.text = getString(R.string.selected_count, selectedIds.size)
        }
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun countText(n: Int): String = when (category) {
        HomeCategory.PASSKEYS -> getString(R.string.passkeys_count, n)
        HomeCategory.CODES -> getString(R.string.codes_count, n)
        HomeCategory.SECURITY -> getString(R.string.security_count, n)
        else -> getString(R.string.objects_count, n)
    }

    private fun searchHintFor(cat: HomeCategory): String = when (cat) {
        HomeCategory.SECURITY -> getString(R.string.search_in_security)
        HomeCategory.ALL -> getString(R.string.search)
        else -> getString(R.string.search_in_section, titleFor(cat))
    }

    private fun emptyTextFor(cat: HomeCategory): String = when (cat) {
        HomeCategory.PASSKEYS -> getString(R.string.passkeys_empty)
        HomeCategory.CODES -> getString(R.string.codes_empty)
        HomeCategory.CONFIG_VPN -> getString(R.string.vpn_empty)
        else -> getString(R.string.no_items)
    }

    private fun titleFor(cat: HomeCategory): String = when (cat) {
        HomeCategory.ALL -> getString(R.string.all)
        HomeCategory.PASSKEYS -> getString(R.string.passkeys)
        HomeCategory.CODES -> getString(R.string.codes)
        HomeCategory.SECURITY -> getString(R.string.security)
        HomeCategory.CONFIG_VPN -> getString(R.string.config_vpn)
        HomeCategory.DELETED -> getString(R.string.deleted)
    }

    private class PasswordAdapter(
        private val scope: kotlinx.coroutines.CoroutineScope,
        private val category: HomeCategory,
        private val actionWidthPx: Float,
        private val allProvider: () -> List<PasswordEntry>,
        private val isSelecting: () -> Boolean,
        private val isSelected: (String) -> Boolean,
        private val openSwipeId: () -> String?,
        private val onOpenSwipe: (String) -> Unit,
        private val onCloseSwipe: (String) -> Unit,
        private val onClick: (PasswordEntry) -> Unit,
        private val onDeleteClick: (PasswordEntry) -> Unit
    ) : RecyclerView.Adapter<PasswordAdapter.VH>() {
        private var items: List<PasswordEntry> = emptyList()

        fun submit(list: List<PasswordEntry>) {
            items = list
            notifyDataSetChanged()
        }

        fun closeSwipeById(id: String) {
            val pos = items.indexOfFirst { it.id == id }
            if (pos >= 0) notifyItemChanged(pos)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemPasswordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val selectMode = isSelecting()
            val ctx = holder.b.root.context
            val swipeAllowed = !selectMode && category != HomeCategory.DELETED

            holder.b.title.text = SiteDisplayName.primary(item)
            if (category == HomeCategory.SECURITY) {
                val warn = SecurityAnalyzer.warningLabel(item, allProvider())
                holder.b.subtitle.text = when (warn) {
                    "reused" -> ctx.getString(R.string.security_reused)
                    "weak" -> ctx.getString(R.string.security_weak)
                    else -> ctx.getString(R.string.security_stolen)
                }
                holder.b.subtitle.setTextColor(ContextCompat.getColor(ctx, R.color.ios_red))
                holder.b.warn.visibility = View.GONE
            } else {
                holder.b.subtitle.text = SiteDisplayName.secondary(item)
                holder.b.subtitle.setTextColor(ContextCompat.getColor(ctx, R.color.ios_text_secondary))
                val flagged = SecurityAnalyzer.isFlagged(item, allProvider())
                holder.b.warn.visibility = if (!selectMode && flagged) View.VISIBLE else View.GONE
            }
            IconLoader.bind(scope, holder.b.icon, item)
            holder.b.chevron.visibility = if (selectMode) View.GONE else View.VISIBLE
            holder.b.check.visibility = if (selectMode) View.VISIBLE else View.GONE
            if (selectMode) {
                holder.b.check.setImageResource(
                    if (isSelected(item.id)) R.drawable.ic_check_on else R.drawable.ic_check_off
                )
            }

            holder.b.foreground.setOnClickListener(null)
            val swipe = SwipeRevealTouchListener(
                target = holder.b.foreground,
                actionWidthPx = actionWidthPx,
                canSwipe = { swipeAllowed },
                onOpenChanged = { open ->
                    if (open) onOpenSwipe(item.id) else onCloseSwipe(item.id)
                },
                onProgress = { fraction -> holder.applySwipeGlass(fraction) },
                onClick = { onClick(item) }
            )
            holder.swipe = swipe
            holder.b.foreground.setOnTouchListener(swipe)
            val open = openSwipeId() == item.id
            swipe.setOpen(open, animate = false)
            holder.applySwipeGlass(if (open) 1f else 0f)

            holder.b.swipeDelete.setOnClickListener {
                onDeleteClick(item)
            }
        }

        class VH(val b: ItemPasswordBinding) : RecyclerView.ViewHolder(b.root) {
            var swipe: SwipeRevealTouchListener? = null

            fun applySwipeGlass(fraction: Float) {
                val density = b.root.resources.displayMetrics.density
                b.foreground.setBackgroundResource(R.drawable.bg_card)
                b.foreground.elevation = (PasswordsElevation.card + 6f * fraction) * density
                b.separator.alpha = 0f
                b.swipeDelete.alpha = (0.4f + 0.6f * fraction).coerceIn(0f, 1f)
                val scale = 0.9f + 0.1f * fraction
                b.swipeDelete.scaleX = scale
                b.swipeDelete.scaleY = scale
            }
        }
    }

    companion object {
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_QUERY = "query"
    }
}
