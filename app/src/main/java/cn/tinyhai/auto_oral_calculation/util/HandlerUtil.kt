package cn.tinyhai.auto_oral_calculation.util

import android.os.Handler
import android.os.Looper

val mainHandler by lazy {
    Handler(Looper.getMainLooper())
}