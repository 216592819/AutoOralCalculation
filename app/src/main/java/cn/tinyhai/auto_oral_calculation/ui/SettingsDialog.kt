package cn.tinyhai.auto_oral_calculation.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import cn.tinyhai.auto_oral_calculation.XposedInit
import cn.tinyhai.auto_oral_calculation.ui.fragment.SettingsFragment
import de.robv.android.xposed.XposedHelpers

class SettingsDialog(context: Context) : AlertDialog.Builder(context) {
    init {
        val activity = context as Activity
        XposedHelpers.callMethod(activity.assets, "addAssetPath", XposedInit.modulePath)
        val fragment = SettingsFragment()
        activity.fragmentManager.beginTransaction().add(fragment, "Settings").commit()
        activity.fragmentManager.executePendingTransactions()
        val contentView = LinearLayout(fragment.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        contentView.addView(fragment.view)
        setView(contentView)
        setTitle("口算糕手设置")
        setNegativeButton("确定", null)
    }
}