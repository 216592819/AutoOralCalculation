package cn.tinyhai.auto_oral_calculation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.content.res.XModuleResources
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.widget.LinearLayout
import android.widget.TextView
import cn.tinyhai.auto_oral_calculation.entities.AutoAnswerMode
import cn.tinyhai.auto_oral_calculation.ui.SettingsDialog
import cn.tinyhai.auto_oral_calculation.util.hostPrefs
import cn.tinyhai.auto_oral_calculation.util.logI
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference


class XposedInit : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        lateinit var modulePath: String
        lateinit var moduleRes: Resources
    }

    private val handler by lazy {
        Handler(Looper.getMainLooper())
    }

    private lateinit var presenterRef: WeakReference<Any>

    private val presenter get() = presenterRef.get()

    @JavascriptInterface
    fun log(str: String) {
        logI("console.log >>>>>>>")
        logI(str)
        logI("console.log <<<<<<<")
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
        moduleRes = XModuleResources.createInstance(modulePath, null)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != lpparam.processName || HOST_PACKAGE_NAME != lpparam.packageName) {
            return
        }

        hookRecognizer(lpparam.classLoader)

        hookPractice(lpparam.classLoader)

        hookWebView(lpparam.classLoader)

        hookSettingActivity(lpparam.classLoader)

        hookRouterActivity(lpparam.classLoader)

        hookHomeActivity(lpparam.classLoader)
    }

    private var shouldStartSettings = false

    private fun hookRouterActivity(classLoader: ClassLoader?) {
        val routerActivityClass = XposedHelpers.findClass(ROUTER_ACTIVITY_CLASSNAME, classLoader)
        XposedHelpers.findAndHookMethod(routerActivityClass, "onCreate", Bundle::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as Activity
                val intent = activity.intent ?: return
                shouldStartSettings = intent.getBooleanExtra(KEY_START_SETTINGS, false)
            }
        })
    }

    private fun hookHomeActivity(classLoader: ClassLoader?) {
        val homeActivityClass = XposedHelpers.findClass(HOME_ACTIVITY_CLASSNAME, classLoader)
        XposedHelpers.findAndHookMethod(homeActivityClass, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (shouldStartSettings) {
                    val context = param.thisObject as Context
                    val intent = Intent().apply {
                        component = ComponentName(HOST_PACKAGE_NAME, SETTINGS_ACTIVITY_CLASSNAME)
                    }
                    context.startActivity(intent)
                }
            }
        })
    }

    private fun hookSettingActivity(classLoader: ClassLoader) {
        val settingsActivityClass =
            XposedHelpers.findClass(SETTINGS_ACTIVITY_CLASSNAME, classLoader)
        val sectionItemClass = XposedHelpers.findClass(SECTION_ITEM_CLASSNAME, classLoader)
        val sectionItemConstructor = sectionItemClass.getConstructor(Context::class.java)
        XposedHelpers.findAndHookMethod(settingsActivityClass, "onCreate", Bundle::class.java, object : XC_MethodHook() {
            @SuppressLint("SetTextI18n", "DiscouragedApi")
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as Activity
                val appWidgetId = activity.resources.getIdentifier("cell_appwidget", "id", activity.packageName)
                val appWidget = activity.findViewById<View>(appWidgetId)
                val container = appWidget.parent as LinearLayout
                val item = sectionItemConstructor.newInstance(activity) as ViewGroup
                val labelId = activity.resources.getIdentifier("text_label", "id", activity.packageName)
                val label = item.findViewById<TextView>(labelId)
                label.text = "口算糕手设置"
                item.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                item.setOnClickListener {
                    try {
                        SettingsDialog(activity).show()
                    } catch (th: Throwable) {
                        logI(th.cause!!)
                    }
                }

                container.addView(item, 0)
            }
        })

        XposedHelpers.findAndHookMethod(settingsActivityClass, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (shouldStartSettings) {
                    shouldStartSettings = false
                    SettingsDialog(param.thisObject as Context).show()
                }
            }
        })
    }

    private fun hookPractice(classLoader: ClassLoader) {
        val qep = XposedHelpers.findClass(PRESENTER_CLASSNAME, classLoader)
        val startExercise = XposedHelpers.findMethodExact(qep, "c", *emptyArray())
        val getAnswers = XposedHelpers.findMethodExact(qep, "g", *emptyArray())
        val commitAnswer =
            XposedHelpers.findMethodExact(qep, "e", String::class.java, List::class.java)
        val nextQuestion = XposedHelpers.findMethodExact(
            qep,
            "d",
            Boolean::class.javaPrimitiveType,
            List::class.java
        )
        val performNext = Runnable {
            if (hostPrefs.getBoolean("auto_practice", true)) {
                presenter?.let {
                    startExercise.invoke(it)
                    val answer = (getAnswers(it) as? List<*>)?.get(0).toString()
                    logI("answer: $answer")
                    commitAnswer.invoke(it, answer, null)
                    nextQuestion.invoke(it, true, emptyList<PointF>())
                }
            }
        }
        // afterAnimation
        XposedHelpers.findAndHookMethod(qep, "N", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (hostPrefs.getBoolean("auto_practice", true)) {
                    handler.post(performNext)
                }
            }
        })
        // afterLoadFinish
        XposedHelpers.findAndHookMethod(qep, "P", List::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                presenterRef = WeakReference(param.thisObject)
                if (hostPrefs.getBoolean("auto_practice", true)) {
                    performNext.run()
                }
            }
        })
    }

    private fun hookRecognizer(classLoader: ClassLoader) {
        val msr = XposedHelpers.findClass(MATH_SCRIPT_RECOGNIZER_CLASSNAME, classLoader)
        XposedHelpers.findAndHookMethod(
            msr,
            "a",
            Int::class.javaPrimitiveType,
            List::class.java,
            List::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!hostPrefs.getBoolean("always_true_answer", true)) {
                        return
                    }
                    val answers = param.args[2] as List<*>
                    param.result = if (answers.isNotEmpty()) {
                        answers[0].toString()
                    } else {
                        ""
                    }
                }
            }
        )
    }
    
    private fun hookWebView(classLoader: ClassLoader) {
        val baseWebAppClass = XposedHelpers.findClass(BASE_WEB_APP_CLASSNAME, classLoader)
        val simpleWebAppFireworkClass =
            XposedHelpers.findClass(SIMPLE_WEB_APP_FIREWORK_CLASSNAME, classLoader)
        val webViewField = simpleWebAppFireworkClass.fields.firstOrNull { it.type == baseWebAppClass }
        var webView: WeakReference<View>? = null
        val loadUrl = baseWebAppClass.methods.firstOrNull {
            it.name == "loadUrl" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }
        val addJavascriptInterface = baseWebAppClass.methods.firstOrNull {
            it.name == "addJavascriptInterface"
        }
        XposedHelpers.findAndHookMethod(simpleWebAppFireworkClass, "onCreate", Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                webViewField?.get(param.thisObject)?.let {
                    webView = WeakReference(it as View)
                }
            }
        })

        var exerciseLoaded = false
        loadUrl?.let {
            XposedBridge.hookMethod(it, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val str = param.args[0].toString()
                    when {
                        str.contains("/leo-web-oral-pk/exercise.html") -> {
                            logI("exercise.html loaded")
                            exerciseLoaded = true
                            addJavascriptInterface?.invoke(param.thisObject, this@XposedInit, "AutoOral")
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, arrayOf("javascript: (function() { let backup_log=console.log;console.log=function(){if(arguments.length>=1){let l=arguments[0];window.AutoOral&&window.AutoOral.log(typeof l===l?l:JSON.stringify(l))}return backup_log(arguments)}; })();"))
                        }
                    }
                }
            })
        }

        XposedHelpers.findAndHookMethod(
            "com.yuanfudao.android.common.webview.CommonWebViewInterface",
            classLoader,
            "jsLoadComplete",
            String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (exerciseLoaded) {
                        webView?.get()?.let {
                            it.post {
                                exerciseLoaded = false

                                val interval = runCatching { Integer.parseInt(hostPrefs.getString("quick_mode_interval", "200")!!) }.getOrElse { 200 }
                                XposedBridge.invokeOriginalMethod(loadUrl, it, arrayOf("javascript: (function() { window._quick_mode_interval = $interval; let backup_log=console.log;console.log=function(){return arguments.length>=1&&window.AutoOral&&window.AutoOral.log(arguments[0]),backup_log(arguments)}; })();"))

                                val index = runCatching { Integer.parseInt(hostPrefs.getString("auto_answer_config", "0")!!) }.getOrElse { 0 }
                                val mode = AutoAnswerMode.entries[index]
                                val jsCode = when (mode) {
                                    AutoAnswerMode.QUICK -> moduleRes.assets.open("js/quick.js").bufferedReader().use { it.readText() }
                                    AutoAnswerMode.CUSTOM -> hostPrefs.getString("custom_answer_config", "")!!
                                    AutoAnswerMode.STANDARD -> moduleRes.assets.open("js/standard.js").bufferedReader().use { it.readText() }
                                    AutoAnswerMode.DISABLE -> ""
                                }
                                if (jsCode.isEmpty()) {
                                    logI("自动答题配置: ${mode.value}")
                                } else {
                                    try {
                                        XposedBridge.invokeOriginalMethod(loadUrl, it, arrayOf("javascript:(function() { $jsCode })();"))
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
        )
    }
}