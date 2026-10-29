package com.tgglabs.xlsxdiff.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.vfs.VirtualFile
import com.tgglabs.xlsxdiff.core.DiffResult

/**
 * The comparison the user has just made.
 *
 * The plugin has **one** function: compare two `.xlsx` files and show what
 * changed. The result is kept here so that the "Export CSV…" action can save
 * it without comparing the two files a second time (and without keeping the
 * IDE busy again).
 *
 * It is a *light* service (the `@Service` annotation): it needs no
 * declaration in `plugin.xml`, and the IDE throws it away with the project.
 */
@Service(Service.Level.PROJECT)
class DiffSession {

    /** The last comparison, or null when the user has not compared anything yet. */
    var result: DiffResult? = null
        private set

    /** The read-only report opened in the editor, when there is one. */
    var report: VirtualFile? = null
        private set

    /**
     * Keeps the result of a comparison, and the report that shows it.
     *
     * @param result the differences that were found.
     * @param report the file opened in the editor, or null when it could not
     *        be opened (the differences are then only in [result]).
     */
    fun remember(result: DiffResult, report: VirtualFile?) {
        this.result = result
        this.report = report
    }

    /** Forgets the last comparison (nothing to export any more). */
    fun clear() {
        result = null
        report = null
    }
}
