package cn.tinyhai.auto_oral_calculation.util

import android.app.AndroidAppHelper
import android.content.Context
import cn.tinyhai.auto_oral_calculation.PREFS_NAME

private val currentContext by lazy { AndroidAppHelper.currentApplication() }

val hostPrefs by lazy {
    currentContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}