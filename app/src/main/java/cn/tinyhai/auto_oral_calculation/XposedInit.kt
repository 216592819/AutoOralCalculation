package cn.tinyhai.auto_oral_calculation

import android.content.res.Resources
import android.content.res.XModuleResources
import cn.tinyhai.auto_oral_calculation.hook.BaseHook
import cn.tinyhai.auto_oral_calculation.util.install
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.callbacks.XC_LoadPackage


class XposedInit : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        lateinit var modulePath: String
        lateinit var moduleRes: Resources
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
        moduleRes = XModuleResources.createInstance(modulePath, null)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != lpparam.processName || HOST_PACKAGE_NAME != lpparam.packageName) {
            return
        }

        install()

        BaseHook.startHook(lpparam)
    }
}