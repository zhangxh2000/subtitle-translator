package com.zhangxh.subtitletranslator.ui.vocabulary

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.domain.vocabulary.VocabularyItemType
import com.zhangxh.subtitletranslator.ui.VocabularyFilter
import com.zhangxh.subtitletranslator.ui.VocabularySort
import com.zhangxh.subtitletranslator.ui.VocabularyUiState
import com.zhangxh.subtitletranslator.ui.VocabularyViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 个人词库
 *
 * 顶部统计/搜索/筛选，下面是连续列表；点击词条进入详情。
 * 复习状态在“学习”页维护，本页只负责展示与整理。
 */
class VocabularyFragment : Fragment() {

    private val viewModel: VocabularyViewModel by viewModels()
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptyDesc: TextView
    private lateinit var inputSearch: TextInputEditText
    private lateinit var chipGroup: ChipGroup
    private lateinit var tvListTitle: TextView
    private lateinit var btnSort: MaterialButton
    private lateinit var tvTotalCount: TextView
    private lateinit var tvWordCount: TextView
    private lateinit var tvPhraseCount: TextView

    private var isBinding = false

    private val adapter = VocabularyAdapter(
        onOpen = { word -> openDetail(word) },
        onSpeak = { word -> viewModel.speak(word) },
        onDelete = { word -> viewModel.delete(word) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_vocabulary, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recyclerView)
        emptyState = view.findViewById(R.id.emptyState)
        tvEmptyTitle = view.findViewById(R.id.tvEmptyTitle)
        tvEmptyDesc = view.findViewById(R.id.tvEmptyDesc)
        inputSearch = view.findViewById(R.id.inputSearch)
        chipGroup = view.findViewById(R.id.filterChips)
        tvListTitle = view.findViewById(R.id.tvListTitle)
        btnSort = view.findViewById(R.id.btnSort)
        tvTotalCount = view.findViewById(R.id.tvTotalCount)
        tvWordCount = view.findViewById(R.id.tvWordCount)
        tvPhraseCount = view.findViewById(R.id.tvPhraseCount)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        inputSearch.doAfterTextChanged { text ->
            if (!isBinding) viewModel.onQueryChanged(text?.toString().orEmpty())
        }
        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isBinding) return@setOnCheckedStateChangeListener
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chipWord -> VocabularyFilter.WORD
                R.id.chipPhrase -> VocabularyFilter.PHRASE
                else -> VocabularyFilter.ALL
            }
            viewModel.setFilter(filter)
        }
        btnSort.setOnClickListener { showSortMenu() }

        viewModel.uiState.observe(viewLifecycleOwner) { state -> render(state) }
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

    private fun showSortMenu() {
        PopupMenu(requireContext(), btnSort).apply {
            menu.add(0, SORT_RECENT, 0, R.string.vocabulary_sort_recent)
            menu.add(0, SORT_ALPHA, 1, R.string.vocabulary_sort_alpha)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    SORT_RECENT -> viewModel.setSort(VocabularySort.RECENT)
                    SORT_ALPHA -> viewModel.setSort(VocabularySort.ALPHABETICAL)
                }
                true
            }
            show()
        }
    }

    private fun render(state: VocabularyUiState) {
        isBinding = true
        try {
            val currentQuery = inputSearch.text?.toString().orEmpty()
            if (currentQuery != state.query) {
                inputSearch.setText(state.query)
                inputSearch.setSelection(state.query.length)
            }

            val chipId = when (state.filter) {
                VocabularyFilter.ALL -> R.id.chipAll
                VocabularyFilter.WORD -> R.id.chipWord
                VocabularyFilter.PHRASE -> R.id.chipPhrase
            }
            if (chipGroup.checkedChipId != chipId) {
                chipGroup.check(chipId)
            }

            tvListTitle.setText(
                if (state.sort == VocabularySort.RECENT) {
                    R.string.vocabulary_recent
                } else {
                    R.string.vocabulary_all
                }
            )
            tvTotalCount.text = state.total.toString()
            tvWordCount.text = state.wordCount.toString()
            tvPhraseCount.text = state.phraseCount.toString()

            adapter.setData(state.items)
            val showEmpty = state.items.isEmpty()
            emptyState.isVisible = showEmpty
            recyclerView.isVisible = !showEmpty
            if (showEmpty) {
                tvEmptyTitle.setText(
                    if (state.noData) R.string.vocabulary_empty_title
                    else R.string.vocabulary_no_results
                )
                tvEmptyDesc.setText(
                    if (state.noData) R.string.vocabulary_empty_desc
                    else R.string.vocabulary_no_results_desc
                )
            }
        } finally {
            isBinding = false
        }
    }

    private fun openDetail(word: String) {
        startActivity(
            Intent(requireContext(), WordDetailActivity::class.java)
                .putExtra(WordDetailActivity.EXTRA_WORD, word)
        )
    }

    private class VocabularyAdapter(
        private val onOpen: (String) -> Unit,
        private val onSpeak: (String) -> Unit,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<VocabularyAdapter.ViewHolder>() {

        private var items = listOf<SavedWord>()

        fun setData(data: List<SavedWord>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vocabulary, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvWord: TextView = itemView.findViewById(R.id.tvWord)
            private val tvPhonetic: TextView = itemView.findViewById(R.id.tvPhonetic)
            private val tvType: TextView = itemView.findViewById(R.id.tvType)
            private val tvMeanings: TextView = itemView.findViewById(R.id.tvMeanings)
            private val tvContext: TextView = itemView.findViewById(R.id.tvContext)
            private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
            private val btnMore: ImageButton = itemView.findViewById(R.id.btnMore)

            fun bind(item: SavedWord) {
                val context = itemView.context
                tvWord.text = item.word
                tvPhonetic.text = item.phonetic
                tvPhonetic.isVisible = item.phonetic.isNotBlank()
                tvType.isVisible = item.itemType == VocabularyItemType.PHRASE

                val meanings = item.meanings.mapNotNull { meaning ->
                    meaning.chineseDefinition.takeIf { it.isNotBlank() }
                }
                tvMeanings.text = if (meanings.isEmpty()) {
                    item.meanings.firstOrNull()?.definition.orEmpty()
                } else {
                    meanings.take(2).joinToString("；")
                }
                tvMeanings.isVisible = tvMeanings.text.isNotBlank()

                val source = item.context?.source?.takeIf { it.isNotBlank() }
                val translation = item.context?.translation?.takeIf { it.isNotBlank() }
                tvContext.text = when {
                    source != null -> "“$source”"
                    translation != null -> "“$translation”"
                    else -> ""
                }
                tvContext.isVisible = tvContext.text.isNotBlank()

                tvMeta.text = context.getString(
                    R.string.vocabulary_meta,
                    formatDate(context, item.createdAt),
                    context.getString(R.string.vocabulary_source_subtitle)
                )

                itemView.setOnClickListener { onOpen(item.word) }
                btnMore.setOnClickListener {
                    PopupMenu(context, btnMore).apply {
                        menu.add(0, ACTION_SPEAK, 0, R.string.action_pronounce)
                        menu.add(0, ACTION_DELETE, 1, R.string.action_delete)
                        setOnMenuItemClickListener { menuItem ->
                            when (menuItem.itemId) {
                                ACTION_SPEAK -> onSpeak(item.word)
                                ACTION_DELETE -> onDelete(item.word)
                            }
                            true
                        }
                        show()
                    }
                }
            }
        }
    }

    private companion object {
        const val SORT_RECENT = 1
        const val SORT_ALPHA = 2
        const val ACTION_SPEAK = 1
        const val ACTION_DELETE = 2

        fun formatDate(context: android.content.Context, timestamp: Long): String {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply { timeInMillis = timestamp }
            val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
            if (sameDay) return context.getString(R.string.vocabulary_date_today)
            val yesterday = now.clone() as Calendar
            yesterday.add(Calendar.DAY_OF_YEAR, -1)
            val isYesterday = yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
            if (isYesterday) return context.getString(R.string.vocabulary_date_yesterday)
            return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
