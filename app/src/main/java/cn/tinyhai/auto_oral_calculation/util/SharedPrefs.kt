package cn.tinyhai.auto_oral_calculation.util

import android.app.AndroidAppHelper
import android.content.Context
import cn.tinyhai.auto_oral_calculation.PREFS_NAME
import cn.tinyhai.auto_oral_calculation.entities.AutoAnswerMode

private val currentContext by lazy { AndroidAppHelper.currentApplication() }

val hostPrefs by lazy {
    currentContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

object Common {
    val alwaysTrue get() = hostPrefs.getBoolean("always_true_answer", true)
}

object Practice {
    val autoHonor get() = hostPrefs.getBoolean(moduleStringRes.KEY_AUTO_HONOR, false)
    val autoPractice
        get() = !autoHonor && hostPrefs.getBoolean(
            moduleStringRes.KEY_AUTO_PRACTICE,
            true
        )
    val autoPracticeQuick
        get() = autoPractice && hostPrefs.getBoolean(
            moduleStringRes.KEY_AUTO_PRACTICE_QUICK,
            false
        )
    val autoPracticeCyclic
        get() = autoPractice && hostPrefs.getBoolean(
            moduleStringRes.KEY_AUTO_PRACTICE_CYCLIC,
            false
        )
    val autoPracticeCyclicInterval: Int
        get() {
            return kotlin.runCatching {
                Integer.parseInt(
                    hostPrefs.getString(
                        moduleStringRes.KEY_AUTO_PRACTICE_CYCLIC_INTERVAL,
                        ""
                    )!!
                )
            }.getOrElse {
                1500
            }
        }
}

object PK {
    val mode: AutoAnswerMode
        get() {
            val index = runCatching {
                Integer.parseInt(hostPrefs.getString(moduleStringRes.KEY_AUTO_ANSWER_CONFIG, "")!!)
            }.getOrElse { 0 }
            return AutoAnswerMode.entries[index]
        }
    val customJs get() = hostPrefs.getString(moduleStringRes.KEY_CUSTOM_ANSWER_CONFIG, "")!!
    val quickModeMustWin
        get() = mode == AutoAnswerMode.QUICK && hostPrefs.getBoolean(
            moduleStringRes.KEY_QUICK_MODE_MUST_WIN, false
        )
    val quickModeInterval: Int
        get() {
            return kotlin.runCatching {
                Integer.parseInt(hostPrefs.getString(moduleStringRes.KEY_QUICK_MODE_INTERVAL, "")!!)
            }.getOrElse { 200 }
        }
    val pkCyclic
        get() = mode in arrayOf(
            AutoAnswerMode.STANDARD,
            AutoAnswerMode.QUICK
        ) && hostPrefs.getBoolean(moduleStringRes.KEY_PK_CYCLIC, false)
    val pkCyclicInterval: Int
        get() {
            return kotlin.runCatching {
                Integer.parseInt(hostPrefs.getString(moduleStringRes.KEY_PK_CYCLIC_INTERVAL, "")!!)
            }.getOrElse { 1500 }
        }
}