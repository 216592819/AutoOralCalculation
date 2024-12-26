package cn.tinyhai.auto_oral_calculation.util

import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject

fun install() {
    runCatching {
        System.loadLibrary("auto_oral")
    }.onFailure {
        logI(it)
    }
}

val String.nativeStrokes: List<Array<DoubleArray>>
    external get

val String.strokes: List<Array<PointF>> get() {
    return nativeStrokes.map {
        it.map { PointF(it[0].toFloat(), it[1].toFloat()) }.toTypedArray()
    }.also {
        logI("answer: $this, strokes: ${it.size}")
    }
}

val String.pathPoints get() = nativeStrokes

fun List<Array<*>>.toJsonString(): String {
    return toJSONArray().toString()
}

fun List<Array<*>>.toJSONArray(): JSONArray {
    val jsonArray = JSONArray()
    forEach {
        val arr = JSONArray()
        it.forEach { point ->
            val p = JSONObject()
            when (point) {
                is PointF -> {
                    p.put("x", point.x)
                    p.put("y", point.y)
                }
                is DoubleArray -> {
                    p.put("x", point[0])
                    p.put("y", point[1])
                }
                else -> throw UnsupportedOperationException()
            }
            arr.put(p)
        }
        jsonArray.put(arr)
    }
    return jsonArray
}

