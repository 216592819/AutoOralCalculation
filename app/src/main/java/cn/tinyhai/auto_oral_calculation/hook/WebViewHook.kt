package cn.tinyhai.auto_oral_calculation.hook

import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.XposedInit.Companion.moduleRes
import cn.tinyhai.auto_oral_calculation.entities.AutoAnswerMode
import cn.tinyhai.auto_oral_calculation.util.hostPrefs
import cn.tinyhai.auto_oral_calculation.util.logI
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

class WebViewHook : BaseHook() {

    private val standardJs by lazy {
        moduleRes.assets.open("js/standard.js")
            .bufferedReader().use { it.readText() }
    }

    private val quickJs by lazy {
        moduleRes.assets.open("js/quick.js")
            .bufferedReader().use { it.readText() }
    }

    private val shouldInjectJs = AtomicBoolean(false)

    private var webViewRef: WeakReference<View>? = null

    private val webView get() = webViewRef?.get()

    @JavascriptInterface
    fun log(str: String) {
        logI("console.log >>>>>>>")
        logI(str)
        logI("console.log <<<<<<<")
    }

    override fun startHook() {
        val baseWebAppClass = findClass(Classname.BASE_WEB_APP)
        val simpleWebAppFireworkClass =
            findClass(Classname.SIMPLE_WEB_APP_FIREWORK_ACTIVITY)
        val webViewField =
            simpleWebAppFireworkClass.fields.firstOrNull { it.type == baseWebAppClass }
        val loadUrl = baseWebAppClass.methods.firstOrNull {
            it.name == "loadUrl" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }
        val addJavascriptInterface = baseWebAppClass.methods.firstOrNull {
            it.name == "addJavascriptInterface"
        }
        simpleWebAppFireworkClass.findMethod("onCreate", Bundle::class.java).after { param ->
            webViewField?.get(param.thisObject)?.let {
                webViewRef = WeakReference(it as View)
            }
        }

        loadUrl?.after { param ->
            val str = param.args[0].toString()
            if (str.contains("/leo-web-oral-pk/exercise.html")) {
                logI("exercise.html loaded")
                shouldInjectJs.set(true)

                addJavascriptInterface?.invoke(
                    param.thisObject,
                    this,
                    "AutoOral"
                )
                XposedBridge.invokeOriginalMethod(
                    param.method,
                    param.thisObject,
                    arrayOf("javascript: (function() { let backup_log=console.log;console.log=function(){if(arguments.length>=1){let l=arguments[0];window.AutoOral&&window.AutoOral.log(typeof l===l?l:JSON.stringify(l))}return backup_log(arguments)}; })();")
                )
            }
        }

        val commonWebViewInterfaceClass = findClass(Classname.COMMON_WEB_VIEW_INTERFACE)
        commonWebViewInterfaceClass.findMethod("jsLoadComplete", String::class.java)
            .after {
                if (shouldInjectJs.compareAndSet(true, false)) {
                    val webView = webView ?: return@after
                    webView.post {
                        val interval = runCatching {
                            Integer.parseInt(
                                hostPrefs.getString(
                                    "quick_mode_interval",
                                    "200"
                                )!!
                            )
                        }.getOrElse { 200 }
                        XposedBridge.invokeOriginalMethod(
                            loadUrl,
                            webView,
                            arrayOf("javascript: (function() { window._quick_mode_interval = $interval; let backup_log=console.log;console.log=function(){return arguments.length>=1&&window.AutoOral&&window.AutoOral.log(arguments[0]),backup_log(arguments)}; })();")
                        )

                        val index = runCatching {
                            Integer.parseInt(
                                hostPrefs.getString(
                                    "auto_answer_config",
                                    "0"
                                )!!
                            )
                        }.getOrElse { 0 }
                        val mode = AutoAnswerMode.entries[index]
                        val jsCode = when (mode) {
                            AutoAnswerMode.QUICK -> quickJs

                            AutoAnswerMode.CUSTOM -> hostPrefs.getString(
                                "custom_answer_config",
                                ""
                            )!!

                            AutoAnswerMode.STANDARD -> standardJs

                            AutoAnswerMode.DISABLE -> ""
                        }
                        if (jsCode.isEmpty()) {
                            logI("自动答题配置: ${mode.value}")
                        } else {
                            try {
                                XposedBridge.invokeOriginalMethod(
                                    loadUrl,
                                    webView,
                                    arrayOf("javascript:(function() { $jsCode })();")
                                )
                                logI("js injected")
                            } catch (th: Throwable) {
                                logI(th)
                            }
                        }
                    }
                }
            }
    }
}