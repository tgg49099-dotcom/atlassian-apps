package com.tgglabs.xlsxdiff.core

import java.nio.file.Path

/**
 * 1-based coordinates of a cell inside a sheet.
 */
data class CellRef(val sheet: String, val row: Int, val column: Int) {
    /** Excel-style column name: 1 -> A, 27 -> AA. */
    val columnName: String get() = columnToName(column)

    /** Excel-style address, e.g. `B12`. */
    val address: String get() = "$columnName$row"

    override fun toString(): String = "$sheet!$address"
}

internal fun columnToName(column: Int): String {
    require(column >= 1) { "column must be >= 1 but was $column" }
    var n = column
    val sb = StringBuilder()
    while (n > 0) {
        val remainder = (n - 1) % 26
        sb.append(('A' + remainder))
        n = (n - 1) / 26
    }
    return sb.reverse().toString()
}

/** What changed inside a single cell. */
enum class CellChange {
    /** The value shown in the cell is different. */
    VALUE,

    /** The formula behind the cell is different. */
    FORMULA,

    /** The kind of content is different (number vs. text vs. boolean...). */
    TYPE,

    /** The cell is empty on the left file and filled on the right file. */
    ADDED,

    /** The cell is filled on the left file and empty on the right file. */
    REMOVED,
}

/** What happened to a whole row. */
enum class RowChange {
    /** The row exists only in the right file. */
    ADDED,

    /** The row exists only in the left file. */
    REMOVED,

    /** The row exists in both files but at least one cell differs. */
    MODIFIED,
}

/** A sheet that exists in only one of the two files. */
enum class SheetChange {
    ONLY_LEFT,
    ONLY_RIGHT,
}

/**
 * A difference found in one cell.
 */
data class CellDiff(
    val ref: CellRef,
    val changes: Set<CellChange>,
    val leftValue: String?,
    val rightValue: String?,
    val leftFormula: String?,
    val rightFormula: String?,
    val leftType: String?,
    val rightType: String?,
) {
    val isAddition: Boolean get() = changes.contains(CellChange.ADDED)
    val isRemoval: Boolean get() = changes.contains(CellChange.REMOVED)
}

/**
 * A difference found in one row.
 *
 * For [RowChange.MODIFIED] rows, [cells] holds the actual cell-level differences.
 * For added/removed rows, [cells] describes the content of the row that appeared
 * or disappeared.
 */
data class RowDiff(
    val sheet: String,
    val row: Int,
    val change: RowChange,
    val cells: List<CellDiff> = emptyList(),
) {
    /** Number of cells that really differ (only meaningful for modified rows). */
    val modifiedCellCount: Int get() = if (change == RowChange.MODIFIED) cells.size else 0
}

/**
 * The differences found in one sheet.
 */
data class SheetDiff(
    val name: String,
    val change: SheetChange? = null,
    val rows: List<RowDiff> = emptyList(),
    val leftColumnCount: Int = 0,
    val rightColumnCount: Int = 0,
) {
    /** All the cell-level differences of this sheet. */
    val cells: List<CellDiff> get() = rows.flatMap { it.cells }

    /** True when the sheet is present in only one of the two files. */
    val isExclusive: Boolean get() = change != null
}

/**
 * The complete result of comparing two workbooks.
 */
data class DiffResult(
    val leftPath: Path,
    val rightPath: Path,
    val sheets: List<SheetDiff>,
) {
    val hasDifferences: Boolean get() = sheets.isNotEmpty()

    /** Number of cells whose content really differs (added/removed rows excluded). */
    val modifiedCellCount: Int get() = sheets.sumOf { sheet -> sheet.rows.sumOf { it.modifiedCellCount } }

    /** Number of rows that differ. */
    val changedRowCount: Int get() = sheets.sumOf { it.rows.size }

    /** Number of sheets that exist in only one of the two files. */
    val exclusiveSheetCount: Int get() = sheets.count { it.isExclusive }

    /** All the cell-level differences, sheet by sheet. */
    val cells: List<CellDiff> get() = sheets.flatMap { it.cells }
}
