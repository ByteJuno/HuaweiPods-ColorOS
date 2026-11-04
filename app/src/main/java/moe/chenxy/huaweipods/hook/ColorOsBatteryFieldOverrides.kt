package moe.chenxy.huaweipods.hook

/** 只撤回仍由模块持有的 DTO 字段；宿主后来写入的新对象不应被旧快照覆盖。 */
internal class ColorOsBatteryFieldOverrides {
    private val originals = mutableMapOf<String, Any?>()
    private val applied = mutableMapOf<String, Any?>()

    fun apply(values: Map<String, Any?>, read: (String) -> Any?, write: (String, Any?) -> Unit) {
        restore(read, write)
        values.forEach { (field, value) ->
            originals[field] = read(field)
            write(field, value)
            applied[field] = value
        }
    }

    fun restore(read: (String) -> Any?, write: (String, Any?) -> Unit) {
        applied.forEach { (field, value) ->
            if (read(field) === value) write(field, originals[field])
        }
        applied.clear()
        originals.clear()
    }
}
