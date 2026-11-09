package com.zhangxh.subtitletranslator.ui.vocabulary

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.zhangxh.subtitletranslator.R
import com.zhangxh.subtitletranslator.SubtitleTranslatorApplication
import com.zhangxh.subtitletranslator.domain.vocabulary.SavedWord
import com.zhangxh.subtitletranslator.domain.wordextractor.WordDifficulty
import com.zhangxh.subtitletranslator.ui.VocabularyViewModel
import com.zhangxh.subtitletranslator.ui.common.auth.LoginGate
import com.zhangxh.subtitletranslator.ui.common.labelRes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 词汇 Tab
 *
 * 列表对游客可见；游客顶部显示登录引导，收藏动作由悬浮窗统一拦截。
 */
class VocabularyFragment : Fragment() {

    private val viewModel: VocabularyViewModel by viewModels()
    private lateinit var loginGate: LoginGate
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var loginBanner: View
    private val adapter = VocabularyAdapter(
        onSpeak = { word -> viewModel.speak(word) },
        onDelete = { word -> viewModel.delete(word) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (requireActivity().application as SubtitleTranslatorApplication).container
        loginGate = LoginGate(
            fragment = this,
            authManager = container.sessionManager,
            coordinator = container.loginPromptCoordinator
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_vocabulary, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recyclerView)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        loginBanner = view.findViewById(R.id.loginBanner)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<MaterialToolbar>(R.id.toolbar).apply {
            inflateMenu(R.menu.vocabulary_menu)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_clear) {
                    viewModel.clear()
                    true
                } else {
                    false
                }
            }
        }
        view.findViewById<View>(R.id.btnBannerLogin).setOnClickListener {
            loginGate.launchLoginDirectly()
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            adapter.setData(state.items)
            tvEmpty.isVisible = state.isEmpty
            recyclerView.isVisible = !state.isEmpty
        }
        viewModel.isLoggedIn.observe(viewLifecycleOwner) { loggedIn ->
            loginBanner.isVisible = !loggedIn
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

    private class VocabularyAdapter(
        private val onSpeak: (String) -> Unit,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<VocabularyAdapter.ViewHolder>() {

        private var items = listOf<SavedWord>()
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

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
            private val tvDifficulty: TextView = itemView.findViewById(R.id.tvDifficulty)
            private val tvWord: TextView = itemView.findViewById(R.id.tvWord)
            private val tvPhonetic: TextView = itemView.findViewById(R.id.tvPhonetic)
            private val tvMeanings: TextView = itemView.findViewById(R.id.tvMeanings)
            private val tvAddedAt: TextView = itemView.findViewById(R.id.tvAddedAt)
            private val btnPronounce: ImageButton = itemView.findViewById(R.id.btnPronounce)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

            fun bind(item: SavedWord) {
                tvWord.text = item.word
                tvPhonetic.text = item.phonetic

                val meaningsText = SpannableStringBuilder()
                item.meanings.forEachIndexed { index, meaning ->
                    if (index > 0) meaningsText.append("\n")
                    if (meaning.partOfSpeech.isNotEmpty()) {
                        val start = meaningsText.length
                        meaningsText.append("[${meaning.partOfSpeech}] ")
                        meaningsText.setSpan(
                            ForegroundColorSpan(
                                tvMeanings.context.getColor(R.color.accent)
                            ),
                            start,
                            meaningsText.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    meaningsText.append(meaning.chineseDefinition)
                }
                tvMeanings.text = meaningsText
                tvMeanings.visibility = if (meaningsText.isEmpty()) View.GONE else View.VISIBLE

                val (backgroundRes, textRes) = when (item.difficulty) {
                    WordDifficulty.HARD ->
                        R.color.difficulty_hard_bg to R.color.difficulty_hard_text
                    WordDifficulty.MEDIUM ->
                        R.color.difficulty_medium_bg to R.color.difficulty_medium_text
                    else ->
                        R.color.difficulty_easy_bg to R.color.difficulty_easy_text
                }
                tvDifficulty.text = tvDifficulty.context.getString(item.difficulty.labelRes())
                tvDifficulty.backgroundTintList = ColorStateList.valueOf(
                    tvDifficulty.context.getColor(backgroundRes)
                )
                tvDifficulty.setTextColor(tvDifficulty.context.getColor(textRes))

                tvAddedAt.text = tvAddedAt.context.getString(
                    R.string.vocabulary_added_at,
                    dateFormat.format(Date(item.createdAt))
                )

                btnPronounce.setOnClickListener { onSpeak(item.word) }
                btnDelete.setOnClickListener { onDelete(item.word) }
            }
        }
    }
}
