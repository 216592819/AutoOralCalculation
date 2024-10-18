package cn.tinyhai.auto_oral_calculation.util

import android.util.Log
import de.robv.android.xposed.XposedBridge

private const val TAG = "AutoOral"

fun logI(vararg infos: Any) {
    infos.forEach {
        XposedBridge.log("$TAG >>> $it")
        Log.e(TAG, it.toString())
        if (it is Throwable) {
            XposedBridge.log(it)
            Log.e(TAG, "", it)
        }
    }
}