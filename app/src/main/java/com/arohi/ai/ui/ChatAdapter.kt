package com.arohi.ai.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.arohi.ai.R
import com.arohi.ai.memory.ChatMessage
import com.arohi.ai.util.Extensions

/**
 * Chat log adapter — user / Arohi / system announcement bubbles.
 */
class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val items = mutableListOf<ChatMessage>()

    fun submit(list: List<ChatMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.chat_name)
        val text: TextView = v.findViewById(R.id.chat_text)
        val time: TextView = v.findViewById(R.id.chat_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        val ctx = holder.itemView.context
        when (msg.role) {
            "user" -> {
                holder.name.text = "শুভ্র"
                holder.itemView.findViewById<View>(R.id.chat_bubble)
                    .setBackgroundResource(R.drawable.bg_chat_user)
                holder.name.setTextColor(ctx.getColor(R.color.arohi_neon))
            }
            "arohi" -> {
                holder.name.text = "আরোহী • ${msg.emotion.take(7)}"
                holder.itemView.findViewById<View>(R.id.chat_bubble)
                    .setBackgroundResource(R.drawable.bg_chat_arohi)
                holder.name.setTextColor(ctx.getColor(R.color.arohi_cyan))
            }
            else -> {
                holder.name.text = "সিস্টেম"
                holder.itemView.findViewById<View>(R.id.chat_bubble)
                    .setBackgroundResource(R.drawable.bg_pill)
                holder.name.setTextColor(ctx.getColor(R.color.arohi_text_dim))
            }
        }
        holder.text.text = msg.text
        holder.time.text = Extensions.dateTime(msg.timestamp)
    }
}
