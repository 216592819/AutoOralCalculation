package cn.tinyhai.auto_oral_calculation.ui.fragment

import android.os.Bundle
import android.preference.Preference
import android.preference.Preference.OnPreferenceClickListener
import android.preference.PreferenceFragment
import android.preference.PreferenceManager
import android.widget.Toast
import cn.tinyhai.auto_oral_calculation.BuildConfig
import cn.tinyhai.auto_oral_calculation.R
import cn.tinyhai.auto_oral_calculation.util.openGithub
import cn.tinyhai.auto_oral_calculation.util.openSettingsInHostApp

class ModuleUIFragment : PreferenceFragment(), OnPreferenceClickListener {

    private lateinit var holder: Holder

    private class Holder(manager: PreferenceManager) {
        val gotoSettings: Preference = manager.findPreference("goto_settings")!!
        val github: Preference = manager.findPreference("github")!!
        val version: Preference = manager.findPreference("version")!!
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.module_ui)
        holder = Holder(preferenceManager)
        holder.github.onPreferenceClickListener = this
        holder.gotoSettings.onPreferenceClickListener = this
        holder.version.onPreferenceClickListener = this
        holder.version.summary = BuildConfig.VERSION_NAME
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when (preference) {
            holder.gotoSettings -> {
                context.openSettingsInHostApp()
            }
            holder.version -> {
                Toast.makeText(context, ":P", Toast.LENGTH_SHORT).show()
            }
            holder.github -> {
                context.openGithub()
            }
        }
        return true
    }
}