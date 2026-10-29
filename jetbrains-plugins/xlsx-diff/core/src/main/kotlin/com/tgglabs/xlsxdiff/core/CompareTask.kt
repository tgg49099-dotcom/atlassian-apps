package com.tgglabs.xlsxdiff.core

import java.nio.file.Path

/**
 * Runs a comparison and decides whether there is a result to show.
 *
 * The IDE asks this object to compare two files and to tell it whether the
 * comparison was cancelled. When it was, the answer is "no result": the
 * caller must not show anything at all.
 *
 * This object lives in `core` (and not in the IDE module) so that the rule
 * "a cancelled comparison shows nothing" can be tested without an IDE.
 */
object CompareTask {

    /**
     * Compares [left] and [right], cell by cell.
     *
     * @return the differences found, or **null** when the comparison was
     *         cancelled: in that case there is nothing to show.
     * @throws XlsxDiffException when a file cannot be read.
     */
    fun run(
        left: Path,
        right: Path,
        cancellation: Cancellation = Cancellation.NEVER,
    ): DiffResult? {
        val result = try {
            XlsxDiffEngine().diff(left, right, cancellation)
        } catch (e: XlsxDiffCancelledException) {
            // The user pressed "Cancel": no result, no error.
            return null
        }
        // The user may have cancelled while the very last row was compared.
        return if (cancellation.isCanceled()) null else result
    }
}
