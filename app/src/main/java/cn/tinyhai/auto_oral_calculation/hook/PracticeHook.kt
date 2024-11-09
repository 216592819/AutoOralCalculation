package cn.tinyhai.auto_oral_calculation.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ProgressBar
import android.widget.TextView
import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.api.OralApiService
import cn.tinyhai.auto_oral_calculation.util.Practice
import cn.tinyhai.auto_oral_calculation.util.logI
import cn.tinyhai.auto_oral_calculation.util.mainHandler
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
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
        ThreadPoolExecutor(0, 5, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(10), DiscardPolicy())
    }

    private lateinit var presenterRef: WeakReference<Any>

    private val presenter get() = presenterRef.get()

    private val strokes: List<Array<PointF>> get() {
        var x = Random.nextInt(233, 666) + Random.nextFloat() * 100
        var y = Random.nextInt(233, 666) + Random.nextFloat() * 100
        val deltaX = 22.222f
        val deltaY = 22.222f
        val list = ArrayList<Array<PointF>>()
        val points = arrayListOf(PointF(x, y))
        repeat(4) {
            x += deltaX
            y += deltaY
            points.add(PointF(x, y))
        }
        repeat(8) {
            x += deltaX
            y -= deltaY
            points.add(PointF(x, y))
        }
        list.add(points.toTypedArray())
        return list
    }

    private val strokesJson: String get() {
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
        return jsonArray.toString()
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
        quickExercisePresenterClass.declaredMethods.first {
            it.parameterCount == 1 && List::class.java.isAssignableFrom(it.parameterTypes[0])
        }.after { param ->
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
                    dataList.subList(1, dataList.size - 1).forEachIndexed { index, data ->
                        val answers =
                            XposedHelpers.getObjectField(data, "rightAnswers") as? List<*>
                        answers?.let {
                            if (it.isNotEmpty()) {
                                XposedHelpers.callMethod(data, "setUserAnswer", it[0])
                            }
                        }
                        val costTime = if (index == 0) {
                            2000 + Random.nextInt(200, 800)
                        } else {
                            Random.nextInt(200, 800)
                        }
                        XposedHelpers.callMethod(data, "setCostTime", costTime)
                        XposedHelpers.callMethod(data, "setStrokes", strokes)
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

        hookSimpleWebActivityCompanion()
    }

    private fun showEditAlertDialog(context: Context, onConfirm: (Int) -> Unit) {
        val editText = EditText(context)
        editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
        editText.filters = arrayOf(InputFilter.LengthFilter(9))

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                16f,
                context.resources.displayMetrics
            ).toInt()
            setPaddingRelative(padding, padding, padding, 0)
            addView(editText)
        }

        val dialog = AlertDialog.Builder(context)
            .setPositiveButton(android.R.string.ok) { d, _ ->
                val targetCount =
                    kotlin.runCatching { editText.text.toString().toInt() }.getOrElse { 0 }
                onConfirm(targetCount)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setTitle("请输入练习次数")
            .setView(container)
            .show()
        val positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        positiveButton.isEnabled = false
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                positiveButton.isEnabled = s.isNotEmpty()
            }
        })
    }

    @SuppressLint("SetTextI18n")
    private fun showProgressDialog(context: Context, onDismiss: () -> Unit): (Int, Int) -> Unit {
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
        val textView = TextView(context).apply {
            text = "0/0"
            textSize = 16f
            setTextColor(Color.rgb(0x33, 0x33, 0x33))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setHorizontalGravity(Gravity.END)
            val padding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                16f,
                context.resources.displayMetrics
            ).toInt()
            setPaddingRelative(padding, padding, padding, 0)
            addView(progressBar)
            addView(textView)
        }
        val dialog = AlertDialog
            .Builder(context)
            .setTitle("练习进度")
            .setView(container)
            .setNegativeButton("停止", null)
            .setCancelable(false)
            .setOnDismissListener {
                onDismiss()
            }
            .show()
        return { current, target ->
            mainHandler.post {
                val progress = (100 * (current / target.toFloat())).toInt().coerceIn(0, 100)
                progressBar.setProgress(progress, true)
                textView.text = "$current/$target"
                if (progress >= 100) {
                    dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text = "完成"
                }
            }
        }
    }

    private fun testDelay() {
        thread {
            var delay = 300
            val lock = ReentrantLock()
            val condition = lock.newCondition()
            var active = true
            var count = 0
            while (active) {
                lock.withLock {
                    condition.await(delay.toLong(), TimeUnit.MILLISECONDS)
                    OralApiService.getExamInfo {
                        lock.withLock {
                            if (it.isSuccess) {
                                count++
                                if (count >= 5) {
                                    active = false
                                }
                                logI("delay: $delay")
                            } else {
                                count = 0
                                delay += 100
                                condition.signalAll()
                            }
                        }
                    }
                    condition.await()
                }
            }
        }
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
                showEditAlertDialog(activity as Context) { targetCount ->
                    val onProgressChange = showProgressDialog(activity) {
                        helper?.stopHonor()
                    }
                    helper = HonorHelper(targetCount, onProgressChange).also {
                        it.startHonor()
                    }
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
        private val targetCount: Int = Int.MAX_VALUE,
        private val onProgress: (Int, Int) -> Unit
    ) {
        private val lock = ReentrantLock()

        private val getExamInfoCondition = lock.newCondition()

        private var successCount: Int = 0

        @Volatile
        private var active: Boolean = true

        private var thread: Thread? = null

        private var lastReqTime: Long = 0L

        fun stopHonor() {
            active = false
            thread?.interrupt()
        }

        fun startHonor() {
            if (targetCount <= 0) {
                stopHonor()
                return
            }
            thread = thread {
                var waitTime = 800L
                while (active && !Thread.interrupted()) {
                    try {
                        lock.withLock {
                            if (successCount >= targetCount) {
                                stopHonor()
                                return@thread
                            }
                            lastReqTime = SystemClock.elapsedRealtime()
                            OralApiService.getExamInfo { result ->
                                lock.withLock {
                                    result.onSuccess {
                                        logI("get exam elapsed: ${SystemClock.elapsedRealtime() - lastReqTime}")
                                        handleExamVO(it)
                                    }.onFailure {
                                        logI(it)
                                        waitTime += 50
                                        logI("waitTime: $waitTime")
                                        getExamInfoCondition.signalAll()
                                    }
                                }
                            }
                            getExamInfoCondition.await(waitTime, TimeUnit.MILLISECONDS)
                            val elapsed = SystemClock.elapsedRealtime() - lastReqTime
                            if (elapsed < waitTime) {
                                getExamInfoCondition.await(waitTime - elapsed, TimeUnit.MILLISECONDS)
                            }
                        }
                    } catch (_: InterruptedException) {
                    }
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

            val runnable = object : Runnable {
                override fun run() {
                    lock.withLock {
                        lastReqTime = SystemClock.elapsedRealtime()
                    }
                    OralApiService.uploadExamResult(examId, examVO) {
                        lock.withLock {
                            if (it.isSuccess) {
                                successCount += 1
                                getExamInfoCondition.signalAll()
                            }
                        }
                        it.onFailure {
                            logI(it)
                            executor.execute(this)
                        }.onSuccess {
                            logI("upload elapsed: ${SystemClock.elapsedRealtime() - lastReqTime}")
                            onProgress(successCount, targetCount)
                        }
                    }
                }
            }
            runnable.run()
        }
    }
}