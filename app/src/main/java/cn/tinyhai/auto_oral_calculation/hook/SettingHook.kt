package cn.tinyhai.auto_oral_calculation.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.HOST_PACKAGE_NAME
import cn.tinyhai.auto_oral_calculation.KEY_START_SETTINGS
import cn.tinyhai.auto_oral_calculation.ui.SettingsDialog

class SettingHook : BaseHook() {

    private var shouldStartSettings = false

    override fun startHook() {
        hookSettingActivity()
        hookRouterActivity()
        hookHomeActivity()
    }

    private fun hookRouterActivity() {
        val routerActivityClass = findClass(Classname.ROUTER_ACTIVITY)
        routerActivityClass.findMethod("onCreate", Bundle::class.java).before { param ->
            val activity = param.thisObject as Activity
            val intent = activity.intent ?: return@before
            shouldStartSettings = intent.getBooleanExtra(KEY_START_SETTINGS, false)
        }
    }

    private fun hookHomeActivity() {
        val homeActivityClass = findClass(Classname.HOME_ACTIVITY)
        homeActivityClass.findMethod("onResume").after { param ->
            if (shouldStartSettings) {
                val context = param.thisObject as Context
                val intent = Intent().apply {
                    component = ComponentName(HOST_PACKAGE_NAME, Classname.SETTINGS_ACTIVITY)
                }
                context.startActivity(intent)
            }
        }
    }

    private fun hookSettingActivity() {
        val settingsActivityClass = findClass(Classname.SETTINGS_ACTIVITY)
        val sectionItemClass = findClass(Classname.SECTION_ITEM)
        val sectionItemConstructor = sectionItemClass.getConstructor(Context::class.java)
        settingsActivityClass.findMethod("onCreate", Bundle::class.java).after { param ->
            val activity = param.thisObject as Activity
            val appWidgetId = activity.resources.getIdentifier(
                "cell_appwidget",
                "id",
                activity.packageName
            )
            val appWidget = activity.findViewById<View>(appWidgetId)
            val container = appWidget.parent as LinearLayout
            val item = sectionItemConstructor.newInstance(activity) as ViewGroup
            val labelId =
                activity.resources.getIdentifier("text_label", "id", activity.packageName)
            val label = item.findViewById<TextView>(labelId)
            label.text = "口算糕手设置"
            item.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            item.setOnClickListener {
                SettingsDialog(activity).show()
            }

            container.addView(item, 0)
        }

        settingsActivityClass.findMethod("onResume").after { param ->
            if (shouldStartSettings) {
                shouldStartSettings = false
                SettingsDialog(param.thisObject as Context).show()
            }
        }
    }
}