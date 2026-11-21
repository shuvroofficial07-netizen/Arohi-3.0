package com.arohi.ai.actions

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract

/**
 * Contacts, calls, SMS & email drafts.
 * NOTE (honest platform limits): Android does not allow silent SMS sending to
 * third-party messengers — we open real drafts the user confirms/taps send on.
 */
object PeopleHelper {

    data class Contact(val name: String, var phone: String)

    fun findContact(context: Context, nameQuery: String): Contact? {
        if (nameQuery.isBlank()) return null
        try {
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$nameQuery%"),
                    null)
                val idxName = cursor?.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val idxPhone = cursor?.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (cursor != null && cursor.moveToFirst() && idxName != null && idxPhone != null) {
                    return Contact(cursor.getString(idxName), cursor.getString(idxPhone) ?: "")
                }
            } finally { cursor?.close() }
        } catch (_: SecurityException) { return null }
        return null
    }

    fun call(context: Context, nameOrNumber: String): String {
        val number = if (nameOrNumber.any { it.isDigit() }) nameOrNumber
            else findContact(context, nameOrNumber)?.phone ?: return "'$nameOrNumber' নামে কন্টাক্ট পাইনি (অথবা কন্টাক্ট পারমিশন নেই)"
        return try {
            context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "কল করছি $nameOrNumber"
        } catch (_: SecurityException) { "কল করার CALL_PHONE পারমিশন নেই — সেটিংস থেকে চালু করুন" }
        catch (_: Exception) { "কল শুরু করা যায়নি" }
    }

    fun openDialer(context: Context): String = try {
        context.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "ডায়ালার খুলে দিয়েছি"
    } catch (_: Exception) { "ডায়ালার খোলা যায়নি" }

    fun openContacts(context: Context): String = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "কন্টাক্টস খুলে দিয়েছি"
    } catch (_: Exception) { "কন্টাক্টস খোলা যায়নি" }

    /** SMS draft (user taps send — silent SMS is not allowed by Android). */
    fun smsDraft(context: Context, recipient: String, message: String): String {
        val number = if (recipient.any { it.isDigit() }) recipient
            else findContact(context, recipient)?.phone ?: return "'$recipient' পাইনি — নাম বা নাম্বার দিন"
        return try {
            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "'$number'-এ মেসেজ ড্রাফট করেছি — পাঠানোর আগে আপনার অনুমোদন লাগবে"
        } catch (_: Exception) { "মেসেজ ড্রাফট খোলা যায়নি" }
    }

    /** WhatsApp / any messaging app deep-link draft when package available. */
    fun whatsappDraft(context: Context, recipient: String, message: String): String {
        val number = recipient.filter { it.isDigit() }
        if (number.isBlank()) return smsDraft(context, recipient, message)
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://wa.me/$number?text=${Uri.encode(message)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "হোয়াটসঅ্যাপে মেসেজ প্রস্তুত — সেন্ড চাপলে পাঠাবে"
        } catch (_: Exception) { "হোয়াটসঅ্যাপ খোলা যায়নি — সাধারণ SMS ড্রাফট বানিয়ে দিচ্ছি" }
    }

    fun emailDraft(context: Context, to: String, subject: String, body: String): String {
        return try {
            context.startActivity(Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "ইমেইল ড্রাফট খুলে দিয়েছি — পাঠানোর আগে আপনার অনুমোদন লাগবে"
        } catch (_: Exception) { "ইমেইল ড্রাফট খোলা যায়নি" }
    }
}
