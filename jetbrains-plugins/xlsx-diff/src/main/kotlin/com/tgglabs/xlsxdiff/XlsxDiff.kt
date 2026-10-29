package com.tgglabs.xlsxdiff

import com.tgglabs.xlsxdiff.core.Cancellation
import com.tgglabs.xlsxdiff.core.CompareTask
import com.tgglabs.xlsxdiff.core.DiffResult
import java.nio.file.Path

/**
 * Entry point of the plugin.
 *
 * The comparison itself lives in the `core` module; this object only adapts
 * it to what the IDE needs.
 */
object XlsxDiff {

    /**
     * Compares two `.xlsx` files, cell by cell.
     *
     * @throws com.tgglabs.xlsxdiff.core.XlsxDiffException when a file cannot be read.
     */
    fun compare(left: Path, right: Path): DiffResult = compare(left, right, Cancellation.NEVER)!!

    /**
     * Compares two `.xlsx` files, stopping as soon as [cancellation] says so.
     *
     * @return the differences found, or null when the comparison was cancelled:
     *         in that case nothing must be shown to the user.
     */
    fun compare(left: Path, right: Path, cancellation: Cancellation): DiffResult? =
        CompareTask.run(left, right, cancellation)
}
