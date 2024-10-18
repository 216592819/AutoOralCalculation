package cn.tinyhai.auto_oral_calculation.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import cn.tinyhai.auto_oral_calculation.HOST_PACKAGE_NAME
import cn.tinyhai.auto_oral_calculation.KEY_START_SETTINGS

fun Context.openSettingsInHostApp() {
    packageManager.getLaunchIntentForPackage(HOST_PACKAGE_NAME)?.run {
        addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        putExtra(KEY_START_SETTINGS, true)
        startActivity(this)
    } ?: Toast.makeText(this, "请先安装小猿口算App", Toast.LENGTH_SHORT).show()
}

fun Context.openGithub() {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/TinyHai/AutoOralCalculation"))
    startActivity(intent)
}