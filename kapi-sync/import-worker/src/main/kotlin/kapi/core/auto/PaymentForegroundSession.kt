package kapi.core.auto

/** Receives only a confirmed application foreground, never an overlay or keyboard. */
class PaymentForegroundSession {
    private var foreground: String? = null
    fun observe(application: String?): Boolean {
        if (application == null || application == foreground) return false
        val changed = foreground != null
        foreground = application
        return changed
    }
}
