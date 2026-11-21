package com.arohi.ai.core

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionHelper {

    fun has(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun hasMicrophone(ctx: Context) = has(ctx, Manifest.permission.RECORD_AUDIO)
    fun hasCamera(ctx: Context) = has(ctx, Manifest.permission.CAMERA)
    fun hasContacts(ctx: Context) = has(ctx, Manifest.permission.READ_CONTACTS)
    fun hasPhone(ctx: Context) = has(ctx, Manifest.permission.CALL_PHONE)

    fun hasNotifications(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || has(ctx, Manifest.permission.POST_NOTIFICATIONS)

    fun request(activity: Activity, perms: Array<String>, code: Int) {
        val missing = perms.filter { !has(activity, it) }.toTypedArray()
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(activity, missing, code)
    }

    /** Human-readable list for the privacy dashboard — no fake green ticks. */
    fun statusLines(ctx: Context): List<Pair<String, Boolean>> = listOf(
        "মাইক্রোফোন (ভয়েস ইনপুট)" to hasMicrophone(ctx),
        "ক্যামেরা (ভিশন)" to hasCamera(ctx),
        "নোটিফিকেশন দেখানো" to hasNotifications(ctx),
        "কন্টাক্ট (কল/মেসেজ ড্রাফট)" to hasContacts(ctx),
        "ফোন কল" to hasPhone(ctx),
        "ব্রাইটনেস নিয়ন্ত্রণ" to android.provider.Settings.System.canWrite(ctx),
        "নোটিফিকেশন লিসেনার (পড়া ও ঘোষণা)" to isNotificationListenerEnabled(ctx),
        "অ্যাক্সেসিবিলিটি (স্ক্রিন অটোমেশন)" to isAccessibilityEnabled(ctx)
    )

    fun isNotificationListenerEnabled(ctx: Context): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it.contains(ctx.packageName) }
    }

    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.split(":").any { it.contains(ctx.packageName) }
    }
}
