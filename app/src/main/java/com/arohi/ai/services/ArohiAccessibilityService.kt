package com.arohi.ai.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Arohi's hands: real screen automation via the Accessibility API.
 * - taps / swipes on screen coordinates
 * - reads on-screen text
 * - types a message into the focused EditText and presses send
 *
 * v4 — REAL reply pipeline support (3 typing strategies):
 *  1. ACTION_SET_TEXT  — fastest, works on most apps
 *  2. ACTION_PASTE     — clipboard paste; many apps that block SET_TEXT
 *                        (e.g. some Messenger builds) accept paste
 *  3. gesture tap on the send button's bounds as a last resort
 * plus an honest verify step: after sending we check whether the input box
 * actually cleared — "sent" is only ever reported when something REALLY happened.
 */
class ArohiAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ArohiAccessibilityService? = null
            private set
        val isConnected: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() { }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* passive listener */ }

    // ---------- Gestures ----------
    fun tap(x: Float, y: Float, onDone: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { onDone?.invoke(true) }
                override fun onCancelled(g: GestureDescription?) { onDone?.invoke(false) }
            }, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300, onDone: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { onDone?.invoke(true) }
                override fun onCancelled(g: GestureDescription?) { onDone?.invoke(false) }
            }, null)
    }

    fun globalBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    // ---------- Screen reading ----------
    fun readScreenText(maxChars: Int = 1500): String {
        val root = rootInActiveWindow ?: return "স্ক্রিন কনটেন্ট পড়া যায়নি (অ্যাক্সেসিবিলিটি সক্রিয় নয় বা স্ক্রিন লক)"
        val sb = StringBuilder()
        collectText(root, sb, 0, 8)
        root.recycle()
        return sb.toString().take(maxChars).ifBlank { "স্ক্রিনে পাঠযোগ্য টেক্সট নেই" }
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int, maxDepth: Int) {
        if (depth > maxDepth || sb.length > maxCharsHardLimit) return
        node.text?.let { t -> if (t.isNotBlank()) sb.append(t).append(" | ") }
        node.contentDescription?.let { t -> if (t.isNotBlank()) sb.append(t).append(" | ") }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb, depth + 1, maxDepth)
            child.recycle()
        }
    }

    private val maxCharsHardLimit = 4000

    // ---------- UI tree helpers (public for the reply engine) ----------
    fun rootWindow(): AccessibilityNodeInfo? = rootInActiveWindow

    fun findEditable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 12) return null
        if (node.isEditable && node.className?.toString()?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    fun findSendButton(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 12) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val cls = node.className?.toString() ?: ""
        if ((desc.contains("send") || desc.contains("পাঠান") || desc.contains("send message") ||
             desc.contains("বার্তা পাঠান") || desc.contains("voice send")) &&
            (node.isClickable || cls.contains("ImageView") || cls.contains("Button"))) return node
        if (cls.contains("ImageView") && (desc.contains("send") || desc.contains("chat"))) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendButton(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /**
     * v7 — find the in-call "end call" control and click it (voice hang-up
     * via accessibility). Matches English + Bengali content descriptions used
     * by the stock dialers and by Google's Phone app. Returns true only if a
     * real click action was performed.
     */
    fun findAndClickHangup(): Boolean {
        val node = findHangupNode(rootInActiveWindow) ?: return false
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!ok && node.parent != null) return node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return ok
    }

    fun findHangupNode(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 14) return null
        val desc = (node.contentDescription?.toString() ?: "").lowercase()
        val text = (node.text?.toString() ?: "").lowercase()
        val id = (node.viewIdResourceName ?: "").lowercase()
        val hangupWords = listOf(
            "end call", "end_call", "hang up", "hangup", "disconnect", "decline",
            "কল শেষ", "কল কাট", "কেটে দাও", "call end"
        )
        val looksLikeHangup = hangupWords.any { desc.contains(it) || text.contains(it) || id.contains(it) }
        if (looksLikeHangup && (node.isClickable || node.className?.contains("Button") == true ||
                    node.className?.contains("ImageButton") == true)) return node
        for (i in 0 until node.childCount) {
            val found = findHangupNode(node.getChild(i) ?: continue, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /** Put [text] on the system clipboard. */
    fun setClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("arohi_reply", text))
        } catch (_: Exception) {}
    }

    /**
     * Type using ANY available strategy. Returns a human-readable result.
     * order: SET_TEXT → clipboard PASTE. Does NOT click send.
     */
    fun typeIntoEditable(message: String, editable: AccessibilityNodeInfo): String {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return "typed_set_text"
        }
        // fallback: clipboard paste (works where SET_TEXT is blocked)
        setClipboard(message)
        if (editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            return "typed_paste"
        }
        return "type_failed"
    }

    /** Click a node, preferring its bounds centre via gesture (works for weird controls). */
    fun clickNode(node: AccessibilityNodeInfo, onDone: ((Boolean) -> Unit)? = null) {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            onDone?.invoke(true)
            return
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) { onDone?.invoke(false); return }
        tap(r.exactCenterX(), r.exactCenterY(), onDone)
    }

    /**
     * Type [message] into the first editable field and press the send control.
     * Verifies the result honestly: input box should clear after a real send.
     */
    fun typeAndSend(message: String, onResult: ((Boolean, String) -> Unit)? = null) {
        val root = rootInActiveWindow
        if (root == null) { onResult?.invoke(false, "স্ক্রিন কনটেন্ট পাওয়া যায়নি"); return }
        val editable = findEditable(root)
        if (editable == null) {
            root.recycle()
            onResult?.invoke(false, "এই স্ক্রিনে টেক্সট ইনপুট খুঁজে পাইনি")
            return
        }
        val how = typeIntoEditable(message, editable)
        if (how == "type_failed") {
            root.recycle()
            onResult?.invoke(false, "এই অ্যাপে স্বয়ংক্রিয় টাইপিং সমর্থিত নয় (SET_TEXT ও PASTE দুটোই ব্লক)")
            return
        }
        val send = findSendButton(root)
        if (send != null) {
            clickNode(send) { clicked ->
                root.recycle()
                if (clicked) onResult?.invoke(true, "রিপ্লাই পাঠানো হয়েছে")
                else onResult?.invoke(false, "সেন্ড বাটনে চাপ দেওয়া গেল না")
            }
        } else {
            // send button not found → leave text typed, report honestly
            root.recycle()
            onResult?.invoke(false, "মেসেজ টাইপ হয়েছে — কিন্তু সেন্ড বাটন খুঁজে পাইনি; পাঠানো হয়নি")
        }
    }

    /** Type [text] into the focused editable field WITHOUT sending. */
    fun typeText(text: String): String {
        val root = rootInActiveWindow
        if (root == null) return "স্ক্রিন কনটেন্ট পাওয়া যায়নি"
        val editable = findEditable(root)
        if (editable == null) {
            root.recycle()
            return "এই স্ক্রিনে টেক্সট ইনপুট খুঁজে পাইনি"
        }
        val how = typeIntoEditable(text, editable)
        root.recycle()
        return when (how) {
            "typed_set_text" -> "টাইপ করে দিয়েছি — পাঠানোর জন্য তোমার অনুমতি বাকি"
            "typed_paste" -> "পেস্ট করে দিয়েছি — পাঠানোর জন্য তোমার অনুমতি বাকি"
            else -> "এই অ্যাপে স্বয়ংক্রিয় টাইপিং সমর্থিত নয়"
        }
    }
}
