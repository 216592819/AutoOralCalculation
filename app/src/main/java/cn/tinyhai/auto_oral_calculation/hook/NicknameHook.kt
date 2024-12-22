package cn.tinyhai.auto_oral_calculation.hook

import cn.tinyhai.auto_oral_calculation.PATTERN_NICKNAME
import cn.tinyhai.auto_oral_calculation.util.Common
import java.nio.charset.Charset
import java.util.regex.Pattern

class NicknameHook : BaseHook() {
    override val name: String
        get() = "NicknameHook"

    override fun startHook() {
        Pattern::class.java.findConstructor(String::class.java, Int::class.javaPrimitiveType!!).before { param ->
            if (Common.removeRestrictionOnNickname && param.args[0] == PATTERN_NICKNAME) {
                param.args[0] = "[\\S]*"
            }
        }
        val gbk = Charset.forName("GBK")
        String::class.java.findMethod("getBytes", Charset::class.java).after { param ->
            if (!Common.doubleNicknameLength || param.args[0] != gbk) {
                return@after
            }
            (param.result as? ByteArray)?.let {
                param.result = it.copyOf(it.size / 2)
            }
        }
    }
}