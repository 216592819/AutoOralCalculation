package cn.tinyhai.auto_oral_calculation.hook

import cn.tinyhai.auto_oral_calculation.util.logI
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.Unhook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

abstract class BaseHook {

    fun findClass(className: String): Class<*> {
        return XposedHelpers.findClass(className, lp.classLoader)
    }

    fun Method.before(block: (XC_MethodHook.MethodHookParam) -> Unit): Unhook {
        return XposedBridge.hookMethod(this, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                block(param)
            }
        })
    }

    fun Method.after(block: (XC_MethodHook.MethodHookParam) -> Unit): Unhook {
        return XposedBridge.hookMethod(this, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                block(param)
            }
        })
    }

    fun List<Method>.before(block: (XC_MethodHook.MethodHookParam) -> Unit): List<Unhook> {
        val callback = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                block(param)
            }
        }
        return map {
            XposedBridge.hookMethod(it, callback)
        }
    }

    fun List<Method>.after(block: (XC_MethodHook.MethodHookParam) -> Unit): List<Unhook> {
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                block(param)
            }
        }
        return map {
            XposedBridge.hookMethod(it, callback)
        }
    }

    fun Class<*>.allMethod(name: String): List<Method> {
        return declaredMethods.filter { it.name == name }
    }

    fun Class<*>.findMethod(name: String, vararg parameters: Any): Method {
        return XposedHelpers.findMethodExact(this, name, *parameters)
    }

    fun startHookCatching(): Result<Unit> {
        return kotlin.runCatching {
            startHook()
        }.onFailure {
            logI(it)
        }
    }

    protected abstract fun startHook()

    companion object {
        lateinit var lp: XC_LoadPackage.LoadPackageParam

        fun startHook(lp: XC_LoadPackage.LoadPackageParam) {
            this.lp = lp
            PracticeHook().startHookCatching()
            RecognizerHook().startHookCatching()
            WebViewHook().startHookCatching()
            SettingHook().startHookCatching()
            RetrofitHook().startHookCatching()
        }
    }
}