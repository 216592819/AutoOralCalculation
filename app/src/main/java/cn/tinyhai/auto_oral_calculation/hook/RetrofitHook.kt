package cn.tinyhai.auto_oral_calculation.hook

import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.api.LegacyApiService
import cn.tinyhai.auto_oral_calculation.api.OralApiService
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

class RetrofitHook : BaseHook(), InvocationHandler {
    override fun startHook() {
    }

    override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
        if (method.name != "intercept") {
            return if (args == null) {
                method.invoke(this)
            } else {
                method.invoke(this, *args)
            }
        }
        val chain = args!![0]
        val request = XposedHelpers.callMethod(chain, "request")
        val httpUrl = XposedHelpers.callMethod(request, "url")
        val pathSegments = XposedHelpers.callMethod(httpUrl, "pathSegments") as List<*>
        val path = "/${pathSegments.take(3).joinToString("/") { it.toString() }}"
        return XposedHelpers.callMethod(chain, "proceed", request)
    }
}