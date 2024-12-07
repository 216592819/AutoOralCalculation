package cn.tinyhai.auto_oral_calculation.hook

import cn.tinyhai.auto_oral_calculation.util.Common
import java.nio.charset.Charset

class NicknameLengthHook : BaseHook() {
    override val name: String
        get() = "NicknameLengthHook"

    override fun startHook() {
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