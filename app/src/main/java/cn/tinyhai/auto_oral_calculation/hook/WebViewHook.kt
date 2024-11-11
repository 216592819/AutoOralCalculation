package cn.tinyhai.auto_oral_calculation.hook

import android.app.AndroidAppHelper
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.XposedInit.Companion.moduleRes
import cn.tinyhai.auto_oral_calculation.entities.AutoAnswerMode
import cn.tinyhai.auto_oral_calculation.util.Debug
import cn.tinyhai.auto_oral_calculation.util.PK
import cn.tinyhai.auto_oral_calculation.util.logI
import cn.tinyhai.auto_oral_calculation.util.pathPoints
import cn.tinyhai.auto_oral_calculation.util.toJSONArray
import cn.tinyhai.auto_oral_calculation.util.toJsonString
import de.robv.android.xposed.XC_MethodHook.Unhook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.roundToLong
import kotlin.random.Random

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

    private val pkPageLoaded = AtomicBoolean(false)

    private val resultPageLoaded = AtomicBoolean(false)

    private val appropriateCostTime = AtomicLong(0L)

    private var webViewRef: WeakReference<View>? = null

    private val webView get() = webViewRef?.get()

    private var loadUrl: Method? = null

    @JavascriptInterface
    fun log(str: String) {
        logI("console.log >>>>>>>")
        logI(str)
        logI("console.log <<<<<<<")
    }

    @JavascriptInterface
    fun targetCostTime(costTime: Long) {
        appropriateCostTime.set(costTime - 100)
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
                str.contains("/bh5/leo-web-oral-pk/exercise.html") -> {
                    logI("exercise.html loaded")
                    hookConsoleLog()
                    pkPageLoaded.set(true)
                }

                str.contains("/bh5/leo-web-oral-pk/english-words.html") -> {
                    logI("english-words.html loaded")
                    hookConsoleLog()
                    pkPageLoaded.set(true)
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
                    pkPageLoaded.compareAndSet(true, false) -> {
                        injectJs2PkPage()
                    }

                    resultPageLoaded.compareAndSet(true, false) -> {
                        injectJs2ResultPage()
                    }
                }
            }
    }

    private fun injectConfig(loadUrl: Method, webView: View, key: String, value: Any) {
        XposedBridge.invokeOriginalMethod(
            loadUrl,
            webView,
            arrayOf("javascript: (function(){window._$key=$value;})();")
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

    private fun injectJs2PkPage() {
        val loadUrl = loadUrl ?: return
        val webView = webView ?: return
        webView.post {
            val mode = PK.mode
            val jsCode = when (mode) {
                AutoAnswerMode.QUICK -> quickJs

                AutoAnswerMode.CUSTOM -> PK.customJs

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
            injectConfig(loadUrl, webView, "pk_cyclic_interval", PK.pkCyclicInterval)

            if (PK.pkCyclic) {
                injectJsCode(cyclicJs, loadUrl, webView)
            }
        }
    }

    private fun hookAddJavascriptInterface(addJavascriptInterface: Method) {
        val openSchemaBeanClass = findClass(Classname.OPEN_SCHEMA_BEAN)
        val dataEncryptBeanClass = findClass(Classname.DATA_ENCRYPT_BEAN)
        val unhooks = arrayOf<Unhook?>(null)
        var count = 0
        addJavascriptInterface.before { param ->
            val obj = param.args[0]
            val name = param.args[1]
            logI(name)
            when (name) {
                "CommonWebView" -> {
                    val caller = XposedHelpers.callMethod(obj, "get", openSchemaBeanClass)
                    hookOpenSchema(caller::class.java)
                    count++
                }

                "LeoSecureWebView" -> {
                    obj::class.java.declaredFields.firstOrNull {
                        Map::class.java.isAssignableFrom(it.type)
                    }?.let {
                        val caller = (it.get(obj) as Map<*, *>)[dataEncryptBeanClass]!!
                        hookDataEncrypt(caller::class.java)
                    }
                    count++
                }

                else -> {}
            }
            if (count >= 2) {
                unhooks.forEach { it?.unhook() }
            }
        }.also {
            unhooks[0] = it
        }
    }

    private fun hookOpenSchema(caller: Class<*>) {
        var lastSchemas: Any? = null
        caller.allMethod("call").before {
            if (!PK.pkCyclic) {
                return@before
            }
            val schemas = XposedHelpers.getObjectField(it.args[0], "schemas") as Array<*>
            val url = Uri.parse(schemas[0].toString()).getQueryParameter("url")!!
            val targetUri = Uri.parse(url)
            when (targetUri.path) {
                "/bh5/leo-web-study-group/motivation-honor-roll.html" -> {
                    when (targetUri.getQueryParameter("fromType")) {
                        "oralPkResult" -> {
                            XposedHelpers.callMethod(
                                it.args[0],
                                "trigger",
                                webView,
                                null,
                                emptyArray<Any>()
                            )
                            it.result = null
                        }

                        "resultPageJs" -> {
                            XposedHelpers.setObjectField(it.args[0], "schemas", lastSchemas)
                            XposedHelpers.setBooleanField(it.args[0], "close", true)
                        }
                    }
                }

                "/bh5/leo-web-oral-pk/result.html" -> {}
                else -> {
                    lastSchemas = schemas.copyOf(schemas.size)
                }
            }
        }
    }

    private fun getSimulateCostTime(questionCnt: Int): Long {
        val interval = PK.quickModeInterval
        var costTime = 0L
        repeat(questionCnt) {
            costTime += (interval * (1 + Random.nextFloat() * 0.25)).roundToLong()
        }
        return costTime
    }

    private fun hookDataEncrypt(caller: Class<*>) {
        caller.allMethod("call").before { param ->
            val mode = PK.mode
            if (!Debug.debug && mode !in arrayOf(AutoAnswerMode.QUICK, AutoAnswerMode.STANDARD)) {
                return@before
            }
            val bean = param.args[0]
            val base64 = XposedHelpers.getObjectField(bean, "base64").toString()
            if (base64.isBlank()) {
                return@before
            }
            val json =
                kotlin.runCatching { JSONObject(Base64.decode(base64, 0).decodeToString()) }.getOrNull()
                    ?: return@before
            if (!json.has("pkIdStr")) {
                return@before
            }
            if (mode !in arrayOf(AutoAnswerMode.QUICK, AutoAnswerMode.STANDARD)) {
                return@before
            }
            runCatching {
                val questions = json.getJSONArray("questions")
                for (i in 0 until questions.length()) {
                    val pathPoints = pathPoints.toJSONArray()
                    val question = questions.getJSONObject(i)
                    val curTrueAnswer = question.getJSONObject("curTrueAnswer")
                    curTrueAnswer.put("pathPoints", pathPoints)
                    question.put("script", pathPoints.toString())
                }
                val questionCnt = json.getInt("questionCnt")
                if (mode == AutoAnswerMode.QUICK) {
                    val appropriateCostTime = appropriateCostTime.get()
                    val costTime = if (PK.quickModeMustWin && appropriateCostTime > 0) {
                        appropriateCostTime
                    } else {
                        getSimulateCostTime(questionCnt).let {
                            if (it > 0) {
                                it
                            } else {
                                questionCnt * 200L
                            }
                        }
                    }
                    logI("originCostTime: ${json.get("costTime")}, costTime: $costTime")
                    json.put("costTime", costTime)
                }
                if (Debug.debug) {
                    thread {
                        val file = File(
                            AndroidAppHelper.currentApplication().externalCacheDir,
                            "${System.currentTimeMillis()}.json"
                        )
                        file.writeText(json.toString())
                    }
                }
                val newBase64 = Base64.encode(json.toString().toByteArray(), 0).decodeToString()
                XposedHelpers.setObjectField(bean, "base64", newBase64)
            }.onFailure {
                logI(it)
            }
        }
    }
}