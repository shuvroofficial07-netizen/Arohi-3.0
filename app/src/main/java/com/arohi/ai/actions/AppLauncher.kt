package com.arohi.ai.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

data class AppInfo(val label: String, val pkg: String)

/**
 * Real app launcher: opens any installed app, fuzzy-matches Bengali/English
 * names, and honestly routes missing apps to the Play Store.
 */
object AppLauncher {

    fun listApps(context: Context): List<AppInfo> = try {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    } catch (_: Exception) { emptyList() }

    fun findByPackageOrName(context: Context, hint: String): AppInfo? {
        if (hint.isBlank()) return null
        val apps = listApps(context)
        apps.firstOrNull { it.pkg.equals(hint, ignoreCase = true) }?.let { return it }
        val q = hint.lowercase().trim()
        apps.firstOrNull { it.pkg.lowercase().contains(q) }?.let { return it }
        apps.firstOrNull { it.label.lowercase() == q }?.let { return it }
        apps.firstOrNull { it.label.lowercase().contains(q) || q.contains(it.label.lowercase()) }?.let { return it }
        return null
    }

    /** Returns a spoken result string. Never fakes success. */
    fun open(context: Context, hint: String): String {
        val app = findByPackageOrName(context, hint)
        return if (app != null) {
            val launch = context.packageManager.getLaunchIntentForPackage(app.pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                "${app.label} খুলে দিয়েছি"
            } else {
                openPlayStore(context, app.label)
            }
        } else {
            openPlayStore(context, hint)
        }
    }

    /** App missing → real Play Store search page (installation is a user decision). */
    fun openPlayStore(context: Context, query: String): String {
        return try {
            val i = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/search?q=${Uri.encode(query)}&c=apps"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            "'$query' অ্যাপটি ইনস্টল করা নেই — প্লে স্টোরে খুলে দিয়েছি, সেখান থেকে ইনস্টল করে নিন"
        } catch (_: Exception) { "অ্যাপ খুঁজে পাইনি এবং প্লে স্টোরও খোলা যায়নি" }
    }

    fun openYouTubeSearch(context: Context, query: String): String {
        return try {
            val q = query.ifBlank { "" }
            if (q.isBlank()) {
                val launch = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(launch); "ইউটিউব খুলেছি" }
                else openPlayStore(context, "YouTube")
            } else {
                val i = Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}"))
                    .setPackage("com.google.android.youtube")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(i) } catch (_: Exception) {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "ইউটিউবে '$q' খুঁজে দিয়েছি"
            }
        } catch (_: Exception) { "ইউটিউব খোলা যায়নি" }
    }

    fun openUrl(context: Context, rawUrl: String): String {
        val url = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "ওয়েবসাইট খুলে দিয়েছি"
        } catch (_: Exception) { "ওয়েবসাইট খোলা যায়নি — লিংকটি ঠিক নয়" }
    }

    fun googleSearch(context: Context, query: String): String = try {
        val i = Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra("query", query) }
        try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        "গুগলে '$query' খুঁজে দিয়েছি"
    } catch (_: Exception) { "সার্চ করা যায়নি" }

    fun mapsSearch(context: Context, query: String): String = try {
        val geo = if (query.isBlank()) Uri.parse("geo:0,0") else Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        context.startActivity(Intent(Intent.ACTION_VIEW, geo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "ম্যাপে '$query' দেখাচ্ছি"
    } catch (_: Exception) { "ম্যাপ খোলা যায়নি" }
}
