package com.arohi.ai.files

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * v5 — ফোনের সম্পূর্ণ ফাইল ও গ্যালারি অ্যাক্সেস (user spec: "সমস্ত ফোনের
 * ফাইল অ্যাক্সেস করতে পারবে, গ্যালারি অ্যাক্সেস করতে পারবে এবং সবকিছুই")।
 *
 * বাস্তব পথ:
 *  - Android 11+: MANAGE_EXTERNAL_STORAGE ("All files access") → সব ফোল্ডার
 *    java.io.File দিয়ে পড়া/খোঁজা যায়। সেটিংস পেজে সরাসরি অনুমতি ফাঁড়ি।
 *  - Android 13+: READ_MEDIA_IMAGES/VIDEO/AUDIO → গ্যালারি MediaStore কুয়েরি।
 *  - পুরোনো (9–10): READ_EXTERNAL_STORAGE যথেষ্ট।
 * অনুমতি না থাকলে সৎ বাংলা বার্তা — কখনো ভুয়া "দেখালাম" নয়।
 */
object PhoneFiles {

    data class FileEntry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val sizeBytes: Long,
        val lastModified: Long
    )

    data class MediaItem(
        val id: Long,
        val uri: Uri,
        val displayName: String,
        val bucket: String,
        val dateTaken: Long,
        val sizeBytes: Long,
        val isVideo: Boolean
    )

    // ---------------- permissions (honest) ----------------

    fun hasMediaPermission(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 33 ->
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        Build.VERSION.SDK_INT >= 29 ->
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
                hasAllFilesAccess(ctx)
        else ->
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAllFilesAccess(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** Opens the exact system page where Shubho grants "All files access". */
    fun openAllFilesSettings(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        true
    } catch (_: Exception) { false }

    // ---------------- file system browsing ----------------

    val QUICK_ROOTS: List<Pair<String, String>> = listOf(
        "Download" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath,
        "DCIM" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath,
        "Pictures" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath,
        "Music" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath,
        "Movies" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath,
        "Documents" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath,
        "WhatsApp" to "/sdcard/Android/media/com.whatsapp/WhatsApp/Media",
        "sdcard (root)" to "/sdcard"
    )

    fun listDir(path: String, limit: Int = 200): List<FileEntry> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val kids = dir.listFiles() ?: return emptyList()
        return kids.sortedWith(compareByDescending<File> { it.isDirectory }
            .thenByDescending { it.lastModified() })
            .take(limit)
            .map { FileEntry(it.name, it.absolutePath, it.isDirectory, it.length(), it.lastModified()) }
    }

    /** Real recursive name search across common roots (bounded). */
    fun search(query: String, maxResults: Int = 25): List<FileEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<FileEntry>()
        val seenRoots = mutableSetOf<String>()
        for ((_, root) in QUICK_ROOTS) {
            val rootDir = File(root)
            if (!rootDir.exists() || seenRoots.contains(rootDir.absolutePath)) continue
            if (QUICK_ROOTS.any { it.second != root && root.startsWith(it.second) }) continue // nested root
            seenRoots.add(rootDir.absolutePath)
            walk(rootDir, q, out, maxResults, depth = 0)
            if (out.size >= maxResults) break
        }
        return out.take(maxResults)
    }

    private fun walk(dir: File, q: String, out: MutableList<FileEntry>, max: Int, depth: Int) {
        if (out.size >= max || depth > 6) return
        val kids = try { dir.listFiles() ?: return } catch (_: Exception) { return }
        for (f in kids) {
            if (out.size >= max) return
            if (f.name.lowercase().contains(q)) out.add(
                FileEntry(f.name, f.absolutePath, f.isDirectory, f.length(), f.lastModified()))
            if (f.isDirectory) walk(f, q, out, max, depth + 1)
        }
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= (1 shl 30) -> "%.1f GB".format(bytes / 1073741824.0)
        bytes >= (1 shl 20) -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= (1 shl 10) -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** Opens any file with the right viewer — real system view. */
    fun open(ctx: Context, path: String): String = try {
        val f = File(path)
        if (!f.exists()) "ফাইলটা আর নেই — সম্ভবত মুছে ফেলা হয়েছে"
        else {
            val uri: Uri = if (Build.VERSION.SDK_INT >= 24)
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            else Uri.fromFile(f)
            val mime = ctx.contentResolver.getType(uri) ?: guessMime(path)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(i)
            "খুলে দিলাম: ${f.name}"
        }
    } catch (_: Exception) {
        "এই ফাইলটা খোলার কোনো অ্যাপ পাইনি"
    }

    fun share(ctx: Context, path: String): String = try {
        val f = File(path)
        val uri: Uri = if (Build.VERSION.SDK_INT >= 24)
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        else Uri.fromFile(f)
        val i = Intent(Intent.ACTION_SEND).setType(guessMime(path))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "শেয়ার করুন").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "শেয়ার শিট খুলে দিয়েছি"
    } catch (_: Exception) { "শেয়ার করা যায়নি" }

    fun delete(path: String): String = try {
        val f = File(path)
        if (f.isDirectory) {
            if (f.deleteRecursively()) "ফোল্ডার মুছে ফেলেছি: ${f.name}" else "ফোল্ডার মুছা গেল না"
        } else {
            if (f.delete()) "ফাইল মুছে ফেলেছি: ${f.name}" else "ফাইল মুছা গেল না"
        }
    } catch (_: Exception) { "মুছতে সমস্যা হয়েছে" }

    private fun guessMime(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp" -> "image/*"
            "mp4", "mkv", "avi", "mov", "3gp" -> "video/*"
            "mp3", "wav", "ogg", "m4a", "flac" -> "audio/*"
            "pdf" -> "application/pdf"
            "txt", "log" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            else -> "*/*"
        }
    }

    /** Bengali summary of a folder — for voice answers. */
    fun describeDir(path: String): String {
        val entries = listDir(path, 200)
        if (entries.isEmpty()) return "\"$path\" ফোল্ডার খালি বা পাওয়া যায়নি"
        val dirs = entries.count { it.isDir }
        val files = entries.size - dirs
        val top = entries.take(5).joinToString(", ") { it.name }
        return "${entries.size}টি আইটেম (${dirs}টি ফোল্ডার, ${files}টি ফাইল) — সামনেরগুলো: $top"
    }

    // ---------------- gallery (MediaStore) ----------------

    fun latestImages(ctx: Context, limit: Int = 24): List<MediaItem> {
        if (!hasMediaPermission(ctx)) return emptyList()
        val out = mutableListOf<MediaItem>()
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE)
        try {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iBucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(iId)
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    out.add(MediaItem(id, uri, c.getString(iName) ?: "",
                        c.getString(iBucket) ?: "", c.getLong(iDate) * 1000L, c.getLong(iSize), false))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun latestVideos(ctx: Context, limit: Int = 12): List<MediaItem> {
        if (!hasMediaPermission(ctx)) return emptyList()
        val out = mutableListOf<MediaItem>()
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE)
        try {
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val iBucket = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val iDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(iId)
                    val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                    out.add(MediaItem(id, uri, c.getString(iName) ?: "",
                        c.getString(iBucket) ?: "", c.getLong(iDate) * 1000L, c.getLong(iSize), true))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun galleryCounts(ctx: Context): Triple<Int, Int, Int> {
        var imgs = 0; var vids = 0
        try {
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { imgs = it.count }
            ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID), null, null, null)?.use { vids = it.count }
        } catch (_: Exception) {}
        return Triple(imgs, vids, imgs + vids)
    }

    fun openSystemGallery(ctx: Context): String = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "গ্যালারি খুলে দিয়েছি"
    } catch (_: Exception) {
        try {
            ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
            "Google Photos খুলে দিয়েছি"
        } catch (_: Exception) { "গ্যালারি খোলা যায়নি" }
    }

    /** Opens one photo/video in the system viewer. */
    fun openMedia(ctx: Context, item: MediaItem): String = try {
        val i = Intent(Intent.ACTION_VIEW).setData(item.uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (item.isVideo) i.type = "video/*" else i.type = "image/*"
        ctx.startActivity(i)
        if (item.isVideo) "ভিডিও চালিয়ে দিলাম: ${item.displayName}" else "ছবি খুলে দিলাম: ${item.displayName}"
    } catch (_: Exception) { "মিডিয়া খোলা যায়নি" }

    /** Voice answer: what's newest in the gallery. */
    fun gallerySummaryBn(ctx: Context): String {
        val (imgs, vids, all) = galleryCounts(ctx)
        if (!hasMediaPermission(ctx))
            return "গ্যালারি পড়ার অনুমতি নেই — FILES স্ক্রিনে গিয়ে মিডিয়া অনুমতি দিন"
        val latest = latestImages(ctx, 3)
        val latestLine = if (latest.isEmpty()) "" else
            " সাম্প্রতিক ছবির ফোল্ডার: " + latest.joinToString(", ") { it.bucket }
        return "গ্যালারিতে ${all}টি মিডিয়া ($imgs ছবি, $vids ভিডিও)।$latestLine"
    }
}
