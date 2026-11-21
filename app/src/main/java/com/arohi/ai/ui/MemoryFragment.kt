package com.arohi.ai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arohi.ai.R
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.core.SettingsStore
import com.arohi.ai.databinding.FragmentMemoryBinding
import com.arohi.ai.memory.MemoryDatabase
import com.arohi.ai.memory.MemoryItem
import com.arohi.ai.memory.MemoryRepository
import com.arohi.ai.util.Extensions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.Gravity

class MemoryFragment : Fragment() {

    private var _binding: FragmentMemoryBinding? = null
    private val binding get() = _binding!!
    private val adapter = MemoryAdapter(
        onEdit = { item -> editDialog(item) },
        onDelete = { item ->
            lifecycleScope.launch(Dispatchers.IO) {
                MemoryDatabase.get(requireContext()).memoryDao().deleteById(item.id)
                ArohiBus.toast("স্মৃতি মুছে ফেলা হয়েছে")
            }
        }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMemoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        binding.memoryList.layoutManager = LinearLayoutManager(ctx)
        binding.memoryList.adapter = adapter

        val repo = MemoryRepository.get(ctx)

        val memSwitch = binding.memorySwitch
        memSwitch.isChecked = SettingsStore.get(ctx).memoryEnabled
        memSwitch.setOnCheckedChangeListener { _, checked ->
            SettingsStore.get(ctx).memoryEnabled = checked
            ArohiBus.toast(if (checked) "মেমরি সিস্টেম চালু" else "মেমরি সিস্টেম বন্ধ")
        }

        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim() ?: ""
                lifecycleScope.launch(Dispatchers.IO) {
                    val dao = MemoryDatabase.get(ctx).memoryDao()
                    val list = if (q.isEmpty()) dao.topMemories(200) else dao.topMemories(500)
                        .filter { it.content.contains(q, ignoreCase = true) }
                    withContext(Dispatchers.Main) { adapter.submit(list) }
                }
            }
        })

        binding.addMemory.setOnClickListener { editDialog(null) }

        binding.exportMemory.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val json = try { repo.exportJson() } catch (_: Exception) { null }
                withContext(Dispatchers.Main) {
                    if (json == null) { ArohiBus.toast("এক্সপোর্ট ব্যর্থ"); return@withContext }
                    try {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,
                                "arohi_memory_${System.currentTimeMillis()}.json")
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                        }
                        val uri = ctx.contentResolver.insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        uri?.let { ctx.contentResolver.openOutputStream(it)?.use { os -> os.write(json.toByteArray()) } }
                        ArohiBus.toast("স্মৃতি Download ফোল্ডারে এক্সপোর্ট হয়েছে")
                    } catch (_: Exception) { ArohiBus.toast("এক্সপোর্ট ব্যর্থ") }
                }
            }
        }

        binding.importMemory.setOnClickListener {
            importLauncher.launch("application/json")
        }

        binding.clearMemory.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("সব স্মৃতি মুছবেন?")
                .setMessage("এটি ফিরিয়ে আনা যাবে না। চ্যাট হিস্ট্রি অক্ষত থাকবে।")
                .setPositiveButton("মুছে ফেলো") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        repo.clearMemories()
                        ArohiBus.toast("সব স্মৃতি মুছে ফেলা হয়েছে")
                    }
                }
                .setNegativeButton("না", null).show()
        }

        binding.viewChat.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val chat = try { repo.recentChat(30) } catch (_: Exception) { emptyList() }
                withContext(Dispatchers.Main) {
                    val chatText = chat.joinToString("\n\n") {
                        "[${Extensions.dateTime(it.timestamp)}] ${if (it.role == "user") "শুভ্র" else if (it.role == "arohi") "আরোহী" else "সিস্টেম"}: ${it.text}"
                    }.ifBlank { "চ্যাট হিস্ট্রি খালি" }
                    val tv = TextView(ctx).apply {
                        text = chatText; setPadding(48, 32, 48, 32); textSize = 13f
                    }
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("চ্যাট হিস্ট্রি (সর্বশেষ ৩০)")
                        .setView(android.widget.ScrollView(ctx).apply { addView(tv) })
                        .setPositiveButton("বন্ধ", null)
                        .setNeutralButton("হিস্ট্রি মুছুন") { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) { repo.clearChat() }
                        }
                        .show()
                }
            }
        }

        // initial load + live updates
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                MemoryDatabase.get(ctx).memoryDao().observeAll().collectLatest { list ->
                    withContext(Dispatchers.Main) { adapter.submit(list) }
                }
            } catch (_: Exception) {}
        }
    }

    private val importLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                val n = json?.let { MemoryRepository.get(requireContext()).importJson(it) } ?: 0
                withContext(Dispatchers.Main) { ArohiBus.toast("ইমপোর্ট হয়েছে: $n আইটেম") }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { ArohiBus.toast("ইমপোর্ট ব্যর্থ") }
            }
        }
    }

    private fun editDialog(existing: MemoryItem?) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "যেমন: শুভ্রের প্রিয় খাবার হলো বিরিয়ানি"
            setPadding(48, 24, 48, 8)
            existing?.let { setText(it.content) }
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) "নতুন স্মৃতি যোগ" else "স্মৃতি সম্পাদনা")
            .setView(input)
            .setPositiveButton("সেভ") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = MemoryDatabase.get(ctx)
                    if (existing == null) {
                        MemoryRepository.get(ctx).addMemory(text, "fact", 3)
                    } else {
                        db.memoryDao().update(existing.copy(content = text,
                            updatedAt = System.currentTimeMillis()))
                    }
                }
            }
            .setNegativeButton("বাতিল", null).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

class MemoryAdapter(
    val onEdit: (MemoryItem) -> Unit,
    val onDelete: (MemoryItem) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.VH>() {

    private val items = mutableListOf<MemoryItem>()

    fun submit(list: List<MemoryItem>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val content: TextView = v.findViewById(R.id.memory_content)
        val meta: TextView = v.findViewById(R.id.memory_meta)
        val edit: View = v.findViewById(R.id.memory_edit)
        val delete: View = v.findViewById(R.id.memory_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_memory, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.content.text = item.content
        holder.meta.text = "${item.category} • গুরুত্ব ${item.importance}/5 • ${Extensions.dateTime(item.updatedAt)}"
        holder.edit.setOnClickListener { onEdit(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }
}
