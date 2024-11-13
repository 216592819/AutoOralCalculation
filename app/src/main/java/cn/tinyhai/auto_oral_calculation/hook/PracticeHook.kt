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
import cn.tinyhai.auto_oral_calculation.util.strokes
import cn.tinyhai.auto_oral_calculation.util.toJsonString
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor.DiscardPolicy
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

class PracticeHook : BaseHook() {

    private class ExerciseGeneralModelWrapper(modelClass: Class<*>, exerciseTypeClass: Class<*>) {
        private val getExerciseType: Method = modelClass.methods.first {
            it.returnType == exerciseTypeClass && it.parameterCount == 0
        }.also { it.isAccessible = true }

        private val buildUri: Method = modelClass.methods.first {
            it.returnType == Uri::class.java && it.parameterCount == 2
        }.also { it.isAccessible = true }

        private val gotoResult: Method = modelClass.methods.first {
            it.returnType == Void.TYPE && it.parameterCount > 1 && it.parameterTypes[0] == Context::class.java
        }.also { it.isAccessible = true }

        fun Any.getExerciseType(): Any? {
            return getExerciseType.invoke(this)
        }

        fun Any.buildUri(costTime: Long, dataList: List<*>): Any? {
            return buildUri.invoke(this, costTime, dataList)
        }

        fun Any.gotoResult(context: Context, intent: Intent, uri: Uri, exerciseType: Int) {
            gotoResult.invoke(this, context, intent, uri, exerciseType)
        }
    }

    private class QuickExercisePresenterWrapper(presenterClass: Class<*>) {
        private val startExercise: Method = presenterClass.declaredMethods.first { it.name == "c" }

        private val getAnswers: Method = presenterClass.declaredMethods.first { it.name == "g" }

        private val commitAnswer: Method = presenterClass.declaredMethods.first {
            it.name == "e" && it.parameterCount == 2 && it.parameterTypes[0] == String::class.java && it.parameterTypes[1] == List::class.java
        }

        private val nextQuestion: Method = presenterClass.declaredMethods.first {
            it.name == "d" && it.parameterCount == 2 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType && it.parameterTypes[1] == List::class.java
        }

        fun Any.startExercise() {
            startExercise.invoke(this)
        }

        fun Any.getAnswers(): List<*>? {
            return getAnswers.invoke(this) as? List<*>
        }

        fun Any.commitAnswer(answer: String) {
            commitAnswer.invoke(this, answer, null /*wrongScript*/)
        }

        fun Any.nextQuestion(autoJump: Boolean, strokes: List<Array<PointF>>) {
            nextQuestion.invoke(this, autoJump, strokes)
        }
    }

    private val executor: ScheduledExecutorService by lazy {
        ScheduledThreadPoolExecutor(5, DiscardPolicy())
    }

    private lateinit var presenterRef: WeakReference<Any>

    private val presenter get() = presenterRef.get()

    override fun startHook() {
        val quickExerciseActivityClass = findClass(Classname.QUICK_EXERCISE_ACTIVITY)

        hookQuickExerciseActivity(quickExerciseActivityClass)

        hookQuickExercisePresenter(quickExerciseActivityClass)

        hookSimpleWebActivityCompanion()
    }

    private fun hookQuickExercisePresenter(quickExerciseActivityClass: Class<*>) {
        val quickExercisePresenterClass = findClass(Classname.PRESENTER)
        val presenterWrapper = QuickExercisePresenterWrapper(quickExercisePresenterClass)
        val performNext = Runnable {
            if (Practice.autoPractice) {
                with(presenterWrapper) {
                    presenter?.run {
                        startExercise()
                        val answer = getAnswers()?.get(0).toString()
                        commitAnswer(answer)
                        nextQuestion(true, strokes.toList())
                    }
                }
            }
        }
        // afterAnimation
        quickExercisePresenterClass.findMethod("N").after {
            if (Practice.autoPractice) {
                mainHandler.post(performNext)
            }
        }

        val modelClass =
            (quickExerciseActivityClass.genericSuperclass as ParameterizedType).actualTypeArguments[1]
        val getGeneralModel =
            quickExerciseActivityClass.declaredMethods.first { it.returnType == modelClass && it.parameterCount == 0 }
                .also { it.isAccessible = true }
        val modelWrapper =
            ExerciseGeneralModelWrapper(modelClass as Class<*>, findClass(Classname.EXERCISE_TYPE))
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
                    val model = getGeneralModel.invoke(activity)
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
                        val costTime = Random.nextInt(150, 250)
                        XposedHelpers.callMethod(data, "setCostTime", costTime)
                        XposedHelpers.callMethod(data, "setStrokes", strokes)
                        totalTime += costTime
                    }
                    with(modelWrapper) {
                        model?.run {
                            val exerciseType = getExerciseType()
                            val exerciseTypeInt =
                                XposedHelpers.getIntField(exerciseType, "exerciseType")
                            val intent = activity.intent
                            val uri = buildUri(totalTime.toLong(), dataList) as Uri
                            gotoResult(activity, intent, uri, exerciseTypeInt)
                            activity.finish()
                        }
                    }
                }.onFailure {
                    logI(it)
                }
            } else {
                mainHandler.post(performNext)
            }
        }
    }

    private fun showEditAlertDialog(context: Context, onConfirm: (Int) -> Unit) {
        val editText = EditText(context)
        editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
        editText.filters = arrayOf(InputFilter.LengthFilter(9))

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
            ).toInt()
            setPaddingRelative(padding, padding, padding, 0)
            addView(editText)
        }

        val dialog = AlertDialog.Builder(context).setPositiveButton(android.R.string.ok) { d, _ ->
            val targetCount =
                kotlin.runCatching { editText.text.toString().toInt() }.getOrElse { 0 }
            onConfirm(targetCount)
        }.setNegativeButton(android.R.string.cancel, null).setTitle("请输入练习次数")
            .setView(container).show()
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
            LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
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
                TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics
            ).toInt()
            setPaddingRelative(padding, padding, padding, 0)
            addView(progressBar)
            addView(textView)
        }
        val dialog = AlertDialog.Builder(context).setTitle("练习进度").setView(container)
            .setNegativeButton("停止", null).setCancelable(false).setOnDismissListener {
                onDismiss()
            }.show()
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

    private fun testDelay(keyPointId: String, limit: Int) {
        thread {
            var delay = 800L
            var lastReqTime: Long
            val lock = ReentrantLock()
            val condition = lock.newCondition()
            var active = true
            var count = 0
            Thread.sleep(1000L)
            while (active) {
                lock.withLock {
                    lastReqTime = SystemClock.elapsedRealtime()
                    logI("req start with delay: $delay")
                    OralApiService.getExamInfo(keyPointId, limit) {
                        lock.withLock {
                            if (it.isSuccess) {
                                count++
                                if (count >= 30) {
                                    active = false
                                    logI("final delay: $delay")
                                }
                            } else {
                                count = 0
                                delay += 50
                            }
                            condition.signalAll()
                        }
                    }
                    condition.await(delay, TimeUnit.MILLISECONDS)
                    val elapsed = SystemClock.elapsedRealtime() - lastReqTime
                    if (elapsed < delay) {
                        condition.await(delay - elapsed, TimeUnit.MILLISECONDS)
                    }
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
            val limit = XposedHelpers.getIntField(model, "c")

            OralApiService.setup(coroutineContext)
            if (Practice.autoHonor) {
                showEditAlertDialog(activity as Context) { targetCount ->
                    val onProgressChange = showProgressDialog(activity) {
                        helper?.stopHonor()
                    }
                    helper = HonorHelper(keyPointId, limit, targetCount, onProgressChange).also {
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
        val exerciseResultActivityClass = findClass(Classname.EXERCISE_RESULT_ACTIVITY)
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
                                    "menu_button_again_btn", "id", activity.packageName
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
        private val keyPointId: String,
        private val limit: Int,
        private val targetCount: Int = Int.MAX_VALUE,
        private val onProgress: (Int, Int) -> Unit
    ) {
        private val lock = ReentrantLock()

        private val getExamInfoCondition = lock.newCondition()

        private var pendingCount: Int = 0

        private var successCount: Int = 0

        @Volatile
        private var active: Boolean = true

        private var workerThread: Thread? = null

        fun stopHonor() {
            active = false
            workerThread?.interrupt()
        }

        fun startHonor() {
            if (targetCount <= 0) {
                stopHonor()
                return
            }
            workerThread = thread {
                doWork()
            }
        }

        private fun doWork() {
            var lastReqTime: Long
            var waitTime = 33L
            while (active && !Thread.interrupted()) {
                try {
                    lock.withLock {
                        while (pendingCount > 0 && successCount + pendingCount >= targetCount) {
                            getExamInfoCondition.await()
                            if (successCount >= targetCount) {
                                stopHonor()
                                return@withLock
                            } else {
                                continue
                            }
                        }

                        pendingCount += 1

                        lastReqTime = SystemClock.elapsedRealtime()
                        OralApiService.getExamInfo(keyPointId, limit) { result ->
                            lock.withLock {
                                result.onSuccess {
                                    logI("get exam elapsed: ${SystemClock.elapsedRealtime() - lastReqTime}")
                                    handleExamVO(it)
                                }.onFailure {
                                    pendingCount -= 1
                                    if (it !is CancellationException) {
                                        waitTime += 1000
                                        logI("waitTime: $waitTime")
                                        logI("get exam failed: ${it.message}")
                                        getExamInfoCondition.signalAll()
                                    }
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

        private fun buildExamResult(examVO: Any): Pair<String, Long> {
            val examId = XposedHelpers.getObjectField(examVO, "idString").toString()
            val questions = XposedHelpers.getObjectField(examVO, "questions") as List<*>
            var totalTime = 0L
            questions.forEach {
                val answers = XposedHelpers.getObjectField(it, "answers") as? List<*>
                if (!answers.isNullOrEmpty()) {
                    XposedHelpers.callMethod(it, "setUserAnswer", answers[0])
                }
                val costTime = Random.nextInt(150, 250)
                XposedHelpers.callMethod(it, "setCostTime", costTime)
                XposedHelpers.callMethod(it, "setScript", strokes.toJsonString())
                XposedHelpers.callMethod(it, "setStatus", 1)
                totalTime += costTime
            }
            val questionCnt = XposedHelpers.getIntField(examVO, "questionCnt")
            XposedHelpers.callMethod(examVO, "setCorrectCnt", questionCnt)
            XposedHelpers.callMethod(examVO, "setCostTime", totalTime)
            return examId to totalTime
        }

        private fun buildAndUploadExamResult(examVO: Any) {
            val (examId, delay) = buildExamResult(examVO)
            val uploadReqTime = SystemClock.elapsedRealtime()
            val runnable = object : Runnable {
                override fun run() {
                    OralApiService.uploadExamResult(examId, examVO) {
                        val elapsed = SystemClock.elapsedRealtime() - uploadReqTime
                        lock.withLock {
                            it.onFailure {
                                if (it !is CancellationException) {
                                    logI("upload exam failed: ${it.message}")
                                    if (delay > elapsed) {
                                        executor.schedule(this, delay - elapsed, TimeUnit.MILLISECONDS)
                                    } else {
                                        pendingCount -= 1
                                        getExamInfoCondition.signalAll()
                                        logI("upload exam failed after delay: $delay")
                                    }
                                }
                            }.onSuccess {
                                successCount += 1
                                pendingCount -= 1
                                getExamInfoCondition.signalAll()
                                logI("upload exam elapsed: $elapsed")
                                onProgress(successCount, targetCount)
                            }
                        }
                    }
                }
            }
            runnable.run()
        }
    }
}