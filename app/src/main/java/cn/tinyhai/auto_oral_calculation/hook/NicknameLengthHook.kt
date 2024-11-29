package cn.tinyhai.auto_oral_calculation.hook

import cn.tinyhai.auto_oral_calculation.Classname
import cn.tinyhai.auto_oral_calculation.util.Common

class NicknameLengthHook : BaseHook() {
    override fun startHook() {
        val verifyUtilClass = findClass(Classname.VERIFY_UTIL_4_96_0)
        verifyUtilClass.declaredMethods.first {
            it.returnType == Int::class.javaPrimitiveType && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }.after { param ->
            if (!Common.doubleNicknameLength) {
                return@after
            }
            val result = param.result
            if (result is Int) {
                param.result = (result - 1).coerceAtLeast(0) / 2
            }
        }
    }
}