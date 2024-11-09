package cn.tinyhai.auto_oral_calculation.api

import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object OralApiService {

    private class ContinuationProxy(
        private val coroutineContext: Any,
        private val onResume: (Result<Any>) -> Unit
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            when (method.name) {
                "getContext" -> {
                    return coroutineContext
                }

                "resumeWith" -> {
                    val result = args?.get(0)
                    val ret = when {
                        result == null -> {
                            Result.failure(NullPointerException("result is null"))
                        }

                        result::class.java.name == "kotlin.Result\$Failure" -> {
                            Result.failure(
                                XposedHelpers.getObjectField(
                                    result,
                                    "exception"
                                ) as Throwable
                            )
                        }

                        result::class.java.name == "kotlin.Result" -> {
                            Result.success(XposedHelpers.getObjectField(result, "value"))
                        }

                        else -> {
                            Result.success(result)
                        }
                    }
                    onResume(ret)

                    return null
                }
            }
            return null
        }
    }

    private lateinit var apiService: Any

    private lateinit var getExamInfoMethod: Method

    private lateinit var uploadExamResultMethod: Method

    private lateinit var coroutineContext: Any

    private lateinit var keyPointId: String

    private lateinit var limit: String

    fun init(apiService: Any) {
        this.apiService = apiService
        uploadExamResultMethod = apiService::class.java.declaredMethods.first {
            it.name == "uploadExamResult" && it.parameterCount == 3
        }
        getExamInfoMethod = apiService::class.java.declaredMethods.first {
            it.name == "getExamInfo" && it.parameterCount == 3
        }
    }

    fun setup(coroutineContext: Any, keyPointId: String, limit: String) {
        this.coroutineContext = coroutineContext
        this.keyPointId = keyPointId
        this.limit = limit
    }

    fun getExamInfo(onResult: (Result<Any>) -> Unit) {
        val coroutineClass = getExamInfoMethod.parameterTypes[2]
        val getExamInfoProxy = Proxy.newProxyInstance(
            coroutineClass.classLoader,
            arrayOf(coroutineClass),
            ContinuationProxy(coroutineContext, onResult)
        )
        getExamInfoMethod.invoke(apiService, keyPointId, limit, getExamInfoProxy)
    }

    fun uploadExamResult(examId: String, requestData: Any, onResult: (Result<Any>) -> Unit) {
        val coroutineClass = uploadExamResultMethod.parameterTypes[2]
        val uploadExamResultProxy = Proxy.newProxyInstance(
            coroutineClass.classLoader, arrayOf(coroutineClass), ContinuationProxy(coroutineContext, onResult)
        )
        uploadExamResultMethod.invoke(apiService, examId, requestData, uploadExamResultProxy)
    }
}