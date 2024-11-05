package cn.tinyhai.auto_oral_calculation.hook

import cn.tinyhai.auto_oral_calculation.Classname

class MMKVHook : BaseHook() {

    companion object {
        var dateDelta: Long = 0
    }

    override fun startHook() {
        val mmkvClass = findClass(Classname.MMKV)
        mmkvClass.findMethod("encodeLong", Long::class.javaPrimitiveType!!, String::class.java, Long::class.javaPrimitiveType!!).after { param ->
            val key = param.args[1] as? String ?: return@after
            if (key == "time.delta") {
                dateDelta = param.args[2] as? Long ?: return@after
            }
        }
    }
}