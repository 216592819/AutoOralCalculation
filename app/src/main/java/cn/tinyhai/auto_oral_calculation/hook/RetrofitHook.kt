package cn.tinyhai.auto_oral_calculation.hook

import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.util.Practice
import cn.tinyhai.auto_oral_calculation.util.logI
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class RetrofitHook : BaseHook(), InvocationHandler {
    override fun startHook() {
        val retrofitClass = findClass(Classname.RETROFIT)
        val apiServiceClass = findClass(Classname.ORAL_API_SERVICE)
        retrofitClass.findMethod("create", Class::class.java).after { param ->
            when (param.args[0]) {
                apiServiceClass -> {
                    addInterceptor(param.thisObject)
                }
            }
        }
    }

    private fun addInterceptor(retrofit: Any) {
        logI("addInterceptor")
        val interceptorClass = findClass(Classname.INTERCEPTOR)
        val callFactory = XposedHelpers.getObjectField(retrofit, "callFactory")
        val client = XposedHelpers.getObjectField(callFactory, "a")
        val interceptors = XposedHelpers.getObjectField(client, "interceptors") as List<*>
        val myInterceptor =
            Proxy.newProxyInstance(interceptorClass.classLoader, arrayOf(interceptorClass), this)
        XposedHelpers.setObjectField(client, "interceptors", (interceptors + myInterceptor).toList())
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
        return intercept(chain)
    }

    private fun intercept(chain: Any): Any? {
        val request = XposedHelpers.callMethod(chain, "request")
        val httpUrl = XposedHelpers.callMethod(request, "url")
        val method = XposedHelpers.callMethod(request, "method")
        val pathSegments = XposedHelpers.callMethod(httpUrl, "pathSegments") as List<*>
        val path = "/${pathSegments.take(3).joinToString("/") { it.toString() }}"

        if (!Practice.autoHonor || "/leo-math/android/exams" != path || method !in arrayOf("POST", "PUT")) {
            return XposedHelpers.callMethod(chain, "proceed", request)
        }

        val url = XposedHelpers.callMethod(request, "url")
        val urlBuilder = XposedHelpers.callMethod(url, "newBuilder")
        XposedHelpers.callMethod(urlBuilder, "setQueryParameter", "isBackground", "0")
        val newUrl = XposedHelpers.callMethod(urlBuilder, "build")
        val newBuilder = XposedHelpers.callMethod(request, "newBuilder")
        XposedHelpers.callMethod(newBuilder, "url", newUrl)
        val newRequest = XposedHelpers.callMethod(newBuilder, "build")
        return XposedHelpers.callMethod(chain, "proceed", newRequest)
    }
}