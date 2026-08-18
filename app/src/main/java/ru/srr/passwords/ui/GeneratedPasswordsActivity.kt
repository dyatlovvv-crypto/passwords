package ru.srr.passwords.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.srr.passwords.R
import ru.srr.passwords.data.PasswordRepository
import ru.srr.passwords.databinding.ActivityGeneratedBinding
import kotlin.random.Random

class GeneratedPasswordsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGeneratedBinding
    private lateinit var repo: PasswordRepository
    private var current = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGeneratedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = PasswordRepository.get(this)

        val adapter = HistoryAdapter { copy(it) }
        binding.history.layoutManager = LinearLayoutManager(this)
        binding.history.adapter = adapter

        binding.btnBack.setOnClickListener { finish() }
        binding.btnGenerate.setOnClickListener { generate() }
        binding.btnCopy.setOnClickListener { copy(current) }

        lifecycleScope.launch {
            repo.state.collectLatest {
                adapter.submit(it.generatedPasswords())
            }
        }
        generate()
    }

    private fun generate() {
        current = buildPassword()
        binding.generatedValue.text = current
        lifecycleScope.launch { repo.rememberGenerated(current) }
    }

    private fun buildPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#\$%*"
        return (1..20).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun copy(value: String) {
        if (value.isBlank()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("password", value))
        Toast.makeText(this, R.string.copy, Toast.LENGTH_SHORT).show()
    }

    private class HistoryAdapter(
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {
        private var items: List<String> = emptyList()
        fun submit(list: List<String>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
            tv.setTextColor(androidx.core.content.ContextCompat.getColor(parent.context, R.color.ios_text))
            return VH(tv)
        }

        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tv.text = items[position]
            holder.tv.setOnClickListener { onClick(items[position]) }
        }

        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    }
}
