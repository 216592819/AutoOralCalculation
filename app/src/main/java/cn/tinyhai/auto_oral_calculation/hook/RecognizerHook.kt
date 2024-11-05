package cn.tinyhai.auto_oral_calculation.hook

import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.util.Common

class RecognizerHook : BaseHook() {
    override fun startHook() {
        val mathScriptRecognizerClass = findClass(Classname.MATH_SCRIPT_RECOGNIZER)
        mathScriptRecognizerClass.findMethod(
            "a",
            Int::class.javaPrimitiveType!!,
            List::class.java,
            List::class.java
        ).before { param ->
            if (!Common.alwaysTrue) {
                return@before
            }
            val answers = param.args[2] as List<*>
            param.result = if (answers.isNotEmpty()) {
                answers[0].toString()
            } else {
                ""
            }
        }
    }
}