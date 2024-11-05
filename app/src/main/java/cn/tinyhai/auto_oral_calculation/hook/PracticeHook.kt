package cn.tinyhai.auto_oral_calculation.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.api.OralApiService
import cn.tinyhai.auto_oral_calculation.util.Practice
import cn.tinyhai.auto_oral_calculation.util.logI
import cn.tinyhai.auto_oral_calculation.util.mainHandler
import de.robv.android.xposed.XC_MethodHook.Unhook
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
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

    private val executor: ExecutorService by lazy {
        ThreadPoolExecutor(0, 5, 30, TimeUnit.SECONDS, LinkedBlockingQueue(30), DiscardPolicy())
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

    private val strokesJson by lazy {
        val jsonArray = JSONArray()
        strokes.forEach {
            val arr = JSONArray()
            it.forEach { point ->
                val p = JSONObject()
                p.put("x", point.x)
                p.put("y", point.y)
                arr.put(p)
            }
            jsonArray.put(arr)
        }
        jsonArray.toString()
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
            if (Practice.autoPractice) {
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
            if (Practice.autoPractice) {
                mainHandler.post(performNext)
            }
        }

        val quickExerciseActivityClass = findClass(Classname.QUICK_EXERCISE_ACTIVITY)
        hookQuickExerciseActivity(quickExerciseActivityClass)

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
            if (!Practice.autoPractice) {
                return@after
            }
            if (Practice.autoPracticeQuick) {
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

        hookApiServiceCompanion()

        hookSimpleWebActivityCompanion()
    }

    private fun hookApiServiceCompanion() {
        val oralApiServiceCompanionClass = findClass("${Classname.ORAL_API_SERVICE}\$a")
        val unhooks = arrayOf<Unhook?>(null)
        oralApiServiceCompanionClass.findMethod("a").after { param ->
            OralApiService.init(param.result)
            unhooks.forEach { it?.unhook() }
        }.also { unhooks[0] = it }
    }

    private fun hookQuickExerciseActivity(quickExerciseActivityClass: Class<*>) {
        val lifecycleOwnerKtClass = findClass(Classname.LIFECYCLE_OWNER_KT)
        val modelClass =
            (quickExerciseActivityClass.genericSuperclass as ParameterizedType).actualTypeArguments.getOrNull(
                1
            )
        val getGeneralModel =
            quickExerciseActivityClass.declaredMethods.firstOrNull { it.returnType == modelClass && it.parameterCount == 0 }
                ?.also { it.isAccessible = true }

        var helper: HonorHelper? = null
        quickExerciseActivityClass.findMethod("onCreate", Bundle::class.java).after { param ->
            val activity = param.thisObject
            val scope =
                XposedHelpers.callStaticMethod(lifecycleOwnerKtClass, "getLifecycleScope", activity)
            val coroutineContext = XposedHelpers.callMethod(scope, "getCoroutineContext")
            val model = getGeneralModel?.invoke(activity) ?: return@after
            val keyPointId = XposedHelpers.getIntField(model, "a").toString()
            val limit = XposedHelpers.getIntField(model, "c").toString()

            OralApiService.setup(coroutineContext, keyPointId, limit)

            if (Practice.autoHonor) {
                helper = HonorHelper(WeakReference(activity as Context)).also {
                    it.startHonor()
                }
            }
        }

        quickExerciseActivityClass.findMethod("onDestroy").before {
            helper?.stopHonor()
        }
    }

    private fun hookSimpleWebActivityCompanion() {
        val exerciseResultActivityClass =
            findClass(Classname.EXERCISE_RESULT_ACTIVITY)
        val simpleWebActivityCompanionClass =
            findClass("${Classname.SIMPLE_WEB_APP_FIREWORK_ACTIVITY}\$a")

        simpleWebActivityCompanionClass.allMethod("a").before { param ->
            if (!Practice.autoPracticeCyclic) {
                return@before
            }
            val activity = param.args[0] as? Activity ?: return@before
            if (exerciseResultActivityClass.isInstance(activity)) {
                val interval = Practice.autoPracticeCyclicInterval
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

    private inner class HonorHelper(
        private val contextRef: WeakReference<Context>
    ) {
        private val count: AtomicLong = AtomicLong(0)

        private val semaphore: Semaphore = Semaphore(5)

        private var lastTimeMillis: Long = 0

        @Volatile
        private var active: Boolean = true

        private var thread: Thread? = null

        private fun toastIfNeeded() {
            when {
                lastTimeMillis == 0L -> {
                    lastTimeMillis = System.currentTimeMillis()
                    mainHandler.post {
                        contextRef.get()?.let {
                            Toast.makeText(it, "自动上分已启动", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                System.currentTimeMillis() - lastTimeMillis > 5000 -> {
                    lastTimeMillis = System.currentTimeMillis()
                    mainHandler.post {
                        contextRef.get()?.let {
                            Toast.makeText(it, "已练习${count}次", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        fun stopHonor() {
            active = false
            thread?.interrupt()
        }

        fun startHonor() {
            thread = thread {
                while (active && !Thread.interrupted()) {
                    toastIfNeeded()
                    try {
                        semaphore.acquire()
                        OralApiService.getExamInfo { result ->
                            semaphore.release()
                            result.onSuccess {
                                handleExamVO(it)
                            }.onFailure {
                                logI(it)
                            }
                        }
                    } catch (_: InterruptedException) {}
                }
            }
        }

        private fun handleExamVO(examVO: Any) {
            if (!active) {
                return
            }
            executor.execute {
                kotlin.runCatching {
                    buildAndUploadExamResult(examVO)
                }.onFailure {
                    logI(it)
                }
            }
        }

        private fun buildAndUploadExamResult(examVO: Any) {
            val examId = XposedHelpers.getObjectField(examVO, "idString").toString()
            val questions = XposedHelpers.getObjectField(examVO, "questions") as List<*>
            var totalTime = 0L
            questions.forEach {
                val answers =
                    XposedHelpers.getObjectField(it, "answers") as? List<*>
                if (!answers.isNullOrEmpty()) {
                    XposedHelpers.callMethod(it, "setUserAnswer", answers[0])
                }
                val costTime = Random.nextInt(233, 2333)
                XposedHelpers.callMethod(it, "setCostTime", costTime)
                XposedHelpers.callMethod(it, "setScript", strokesJson)
                XposedHelpers.callMethod(it, "setStatus", 1)
                totalTime += costTime
            }
            val questionCnt = XposedHelpers.getIntField(examVO, "questionCnt")
            XposedHelpers.callMethod(examVO, "setCorrectCnt", questionCnt)
            XposedHelpers.callMethod(examVO, "setCostTime", totalTime)
            OralApiService.uploadExamResult(examId, examVO) {
                it.onFailure {
                    logI(it)
                }.onSuccess {
                    count.incrementAndGet()
                }
            }
        }
    }
}