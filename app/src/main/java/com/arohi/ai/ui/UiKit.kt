package com.arohi.ai.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.arohi.ai.R

/**
 * v4 — shared builder kit for the new v4 screens (API CENTER, SYSTEM HEALTH,
 * AUTOMATION, NOTIFICATIONS, MESSAGES, SUPPORT, VISION). Glassmorphism cards
 * on the AMOLED theme — one visual language across the whole app.
 */
object UiKit {

    fun scrollRoot(ctx: Context): ScrollView = ScrollView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isFillViewport = true
    }

    fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 32, 40, 96)
    }

    fun section(ctx: Context, title: String): TextView = TextView(ctx).apply {
        text = title; textSize = 16f
        setTextColor(ContextCompat.getColor(ctx, R.color.arohi_cyan))
        setPadding(8, 28, 8, 8)
    }

    fun card(ctx: Context, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_glass)
        setPadding(24, 20, 24, 20)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, 8, 0, 8) }
        views.forEach { addView(it) }
    }

    fun row(ctx: Context, vararg views: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        views.forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(4, 4, 4, 4) }
            addView(it)
        }
    }

    fun button(ctx: Context, label: String, onClick: () -> Unit): com.google.android.material.button.MaterialButton =
        com.google.android.material.button.MaterialButton(ctx).apply {
            text = label; isAllCaps = false
            textSize = 13f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 6, 0, 6) }
        }

    fun field(ctx: Context, hint: String, value: String, password: Boolean = false): com.google.android.material.textfield.TextInputLayout {
        return com.google.android.material.textfield.TextInputLayout(ctx).apply {
            this.hint = hint
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 6, 0, 6) }
            // v6: eye toggle on password fields (screenshot: API Key field with eye)
            if (password) endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
            val et = EditText(ctx).apply {
                setText(value)
                textSize = 14f
                if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            addView(et)
        }
    }

    /** v6: gradient-background button (Save API Key / Test Connection styles). */
    fun gradientButton(ctx: Context, label: String, bgRes: Int, onClick: () -> Unit): android.widget.Button {
        val b = android.widget.Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.textSize = 14f
        b.typeface = android.graphics.Typeface.DEFAULT_BOLD
        b.setTextColor(android.graphics.Color.WHITE)
        b.background = ContextCompat.getDrawable(ctx, bgRes)
        b.setOnClickListener { onClick() }
        b.setPadding(24, 22, 24, 22)
        b.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, 8, 0, 8) }
        return b
    }

    /** v6: rounded banner (success green / error red) for real connection status. */
    fun banner(ctx: Context, ok: Boolean, title: String, sub: String): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(ctx, if (ok) R.drawable.bg_banner_success else R.drawable.bg_banner_error)
            setPadding(28, 20, 28, 20)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 10, 0, 10) }
            val icon = TextView(ctx).apply {
                text = if (ok) "✓" else "✗"
                textSize = 20f
                setTextColor(ContextCompat.getColor(ctx, if (ok) R.color.arohi_success else R.color.arohi_error))
                setPadding(0, 0, 22, 0)
            }
            val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(ctx).apply {
                text = title; textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(ctx, if (ok) R.color.arohi_success else R.color.arohi_error))
            })
            box.addView(TextView(ctx).apply {
                text = sub; textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.arohi_text_dim))
            })
            addView(icon)
            addView(box)
        }
    }

    /** v6: provider tab chip — selected = blue gradient, unselected = ghost. */
    fun tabChip(ctx: Context, label: String, selected: Boolean, onClick: () -> Unit): android.widget.Button {
        val b = android.widget.Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.textSize = 14f
        b.typeface = android.graphics.Typeface.DEFAULT_BOLD
        b.setTextColor(if (selected) android.graphics.Color.WHITE
            else ContextCompat.getColor(ctx, R.color.arohi_text_dim))
        b.background = ContextCompat.getDrawable(ctx, if (selected) R.drawable.bg_tab_on else R.drawable.bg_tab_off)
        b.setOnClickListener { onClick() }
        b.setPadding(12, 20, 12, 20)
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { setMargins(4, 0, 4, 0) }
        return b
    }

    fun statusLine(ctx: Context): TextView = TextView(ctx).apply {
        textSize = 13f
        setPadding(0, 8, 0, 8)
        setTextColor(ContextCompat.getColor(ctx, R.color.arohi_text_dim))
    }

    fun note(ctx: Context, text: String, warn: Boolean = false): TextView = TextView(ctx).apply {
        this.text = text; textSize = 12f
        setTextColor(ContextCompat.getColor(ctx, if (warn) R.color.arohi_warn else R.color.arohi_text_dim))
        setPadding(0, 4, 0, 4)
    }

    fun switch(ctx: Context, label: String, checked: Boolean, on: (Boolean) -> Unit): com.google.android.material.materialswitch.MaterialSwitch =
        com.google.android.material.materialswitch.MaterialSwitch(ctx).apply {
            text = label; isChecked = checked; textSize = 14f
            setOnCheckedChangeListener { _, v -> on(v) }
        }
}
