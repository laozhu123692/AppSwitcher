package com.tester.appswitcher

import android.app.Activity
import android.widget.SimpleAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.tester.appswitcher.databinding.DialogAppPickerBinding

/**
 * 应用选择弹窗：列出所有可从桌面启动的已安装 App，支持按名称或包名筛选。
 */
object AppPickerDialog {

    private const val KEY_LABEL = "label"
    private const val KEY_PKG = "pkg"

    fun show(activity: Activity, onPicked: (AppInfo) -> Unit) {
        val binding = DialogAppPickerBinding.inflate(activity.layoutInflater)
        val all = AppLoader.load(activity)
        var shown: List<AppInfo> = all

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.pick_app_title)
            .setView(binding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        fun bind(list: List<AppInfo>) {
            shown = list
            binding.listApps.adapter = SimpleAdapter(
                activity,
                list.map { mapOf(KEY_LABEL to it.label, KEY_PKG to it.packageName) },
                android.R.layout.simple_list_item_2,
                arrayOf(KEY_LABEL, KEY_PKG),
                intArrayOf(android.R.id.text1, android.R.id.text2)
            )
            binding.tvEmpty.visibility =
                if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }

        bind(all)

        binding.listApps.setOnItemClickListener { _, _, position, _ ->
            val picked = shown.getOrNull(position)
            if (picked != null) {
                onPicked(picked)
            }
            dialog.dismiss()
        }

        binding.etSearch.doAfterTextChanged { text ->
            val keyword = text?.toString()?.trim().orEmpty()
            bind(
                if (keyword.isEmpty()) {
                    all
                } else {
                    all.filter {
                        it.label.contains(keyword, ignoreCase = true) ||
                            it.packageName.contains(keyword, ignoreCase = true)
                    }
                }
            )
        }

        dialog.show()
    }
}
