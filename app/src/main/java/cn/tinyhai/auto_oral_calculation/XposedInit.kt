package cn.tinyhai.auto_oral_calculation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.content.res.XModuleResources
import android.graphics.PointF
import android.net.Uri
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
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.ArrayList
import kotlin.random.Random


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
        val routerActivityClass = XposedHelpers.findClass(Classname.ROUTER_ACTIVITY, classLoader)
        XposedHelpers.findAndHookMethod(
            routerActivityClass,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    val intent = activity.intent ?: return
                    shouldStartSettings = intent.getBooleanExtra(KEY_START_SETTINGS, false)
                }
            })
    }

    private fun hookHomeActivity(classLoader: ClassLoader?) {
        val homeActivityClass = XposedHelpers.findClass(Classname.HOME_ACTIVITY, classLoader)
        XposedHelpers.findAndHookMethod(homeActivityClass, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (shouldStartSettings) {
                    val context = param.thisObject as Context
                    val intent = Intent().apply {
                        component = ComponentName(HOST_PACKAGE_NAME, Classname.SETTINGS_ACTIVITY)
                    }
                    context.startActivity(intent)
                }
            }
        })
    }

    private fun hookSettingActivity(classLoader: ClassLoader) {
        val settingsActivityClass =
            XposedHelpers.findClass(Classname.SETTINGS_ACTIVITY, classLoader)
        val sectionItemClass = XposedHelpers.findClass(Classname.SECTION_ITEM, classLoader)
        val sectionItemConstructor = sectionItemClass.getConstructor(Context::class.java)
        XposedHelpers.findAndHookMethod(
            settingsActivityClass,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                @SuppressLint("SetTextI18n", "DiscouragedApi")
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    val appWidgetId = activity.resources.getIdentifier(
                        "cell_appwidget",
                        "id",
                        activity.packageName
                    )
                    val appWidget = activity.findViewById<View>(appWidgetId)
                    val container = appWidget.parent as LinearLayout
                    val item = sectionItemConstructor.newInstance(activity) as ViewGroup
                    val labelId =
                        activity.resources.getIdentifier("text_label", "id", activity.packageName)
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

        XposedHelpers.findAndHookMethod(
            settingsActivityClass,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (shouldStartSettings) {
                        shouldStartSettings = false
                        SettingsDialog(param.thisObject as Context).show()
                    }
                }
            })
    }

    private class ExerciseGeneralModelWrapper(model: Any, classLoader: ClassLoader) {
        private val getExerciseType: Method
        private val buildUri: Method
        private val gotoResult: Method
        init {
            val exerciseTypeClass = XposedHelpers.findClass(Classname.EXERCISE_TYPE, classLoader)
            val modelClass = model::class.java
            getExerciseType = modelClass.methods.first {
                it.returnType == exerciseTypeClass && it.parameterCount == 0
            }.also { it.isAccessible = true }
            buildUri = modelClass.methods.first {
                it.returnType == Uri::class.java && it.parameterCount == 2
            }.also { it.isAccessible = true }
            gotoResult = modelClass.methods.first {
                it.returnType == Void.TYPE && it.parameterCount > 1 && it.parameterTypes[0] == Context::class.java
            }.also { it.isAccessible = true }
        }

        fun getExerciseType(model: Any): Any? {
            return getExerciseType.invoke(model)
        }

        fun buildUri(model: Any, costTime: Long, dataList: List<*>): Any? {
            return buildUri.invoke(model, costTime, dataList)
        }

        fun gotoResult(model: Any, context: Context, intent: Intent, uri: Uri, exerciseType: Int) {
            gotoResult.invoke(model, context, intent, uri, exerciseType)
        }
    }

    private val strokes by lazy {
        var x = 233.33333f
        var y = 233.33333f
        val deltaX = 22.222f
        val deltaY = 22.222f
        val list = ArrayList<Array<PointF>>()
        val points = arrayListOf(PointF(x, y))
        repeat(9) {
            x += deltaX
            y += deltaY
            points.add(PointF(x, y))
        }
        repeat(18) {
            x += deltaX
            y -= deltaY
            points.add(PointF(x, y))
        }
        list.add(points.toTypedArray())
        list
    }

    private fun hookPractice(classLoader: ClassLoader) {
        val quickExercisePresenterClass = XposedHelpers.findClass(Classname.PRESENTER, classLoader)
        val startExercise =
            XposedHelpers.findMethodExact(quickExercisePresenterClass, "c", *emptyArray())
        val getAnswers =
            XposedHelpers.findMethodExact(quickExercisePresenterClass, "g", *emptyArray())
        val commitAnswer =
            XposedHelpers.findMethodExact(
                quickExercisePresenterClass,
                "e",
                String::class.java,
                List::class.java
            )
        val nextQuestion = XposedHelpers.findMethodExact(
            quickExercisePresenterClass,
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
                    nextQuestion.invoke(it, true, strokes.toList())
                }
            }
        }
        // afterAnimation
        XposedHelpers.findAndHookMethod(quickExercisePresenterClass, "N", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (hostPrefs.getBoolean("auto_practice", true)) {
                    handler.post(performNext)
                }
            }
        })

        val quickExerciseActivityClass =
            XposedHelpers.findClass(Classname.QUICK_EXERCISE_ACTIVITY, classLoader)
        val modelClass =
            (quickExerciseActivityClass.genericSuperclass as ParameterizedType).actualTypeArguments.getOrNull(
                1
            )
        val getGeneralModel =
            quickExerciseActivityClass.declaredMethods.firstOrNull { it.returnType == modelClass && it.parameterCount == 0 }
                ?.also { it.isAccessible = true }
        var modelWrapper: ExerciseGeneralModelWrapper? = null
        // afterLoadFinish
        XposedHelpers.findAndHookMethod(
            quickExercisePresenterClass,
            "P",
            List::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    presenterRef = WeakReference(param.thisObject)
                    if (!hostPrefs.getBoolean("auto_practice", true)) {
                        return
                    }
                    if (hostPrefs.getBoolean("auto_practice_quick", false)) {
                        kotlin.runCatching {
                            val v = XposedHelpers.getObjectField(param.thisObject, "a")
                            val activity = XposedHelpers.callMethod(v, "getContext") as Activity
                            val model = getGeneralModel?.invoke(activity)!!
                            val dataList = quickExercisePresenterClass.declaredFields.firstOrNull {
                                List::class.java.isAssignableFrom(it.type)
                            }?.get(param.thisObject) as List<*>
                            var totalTime = 0
                            dataList.subList(1, dataList.size - 1).forEach { data ->
                                val answers = XposedHelpers.getObjectField(data, "rightAnswers") as? List<*>
                                answers?.let {
                                    if (it.isNotEmpty()) {
                                        XposedHelpers.callMethod(data, "setUserAnswer", it[0])
                                    }
                                }
                                val costTime = Random.nextInt(200, 800)
                                XposedHelpers.callMethod(data, "setCostTime", costTime)
                                XposedHelpers.callMethod(data, "setStrokes", strokes.toList())
                                totalTime += costTime
                            }
                            val wrapper = modelWrapper ?: ExerciseGeneralModelWrapper(model, classLoader).also { modelWrapper = it }
                            val exerciseType = wrapper.getExerciseType(model)
                            val exerciseTypeInt = XposedHelpers.getIntField(exerciseType, "exerciseType")
                            val intent = activity.intent
                            val uri = wrapper.buildUri(model, totalTime.toLong(), dataList) as Uri
                            wrapper.gotoResult(model, activity, intent, uri, exerciseTypeInt)
                            activity.finish()
                        }.onFailure {
                            logI(it)
                        }
                    } else {
                        handler.post(performNext)
                    }
                }
            })

        val exerciseResultActivityClass =
            XposedHelpers.findClass(Classname.EXERCISE_RESULT_ACTIVITY, classLoader)
        val exerciseResultActivityCompanionClass =
            XposedHelpers.findClass("${Classname.EXERCISE_RESULT_ACTIVITY}\$a", classLoader)
        XposedHelpers.findAndHookMethod(
            exerciseResultActivityClass,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    kotlin.runCatching {
                        val activity = param.thisObject
                        val companion = XposedHelpers.findFirstFieldByExactType(
                            exerciseResultActivityClass,
                            exerciseResultActivityCompanionClass
                        ).get(activity)
                        val model = XposedHelpers.callMethod(companion, "a")
                        val examIdString = XposedHelpers.getObjectField(model, "a")
                        val requestData = XposedHelpers.getObjectField(model, "b")
                        val requestDataString = XposedHelpers.callMethod(requestData, "writeJson")
                        logI("examId: $examIdString")
                        logI("requestData: $requestDataString")
                    }.onFailure {
                        logI(it)
                    }
                }
            })

        val simpleWebActivityCompanionClass =
            XposedHelpers.findClass("${Classname.SIMPLE_WEB_APP_FIREWORK_ACTIVITY}\$a", classLoader)
        XposedBridge.hookAllMethods(simpleWebActivityCompanionClass, "a", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!hostPrefs.getBoolean("auto_practice", true) || !hostPrefs.getBoolean("auto_practice_cyclic", false)) {
                    return
                }
                val activity = param.args[0] as? Activity ?: return
                if (exerciseResultActivityClass.isInstance(activity)) {
                    val interval = runCatching {
                        Integer.parseInt(hostPrefs.getString("auto_practice_cyclic_interval", "1500")!!)
                    }.getOrElse { 1500 }
                    handler.postDelayed({
                        if (!activity.isDestroyed && !activity.isFinishing) {
                            kotlin.runCatching {
                                activity.findViewById<View>(activity.resources.getIdentifier("menu_button_again_btn", "id", activity.packageName)).performClick()
                            }.onFailure {
                                logI(it)
                            }
                        }
                    }, interval.toLong())
                    param.result = null
                }
            }
        })
    }

    private fun hookRecognizer(classLoader: ClassLoader) {
        val mathScriptRecognizerClass =
            XposedHelpers.findClass(Classname.MATH_SCRIPT_RECOGNIZER, classLoader)
        XposedHelpers.findAndHookMethod(
            mathScriptRecognizerClass,
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
        val baseWebAppClass = XposedHelpers.findClass(Classname.BASE_WEB_APP, classLoader)
        val simpleWebAppFireworkClass =
            XposedHelpers.findClass(Classname.SIMPLE_WEB_APP_FIREWORK_ACTIVITY, classLoader)
        val webViewField =
            simpleWebAppFireworkClass.fields.firstOrNull { it.type == baseWebAppClass }
        var webView: WeakReference<View>? = null
        val loadUrl = baseWebAppClass.methods.firstOrNull {
            it.name == "loadUrl" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }
        val addJavascriptInterface = baseWebAppClass.methods.firstOrNull {
            it.name == "addJavascriptInterface"
        }
        XposedHelpers.findAndHookMethod(
            simpleWebAppFireworkClass,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
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
                            addJavascriptInterface?.invoke(
                                param.thisObject,
                                this@XposedInit,
                                "AutoOral"
                            )
                            XposedBridge.invokeOriginalMethod(
                                param.method,
                                param.thisObject,
                                arrayOf("javascript: (function() { let backup_log=console.log;console.log=function(){if(arguments.length>=1){let l=arguments[0];window.AutoOral&&window.AutoOral.log(typeof l===l?l:JSON.stringify(l))}return backup_log(arguments)}; })();")
                            )
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
                                    it,
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
                                    AutoAnswerMode.QUICK -> moduleRes.assets.open("js/quick.js")
                                        .bufferedReader().use { it.readText() }

                                    AutoAnswerMode.CUSTOM -> hostPrefs.getString(
                                        "custom_answer_config",
                                        ""
                                    )!!

                                    AutoAnswerMode.STANDARD -> moduleRes.assets.open("js/standard.js")
                                        .bufferedReader().use { it.readText() }

                                    AutoAnswerMode.DISABLE -> ""
                                }
                                if (jsCode.isEmpty()) {
                                    logI("自动答题配置: ${mode.value}")
                                } else {
                                    try {
                                        XposedBridge.invokeOriginalMethod(
                                            loadUrl,
                                            it,
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
        )
    }
}