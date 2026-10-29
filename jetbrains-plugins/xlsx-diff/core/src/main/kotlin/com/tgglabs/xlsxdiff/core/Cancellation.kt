package com.tgglabs.xlsxdiff.core

/**
 * How the caller asks the comparison to stop.
 *
 * The IDE shows a progress bar with a "Cancel" button; when the user presses
 * it, the comparison must stop at once and *nothing* must be shown.
 *
 * The core does not know anything about the IDE: it only asks this object
 * from time to time whether it should stop.
 */
fun interface Cancellation {

    /** True when the caller wants the comparison to stop. */
    fun isCanceled(): Boolean

    companion object {
        /** A cancellation that never happens. */
        val NEVER: Cancellation = Cancellation { false }
    }
}
