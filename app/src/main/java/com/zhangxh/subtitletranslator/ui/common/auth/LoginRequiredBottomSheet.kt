package com.zhangxh.subtitletranslator.ui.common.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.zhangxh.subtitletranslator.R

/**
 * 登录引导 BottomSheet
 *
 * 比 AlertDialog 更贴合移动端上下文；主操作突出，次操作轻量。
 */
class LoginRequiredBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_FEATURE = "feature"

        fun newInstance(feature: LoginFeature): LoginRequiredBottomSheet =
            LoginRequiredBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_FEATURE, feature.name) }
            }
    }

    var onLogin: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private var actionTaken = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_login_required, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val feature = arguments?.getString(ARG_FEATURE)
            ?.let { runCatching { LoginFeature.valueOf(it) }.getOrNull() }
            ?: LoginFeature.GENERIC

        view.findViewById<TextView>(R.id.tvSheetTitle).setText(feature.titleRes)
        view.findViewById<TextView>(R.id.tvSheetMessage).setText(feature.messageRes)
        view.findViewById<MaterialButton>(R.id.btnSheetLogin).setOnClickListener {
            actionTaken = true
            dismiss()
            onLogin?.invoke()
        }
        view.findViewById<MaterialButton>(R.id.btnSheetLater).setOnClickListener {
            actionTaken = true
            dismiss()
            onCancel?.invoke()
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (!actionTaken) onCancel?.invoke()
    }
}
