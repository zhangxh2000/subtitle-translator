package com.zhangxh.subtitletranslator.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.TranslationResult
import com.zhangxh.subtitletranslator.ui.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 翻译历史 Tab */
class HistoryFragment : Fragment() {

    private val viewModel: HistoryViewModel by viewModels()
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private val adapter = HistoryAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_history, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recyclerView)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<MaterialToolbar>(R.id.toolbar).apply {
            inflateMenu(R.menu.history_menu)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_clear) {
                    viewModel.clear()
                    true
                } else {
                    false
                }
            }
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            adapter.setData(state.items)
            tvEmpty.isVisible = state.isEmpty
            recyclerView.isVisible = !state.isEmpty
        }
        viewModel.messages.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(requireContext(), message.resolve(requireContext()), Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        private var items = listOf<TranslationResult>()
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        fun setData(data: List<TranslationResult>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
            private val tvOriginal: TextView = itemView.findViewById(R.id.tvOriginal)
            private val tvTranslated: TextView = itemView.findViewById(R.id.tvTranslated)

            fun bind(item: TranslationResult) {
                tvTime.text = dateFormat.format(Date(item.timestamp))
                tvOriginal.text = item.originalText
                tvTranslated.text = item.translatedText
            }
        }
    }
}
