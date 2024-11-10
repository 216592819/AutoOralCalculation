package cn.tinyhai.auto_oral_calculation.util

import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

val strokes: List<Array<PointF>> get() {
    var x = Random.nextInt(233, 666) + Random.nextFloat() * 100
    var y = Random.nextInt(233, 666) + Random.nextFloat() * 100
    val deltaX = 22.222f
    val deltaY = 22.222f
    val list = ArrayList<Array<PointF>>()
    val points = arrayListOf(PointF(x, y))
    repeat(4) {
        x += deltaX
        y += deltaY
        points.add(PointF(x, y))
    }
    repeat(8) {
        x += deltaX
        y -= deltaY
        points.add(PointF(x, y))
    }
    list.add(points.toTypedArray())
    return list
}

val strokesJsonString: String get() {
    val jsonArray = JSONArray()
    strokes.forEach {
        val arr = JSONArray()
        it.forEach { point ->
            val p = JSONObject()
            p.put("x", point.x)
            p.put("y", point.y)
            arr.put(p)
        }
        jsonArray.put(arr)
    }
    return jsonArray.toString()
}