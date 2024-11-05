package cn.tinyhai.auto_oral_calculation.hook

import cn.tinyhai.auto_oral_calculation.Classname
import de.robv.android.xposed.XC_MethodHook.Unhook
import de.robv.android.xposed.XposedHelpers

class RetrofitHook : BaseHook() {
    override fun startHook() {
        val retrofitClass = findClass(Classname.RETROFIT)
        val apiServiceClass = findClass(Classname.ORAL_API_SERVICE)
        val unhooks = arrayOf<Unhook?>(null)
        retrofitClass.findMethod("create", Class::class.java).before { param ->
            if (param.args[0] != apiServiceClass) {
                return@before
            }
            val retrofit = param.thisObject
            val callFactory = XposedHelpers.getObjectField(retrofit, "callFactory")
            val okhttpClient = XposedHelpers.getObjectField(callFactory, "a")
            val dispatcher = XposedHelpers.getObjectField(okhttpClient, "dispatcher")
            XposedHelpers.callMethod(dispatcher, "setMaxRequestsPerHost", 10)
            unhooks.forEach { it?.unhook() }
        }.also { unhooks[0] = it }
    }
}