package cn.tinyhai.auto_oral_calculation.hook

import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.api.OralApiService
import de.robv.android.xposed.XC_MethodHook.Unhook
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

class RetrofitHook : BaseHook(), InvocationHandler {
    override fun startHook() {
        val retrofitClass = findClass(Classname.RETROFIT)
        val apiServiceClass = findClass(Classname.ORAL_API_SERVICE)
        val unhooks = arrayOf<Unhook?>(null)
        retrofitClass.findMethod("create", Class::class.java).after { param ->
            if (param.args[0] != apiServiceClass) {
                return@after
            }
            OralApiService.init(param.result)
            unhooks.forEach { it?.unhook() }
        }.also { unhooks[0] = it }
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