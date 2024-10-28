package cn.tinyhai.auto_oral_calculation.ui.fragment

import android.os.Bundle
import android.preference.EditTextPreference
import android.preference.ListPreference
import android.preference.Preference
import android.preference.Preference.OnPreferenceClickListener
import android.preference.PreferenceFragment
import android.preference.PreferenceManager
import android.preference.SwitchPreference
import cn.tinyhai.auto_oral_calculation.BuildConfig
import cn.tinyhai.auto_oral_calculation.PREFS_NAME
import cn.tinyhai.auto_oral_calculation.R
import cn.tinyhai.auto_oral_calculation.entities.AutoAnswerMode
import cn.tinyhai.auto_oral_calculation.util.openGithub

class SettingsFragment : PreferenceFragment(), OnPreferenceClickListener {

    private class Holder(manager: PreferenceManager) {
        val alwaysTrue: SwitchPreference =
            manager.findPreference("always_true_answer") as SwitchPreference
        val autoPractice: SwitchPreference =
            manager.findPreference("auto_practice") as SwitchPreference
        val autoPracticeQuick: SwitchPreference =
            manager.findPreference("auto_practice_quick") as SwitchPreference
        val autoPracticeCyclic: SwitchPreference =
            manager.findPreference("auto_practice_cyclic") as SwitchPreference
        val autoPracticeLoopInterval: EditTextPreference =
            manager.findPreference("auto_practice_cyclic_interval") as EditTextPreference
        val autoAnswerConfig: ListPreference =
            manager.findPreference("auto_answer_config") as ListPreference
        val customAnswerConfig: EditTextPreference =
            manager.findPreference("custom_answer_config") as EditTextPreference
        val quickModeInterval: EditTextPreference =
            manager.findPreference("quick_mode_interval") as EditTextPreference
        val github: Preference = manager.findPreference("github")!!
        val version: Preference = manager.findPreference("version")!!
    }

    private lateinit var holder: Holder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferencesName = PREFS_NAME
        addPreferencesFromResource(R.xml.host_settings)
        holder = Holder(preferenceManager)
        initPreference()
    }

    private fun initPreference() {
        holder.version.setSummary(BuildConfig.VERSION_NAME)
        holder.autoAnswerConfig.let {
            var index = kotlin.runCatching { Integer.parseInt(it.value) }.getOrElse { 0 }
            it.summary = "当前选择: ${AutoAnswerMode.entries[index].value}"
            var mode = AutoAnswerMode.entries[index]
            holder.customAnswerConfig.isEnabled = mode == AutoAnswerMode.CUSTOM
            holder.quickModeInterval.isEnabled = mode == AutoAnswerMode.QUICK

            it.setOnPreferenceChangeListener { _, newValue ->
                index = kotlin.runCatching { Integer.parseInt(newValue.toString()) }.getOrElse { 0 }
                mode = AutoAnswerMode.entries[index]
                holder.customAnswerConfig.isEnabled = mode == AutoAnswerMode.CUSTOM
                holder.quickModeInterval.isEnabled = mode == AutoAnswerMode.QUICK
                holder.autoAnswerConfig.summary = "当前选择: ${mode.value}"
                true
            }
        }
        holder.autoPracticeLoopInterval.let {
            var interval = kotlin.runCatching { Integer.parseInt(it.text) }.getOrElse { 1500 }
            it.summary = "当前间隔: $interval 毫秒"
            it.setOnPreferenceChangeListener { _, newValue ->
                interval =
                    kotlin.runCatching { Integer.parseInt(newValue.toString()) }.getOrElse { 1500 }
                it.summary = "当前间隔: $interval 毫秒"
                true
            }
        }
        holder.quickModeInterval.let {
            var interval = kotlin.runCatching { Integer.parseInt(it.text) }.getOrElse { 200 }
            it.summary = "当前间隔: $interval 毫秒"
            it.setOnPreferenceChangeListener { _, newValue ->
                interval =
                    kotlin.runCatching { Integer.parseInt(newValue.toString()) }.getOrElse { 200 }
                it.summary = "当前间隔: $interval 毫秒"
                true
            }
        }
        holder.github.onPreferenceClickListener = this
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when (preference) {
            holder.github -> {
                context.openGithub()
            }
        }
        return true
    }
}