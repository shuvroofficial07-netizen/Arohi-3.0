package com.arohi.ai.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arohi.ai.R
import com.arohi.ai.core.ArohiBus
import com.arohi.ai.databinding.FragmentFilesBinding
import com.arohi.ai.files.PhoneFiles
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v5 — FILES screen: ফোনের সম্পূর্ণ ফাইল ব্রাউজার + গ্যালারি।
 * প্রতিটি বাটন বাস্তব: ফোল্ডার খোলা, ফাইল খোলা, শেয়ার, মুছা, খোঁজা,
 * ছবি/ভিডিও খোলা — সব প্রকৃত সিস্টেম কল।
 */
class FilesFragment : Fragment() {

    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!
    private val fileAdapter = FileAdapter()
    private val galleryAdapter = GalleryAdapter()
    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    private var currentPath: String? = null

    private val mediaPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) { refreshGallery(); ArohiBus.toast("গ্যালারি অনুমতি পাওয়া গেছে") }
        else ArohiBus.toast("অনুমতি ছাড়া গ্যালারি দেখা সম্ভব নয়")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.filesList.layoutManager = LinearLayoutManager(requireContext())
        binding.filesList.adapter = fileAdapter
        binding.galleryGrid.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.galleryGrid.adapter = galleryAdapter

        // quick roots
        binding.rootChips.removeAllViews()
        PhoneFiles.QUICK_ROOTS.forEach { (label, path) ->
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                text = label
                isClickable = true
                setOnClickListener { openDir(path) }
            }
            binding.rootChips.addView(chip)
        }

        // permission buttons — REAL system pages
        binding.btnAllFiles.setOnClickListener {
            if (!PhoneFiles.openAllFilesSettings(requireContext()))
                ArohiBus.toast("সেটিংস পেজ খোলা যায়নি")
        }
        binding.btnMediaPerm.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                mediaPerm.launch(arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO))
            } else {
                mediaPerm.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            }
        }

        // search across the whole phone
        binding.btnSearch.setOnClickListener { runSearch() }
        binding.searchInput.setOnEditorActionListener { _, _, _ -> runSearch(); true }

        // mode toggle
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == R.id.tab_files) {
                binding.filesList.visibility = View.VISIBLE
                binding.galleryGrid.visibility = View.GONE
            } else {
                binding.filesList.visibility = View.GONE
                binding.galleryGrid.visibility = View.VISIBLE
                refreshGallery()
            }
        }

        refreshPermissionCard()
        openDir(null) // default: quick roots overview
    }

    private fun runSearch() {
        val q = binding.searchInput.text.toString().trim()
        if (q.isEmpty()) { ArohiBus.toast("কী খুঁজবো লিখুন"); return }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val results = PhoneFiles.search(q, 60)
            withContext(Dispatchers.Main) {
                if (results.isEmpty()) {
                    ArohiBus.toast("\"$q\" নামের কোনো ফাইল পাইনি")
                    fileAdapter.submit(emptyList(), "কোনো ফলাফল নেই")
                } else {
                    binding.modeGroup.check(R.id.tab_files)
                    fileAdapter.submit(results, "খোঁজার ফল: ${results.size}টি — \"$q\"")
                    currentPath = null
                }
            }
        }
    }

    private fun refreshPermissionCard() {
        val all = PhoneFiles.hasAllFilesAccess(requireContext())
        val media = PhoneFiles.hasMediaPermission(requireContext())
        binding.permCard.visibility = if (all || media) View.GONE else View.VISIBLE
        binding.permText.text = when {
            all -> "সব ফাইল অ্যাক্সেস চালু ✓"
            media -> "গ্যালারি অনুমতি আছে ✓ — সম্পূর্ণ ফাইল অ্যাক্সেসের জন্য 'সব ফাইল অ্যাক্সেস' চালু করুন"
            else -> "ফোনের সব ফাইল আর গ্যালারি দেখতে 'সব ফাইল অ্যাক্সেস' অনুমতি দিন"
        }
    }

    private fun openDir(path: String?) {
        currentPath = path
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val entries = if (path == null) {
                // overview: quick roots as pseudo entries
                PhoneFiles.QUICK_ROOTS.map { (label, p) ->
                    PhoneFiles.FileEntry(label, p, true, 0L, File(p).lastModified())
                }
            } else PhoneFiles.listDir(path)
            withContext(Dispatchers.Main) {
                binding.pathLabel.text = path ?: "দ্রুত-ফোল্ডার — চিপ থেকে যেকোনো ফোল্ডারে ঢুকুন"
                fileAdapter.submit(entries, null)
            }
        }
    }

    private fun refreshGallery() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val items = PhoneFiles.latestImages(requireContext(), 36) +
                PhoneFiles.latestVideos(requireContext(), 12)
            val counts = PhoneFiles.galleryCounts(requireContext())
            withContext(Dispatchers.Main) {
                galleryAdapter.submit(items)
                if (items.isEmpty()) ArohiBus.toast(
                    if (counts.third > 0) "মিডিয়া আছে কিন্তু অনুমতি নেই — 'গ্যালারি অনুমতি' চাপুন"
                    else "গ্যালারি খালি বা অনুমতি নেই")
            }
        }
    }

    private fun askDelete(entry: PhoneFiles.FileEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("মুছে ফেলবেন?")
            .setMessage("${entry.name} — এটা স্থায়ীভাবে মুছে যাবে।")
            .setPositiveButton("মুছুন") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val msg = PhoneFiles.delete(entry.path)
                    withContext(Dispatchers.Main) {
                        ArohiBus.toast(msg)
                        currentPath?.let { openDir(it) } ?: openDir(null)
                    }
                }
            }
            .setNegativeButton("না", null)
            .show()
    }

    // ---------------- files adapter ----------------
    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.FH>() {
        private val items = mutableListOf<PhoneFiles.FileEntry>()
        private var header: String? = null

        fun submit(list: List<PhoneFiles.FileEntry>, headerLabel: String?) {
            header = headerLabel
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        inner class FH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.file_icon)
            val name: TextView = v.findViewById(R.id.file_name)
            val meta: TextView = v.findViewById(R.id.file_meta)
            val share: ImageButton = v.findViewById(R.id.btn_share)
            val del: ImageButton = v.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FH =
            FH(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: FH, position: Int) {
            val e = items[position]
            holder.icon.text = when {
                e.isDir -> "\uD83D\uDCC1"                // folder
                e.name.endsWith(".jpg", true) || e.name.endsWith(".png", true) -> "\uD83D\uDDBC"
                e.name.endsWith(".mp3", true) || e.name.endsWith(".wav", true) -> "\uD83C\uDFB5"
                e.name.endsWith(".mp4", true) || e.name.endsWith(".mkv", true) -> "\uD83C\uDFAC"
                e.name.endsWith(".pdf", true) -> "\uD83D\uDCC4"
                e.name.endsWith(".apk", true) -> "\uD83D\uDCE6"
                e.name.endsWith(".zip", true) -> "\uD83D\uDDC3"
                else -> "\uD83D\uDCC4"
            }
            holder.name.text = e.name
            holder.meta.text = buildString {
                append(if (e.isDir) "ফোল্ডার" else PhoneFiles.humanSize(e.sizeBytes))
                if (e.lastModified > 0) append(" • ").append(dateFmt.format(Date(e.lastModified)))
            }
            holder.itemView.setOnClickListener {
                if (e.isDir) openDir(e.path)
                else ArohiBus.toast(PhoneFiles.open(requireContext(), e.path))
            }
            holder.itemView.setOnLongClickListener {
                askDelete(e); true
            }
            holder.share.setOnClickListener { ArohiBus.toast(PhoneFiles.share(requireContext(), e.path)) }
            holder.del.setOnClickListener { askDelete(e) }
        }
    }

    // ---------------- gallery adapter ----------------
    private inner class GalleryAdapter : RecyclerView.Adapter<GH>() {
        private val items = mutableListOf<PhoneFiles.MediaItem>()

        fun submit(list: List<PhoneFiles.MediaItem>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GH =
            GH(LayoutInflater.from(parent.context).inflate(R.layout.item_gallery, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: GH, position: Int) {
            val m = items[position]
            holder.tag.text = if (m.isVideo) "▶" else ""
            holder.img.setImageBitmap(null)
            holder.img.tag = m.uri.toString()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val bmp = try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                    val stream = requireContext().contentResolver.openInputStream(m.uri)
                    val b = BitmapFactory.decodeStream(stream, null, opts)
                    stream?.close(); b
                } catch (_: Exception) { null }
                bmp?.let {
                    withContext(Dispatchers.Main) {
                        // only apply if this holder still shows the same media
                        if (_binding != null && holder.img.tag == m.uri.toString())
                            holder.img.setImageBitmap(it)
                    }
                }
            }
            holder.itemView.setOnClickListener { ArohiBus.toast(PhoneFiles.openMedia(requireContext(), m)) }
        }
    }

    /** Gallery grid ViewHolder (fragment-level nested class). */
    private class GH(v: View) : RecyclerView.ViewHolder(v) {
        val img: android.widget.ImageView = v.findViewById(R.id.grid_image)
        val tag: TextView = v.findViewById(R.id.grid_tag)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionCard()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
