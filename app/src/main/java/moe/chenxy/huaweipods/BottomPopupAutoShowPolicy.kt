package moe.chenxy.huaweipods

/** Accessed under the controller's session lock. Permission belongs to a connection, not a callback. */
internal class BottomPopupAutoShowPolicy {
    private var generation: Long? = null
    private var allowed = false
    private var shown = false

    fun beginSession(generation: Long, isSessionRestore: Boolean) {
        if (this.generation == generation) return
        this.generation = generation
        allowed = !isSessionRestore
        shown = false
    }

    fun isAllowed(generation: Long): Boolean = this.generation == generation && allowed

    fun claim(generation: Long, enabled: Boolean): Boolean {
        if (!enabled || !isAllowed(generation) || shown) return false
        shown = true
        return true
    }
}
