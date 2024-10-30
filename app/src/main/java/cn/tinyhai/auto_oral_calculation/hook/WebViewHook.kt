package cn.tinyhai.auto_oral_calculation.hook

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.XposedInit.Companion.moduleRes
import cn.tinyhai.auto_oral_calculation.entities.AutoAnswerMode
import cn.tinyhai.auto_oral_calculation.util.hostPrefs
import cn.tinyhai.auto_oral_calculation.util.logI
import de.robv.android.xposed.XC_MethodHook.Unhook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method
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

    private val cyclicJs by lazy {
        moduleRes.assets.open("js/cyclic.js")
            .bufferedReader().use { it.readText() }
    }

    private val exercisePageLoaded = AtomicBoolean(false)

    private val resultPageLoaded = AtomicBoolean(false)

    private var webViewRef: WeakReference<View>? = null

    private val webView get() = webViewRef?.get()

    private var loadUrl: Method? = null

    @JavascriptInterface
    fun log(str: String) {
        logI("console.log >>>>>>>")
        logI(str)
        logI("console.log <<<<<<<")
    }

    private fun hookConsoleLog() {
        val loadUrl = loadUrl ?: return
        val webView = webView ?: return
        XposedBridge.invokeOriginalMethod(
            loadUrl,
            webView,
            arrayOf("javascript: (function() { let backup_log=console.log;console.log=function(){if(arguments.length>=1){let l=arguments[0];window.AutoOral&&window.AutoOral.log(typeof l===l?l:JSON.stringify(l))}return backup_log(arguments)}; })();")
        )
    }

    override fun startHook() {
        val baseWebAppClass = findClass(Classname.BASE_WEB_APP)
        val simpleWebAppFireworkClass =
            findClass(Classname.SIMPLE_WEB_APP_FIREWORK_ACTIVITY)
        val webViewField =
            simpleWebAppFireworkClass.fields.firstOrNull { it.type == baseWebAppClass }

        loadUrl = baseWebAppClass.methods.firstOrNull {
            it.name == "loadUrl" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }
        val addJavascriptInterface = baseWebAppClass.methods.firstOrNull {
            it.name == "addJavascriptInterface"
        }
        addJavascriptInterface?.let(::hookAddJavascriptInterface)

        simpleWebAppFireworkClass.findMethod("onCreate", Bundle::class.java).after { param ->
            logI("simpleWebApp onCreate")
            webViewField?.get(param.thisObject)?.let {
                webViewRef = WeakReference(it as View)
                addJavascriptInterface?.invoke(
                    it,
                    this,
                    "AutoOral"
                )
            }
        }

        loadUrl?.after { param ->
            val str = param.args[0].toString()
            when {
                str.startsWith("javascript:") -> return@after
                str.contains("/leo-web-oral-pk/exercise.html") -> {
                    logI("exercise.html loaded")
                    hookConsoleLog()
                    exercisePageLoaded.set(true)
                }
                str.contains("/bh5/leo-web-oral-pk/result.html") -> {
                    logI("result.html loaded")
                    hookConsoleLog()
                    resultPageLoaded.set(true)
                }
            }
        }

        hookJsLoadComplete()
    }

    private fun hookJsLoadComplete() {
        val commonWebViewInterfaceClass = findClass(Classname.COMMON_WEB_VIEW_INTERFACE)
        commonWebViewInterfaceClass.findMethod("jsLoadComplete", String::class.java)
            .after {
                when {
                    exercisePageLoaded.compareAndSet(true, false) -> {
                        injectJs2ExercisePage()
                    }

                    resultPageLoaded.compareAndSet(true, false) -> {
                        injectJs2ResultPage()
                    }
                }
            }
    }

    private fun getAutoAnswerMode(): AutoAnswerMode {
        val index = runCatching {
            Integer.parseInt(
                hostPrefs.getString(
                    "auto_answer_config",
                    "0"
                )!!
            )
        }.getOrElse { 0 }
        return AutoAnswerMode.entries[index]
    }

    private fun injectConfig(loadUrl: Method, webView: View, key: String, value: (String) -> Any) {
        val v = value(key)
        XposedBridge.invokeOriginalMethod(
            loadUrl,
            webView,
            arrayOf("javascript: (function(){window._$key=$v;})();")
        )
    }

    private fun injectJsCode(jsCode: String, loadUrl: Method, webView: View) {
        XposedBridge.invokeOriginalMethod(
            loadUrl,
            webView,
            arrayOf("javascript:(function() { $jsCode })();")
        )
        logI("js injected")
    }

    private fun injectJs2ExercisePage() {
        val loadUrl = loadUrl ?: return
        val webView = webView ?: return
        webView.post {
            injectConfig(loadUrl, webView, "quick_mode_interval") {
                runCatching { Integer.parseInt(hostPrefs.getString(it, "")!!) }.getOrElse { 200 }
            }
            injectConfig(loadUrl, webView, "quick_mode_must_win") {
                hostPrefs.getBoolean(it, false)
            }

            val mode = getAutoAnswerMode()
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
                injectJsCode(jsCode, loadUrl, webView)
            }
        }
    }

    private fun injectJs2ResultPage() {
        val loadUrl = loadUrl ?: return
        val webView = webView ?: return
        webView.post {
            injectConfig(loadUrl, webView, "pk_cyclic_interval") {
                runCatching { Integer.parseInt(hostPrefs.getString(it, "")!!) }.getOrElse { 1500 }
            }

            val mode = getAutoAnswerMode()
            when (mode) {
                AutoAnswerMode.QUICK, AutoAnswerMode.STANDARD -> {
                    if (hostPrefs.getBoolean("pk_cyclic", false)) {
                        injectJsCode(cyclicJs, loadUrl, webView)
                    }
                }
                else -> {}
            }
        }
    }

    private fun hookAddJavascriptInterface(addJavascriptInterface: Method) {
        val openSchemaBeanClass = findClass(Classname.OPEN_SCHEMA_BEAN)
        val unhooks = arrayOf<Unhook?>(null)
        addJavascriptInterface.before { param ->
            val obj = param.args[0]
            val name = param.args[1]
            logI(name)
            when (name) {
                "CommonWebView" -> {
                    val caller = XposedHelpers.callMethod(obj, "get", openSchemaBeanClass)
                    hookOpenSchema(caller::class.java)
                    unhooks.forEach { it?.unhook() }
                }
                else -> {}
            }
        }.also {
            unhooks[0] = it
        }
    }

    private fun hookOpenSchema(caller: Class<*>) {
        var exerciseSchemas: Any? = null
        caller.allMethod("call").before {
            if (getAutoAnswerMode() !in arrayOf(AutoAnswerMode.QUICK, AutoAnswerMode.STANDARD)) {
                return@before
            }
            if (!hostPrefs.getBoolean("pk_cyclic", false)) {
                return@before
            }
            val schemas = XposedHelpers.getObjectField(it.args[0], "schemas") as Array<*>
            val url = Uri.parse(schemas[0].toString()).getQueryParameter("url")!!
            val targetUri = Uri.parse(url)
            when (targetUri.path) {
                "/bh5/leo-web-oral-pk/exercise.html" -> {
                    exerciseSchemas = schemas.copyOf(schemas.size)
                }
                "/bh5/leo-web-study-group/motivation-honor-roll.html" -> {
                    when (targetUri.getQueryParameter("fromType")) {
                        "oralPkResult" -> {
                            XposedHelpers.callMethod(it.args[0], "trigger", webView, null, emptyArray<Any>())
                            it.result = null
                        }
                        "resultPageJs" -> {
                            XposedHelpers.setObjectField(it.args[0], "schemas", exerciseSchemas)
                            XposedHelpers.setBooleanField(it.args[0], "close", true)
                        }
                    }
                }
            }
        }
    }
}