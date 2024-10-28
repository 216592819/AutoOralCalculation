package cn.tinyhai.auto_oral_calculation.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.view.View
import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.util.hostPrefs
import cn.tinyhai.auto_oral_calculation.util.logI
import cn.tinyhai.auto_oral_calculation.util.mainHandler
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import kotlin.random.Random

class PracticeHook : BaseHook() {

    private inner class ExerciseGeneralModelWrapper(model: Any) {
        private val getExerciseType: Method
        private val buildUri: Method
        private val gotoResult: Method

        init {
            val exerciseTypeClass = findClass(Classname.EXERCISE_TYPE)
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

    private lateinit var presenterRef: WeakReference<Any>

    private val presenter get() = presenterRef.get()

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

    override fun startHook() {
        val quickExercisePresenterClass = findClass(Classname.PRESENTER)
        val startExercise = quickExercisePresenterClass.findMethod("c")
        val getAnswers = quickExercisePresenterClass.findMethod("g")
        val commitAnswer =
            quickExercisePresenterClass.findMethod("e", String::class.java, List::class.java)

        val nextQuestion = quickExercisePresenterClass.findMethod(
            "d",
            Boolean::class.javaPrimitiveType!!,
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
        quickExercisePresenterClass.findMethod("N").after {
            if (hostPrefs.getBoolean("auto_practice", true)) {
                mainHandler.post(performNext)
            }
        }

        val quickExerciseActivityClass = findClass(Classname.QUICK_EXERCISE_ACTIVITY)
        val modelClass =
            (quickExerciseActivityClass.genericSuperclass as ParameterizedType).actualTypeArguments.getOrNull(
                1
            )
        val getGeneralModel =
            quickExerciseActivityClass.declaredMethods.firstOrNull { it.returnType == modelClass && it.parameterCount == 0 }
                ?.also { it.isAccessible = true }
        var modelWrapper: ExerciseGeneralModelWrapper? = null
        // afterLoadFinish
        quickExercisePresenterClass.findMethod("P", List::class.java).after { param ->
            presenterRef = WeakReference(param.thisObject)
            if (!hostPrefs.getBoolean("auto_practice", true)) {
                return@after
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
                        val answers =
                            XposedHelpers.getObjectField(data, "rightAnswers") as? List<*>
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
                    val wrapper = modelWrapper
                        ?: ExerciseGeneralModelWrapper(model).also { modelWrapper = it }
                    val exerciseType = wrapper.getExerciseType(model)
                    val exerciseTypeInt =
                        XposedHelpers.getIntField(exerciseType, "exerciseType")
                    val intent = activity.intent
                    val uri = wrapper.buildUri(model, totalTime.toLong(), dataList) as Uri
                    wrapper.gotoResult(model, activity, intent, uri, exerciseTypeInt)
                    activity.finish()
                }.onFailure {
                    logI(it)
                }
            } else {
                mainHandler.post(performNext)
            }
        }

        val exerciseResultActivityClass =
            findClass(Classname.EXERCISE_RESULT_ACTIVITY)
        val exerciseResultActivityCompanionClass =
            findClass("${Classname.EXERCISE_RESULT_ACTIVITY}\$a")

        exerciseResultActivityClass.findMethod("onCreate", Bundle::class.java).after { param ->
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

        val simpleWebActivityCompanionClass =
            findClass("${Classname.SIMPLE_WEB_APP_FIREWORK_ACTIVITY}\$a")

        simpleWebActivityCompanionClass.allMethod("a").before { param ->
            if (!hostPrefs.getBoolean(
                    "auto_practice",
                    true
                ) || !hostPrefs.getBoolean("auto_practice_cyclic", false)
            ) {
                return@before
            }
            val activity = param.args[0] as? Activity ?: return@before
            if (exerciseResultActivityClass.isInstance(activity)) {
                val interval = runCatching {
                    Integer.parseInt(
                        hostPrefs.getString(
                            "auto_practice_cyclic_interval",
                            "1500"
                        )!!
                    )
                }.getOrElse { 1500 }
                mainHandler.postDelayed({
                    if (!activity.isDestroyed && !activity.isFinishing) {
                        kotlin.runCatching {
                            activity.findViewById<View>(
                                activity.resources.getIdentifier(
                                    "menu_button_again_btn",
                                    "id",
                                    activity.packageName
                                )
                            ).performClick()
                        }.onFailure {
                            logI(it)
                        }
                    }
                }, interval.toLong())
                param.result = null
            }
        }
    }
}