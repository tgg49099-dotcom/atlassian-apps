package com.tgglabs.xlsxdiff.core

/**
 * The limits of a comparison.
 *
 * A `.xlsx` file is a ZIP archive of XML files: 20 MB on the disk can hold
 * 2 GB once decompressed, and the spreadsheet library loads the whole
 * workbook in memory. Without these limits, a big file can freeze the IDE.
 *
 * The limits are checked in this order, and **before** the workbook is
 * opened, so that a refused file costs nothing:
 *
 *  - the size of the file on the disk ([maxFileBytes]);
 *  - the total size of the entries of the ZIP once decompressed
 *    ([maxUncompressedBytes]);
 *  - the number of cells that are compared ([maxCells]).
 */
data class XlsxDiffLimits(
    /** Maximum size of a file on the disk, in bytes. */
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    /** Maximum total size of the ZIP entries once decompressed, in bytes. */
    val maxUncompressedBytes: Long = DEFAULT_MAX_UNCOMPRESSED_BYTES,
    /** Maximum number of cells that can be compared. */
    val maxCells: Long = DEFAULT_MAX_CELLS,
) {
    companion object {
        /** 20 MB on the disk (the limit the jury asked for). */
        const val DEFAULT_MAX_FILE_BYTES: Long = 20L * 1024 * 1024

        /**
         * 50 MB once decompressed.
         *
         * 27/10/2026 (jury) : 100 Mo de XML decompresse pouvait prendre plus
         * d'1 Go dans le tas de l'IDE. On descend a 50 Mo.
         */
        const val DEFAULT_MAX_UNCOMPRESSED_BYTES: Long = 50L * 1024 * 1024

        /** Two million cells. */
        const val DEFAULT_MAX_CELLS: Long = 2_000_000L
    }
}
