package com.tgglabs.xlsxdiff.core

/**
 * The kind of difference shown on one line of the diff table.
 */
enum class DiffKind(val label: String) {
    /** A cell exists in both files but does not hold the same content. */
    CELL_MODIFIED("Cell modified"),

    /** A row exists only in the right file. */
    ROW_ADDED("Row added"),

    /** A row exists only in the left file. */
    ROW_REMOVED("Row removed"),

    /** A sheet exists only in the left file. */
    SHEET_ONLY_LEFT("Sheet only in the left file"),

    /** A sheet exists only in the right file. */
    SHEET_ONLY_RIGHT("Sheet only in the right file"),
}

/**
 * One line of the diff table: everything the user reads about a difference.
 *
 * @property kind what happened.
 * @property sheet the sheet the difference belongs to.
 * @property cell the address of the cell (`B12`), empty for a row or sheet difference.
 * @property row the 1-based row number, 0 when the difference is not tied to a row.
 * @property left the value on the left side, empty when there is none.
 * @property right the value on the right side, empty when there is none.
 * @property formula the formulas when they differ (`A1+B1 -> A1+B2`), empty otherwise.
 */
data class DiffRow(
    val kind: DiffKind,
    val sheet: String,
    val cell: String,
    val row: Int,
    val left: String,
    val right: String,
    val formula: String,
)

/**
 * How many differences of each kind were found.
 */
data class DiffSummary(
    val modifiedCells: Int,
    val addedRows: Int,
    val removedRows: Int,
    val exclusiveSheets: Int,
) {
    /** Total number of differences shown to the user. */
    val total: Int get() = modifiedCells + addedRows + removedRows + exclusiveSheets

    /** One sentence, ready to be shown in a label. */
    fun asText(): String {
        if (total == 0) return "The two files are identical."
        val parts = ArrayList<String>()
        if (modifiedCells > 0) parts.add("$modifiedCells cell(s) modified")
        if (addedRows > 0) parts.add("$addedRows row(s) added")
        if (removedRows > 0) parts.add("$removedRows row(s) removed")
        if (exclusiveSheets > 0) parts.add("$exclusiveSheets sheet(s) in one file only")
        return parts.joinToString(", ") + "."
    }
}

/**
 * Turns a [DiffResult] into the flat list of lines shown in the diff table.
 *
 * This is the presentation logic of the plugin; it lives in `core` so that it
 * can be tested without the IDE.
 */
object DiffRows {

    /**
     * One line per difference, in the order the sheets and the rows appear.
     */
    fun of(result: DiffResult): List<DiffRow> {
        val rows = ArrayList<DiffRow>()
        for (sheet in result.sheets) {
            when (sheet.change) {
                SheetChange.ONLY_LEFT -> rows.add(
                    DiffRow(
                        kind = DiffKind.SHEET_ONLY_LEFT,
                        sheet = sheet.name,
                        cell = "",
                        row = 0,
                        left = "${sheet.leftColumnCount} column(s)",
                        right = "",
                        formula = "",
                    ),
                )
                SheetChange.ONLY_RIGHT -> rows.add(
                    DiffRow(
                        kind = DiffKind.SHEET_ONLY_RIGHT,
                        sheet = sheet.name,
                        cell = "",
                        row = 0,
                        left = "",
                        right = "${sheet.rightColumnCount} column(s)",
                        formula = "",
                    ),
                )
                null -> Unit
            }
            for (rowDiff in sheet.rows) {
                when (rowDiff.change) {
                    RowChange.MODIFIED -> for (cell in rowDiff.cells) {
                        rows.add(
                            DiffRow(
                                kind = DiffKind.CELL_MODIFIED,
                                sheet = sheet.name,
                                cell = cell.ref.address,
                                row = cell.ref.row,
                                left = cell.leftValue ?: "",
                                right = cell.rightValue ?: "",
                                formula = formulaNote(cell),
                            ),
                        )
                    }
                    RowChange.ADDED -> rows.add(
                        DiffRow(
                            kind = DiffKind.ROW_ADDED,
                            sheet = sheet.name,
                            cell = "",
                            row = rowDiff.row,
                            left = "",
                            right = joinValues(rowDiff.cells, right = true),
                            formula = "",
                        ),
                    )
                    RowChange.REMOVED -> rows.add(
                        DiffRow(
                            kind = DiffKind.ROW_REMOVED,
                            sheet = sheet.name,
                            cell = "",
                            row = rowDiff.row,
                            left = joinValues(rowDiff.cells, right = false),
                            right = "",
                            formula = "",
                        ),
                    )
                }
            }
        }
        return rows
    }

    /**
     * Counts the differences of each kind.
     */
    fun summary(result: DiffResult): DiffSummary {
        var modifiedCells = 0
        var addedRows = 0
        var removedRows = 0
        for (sheet in result.sheets) {
            for (rowDiff in sheet.rows) {
                when (rowDiff.change) {
                    RowChange.MODIFIED -> modifiedCells += rowDiff.cells.size
                    RowChange.ADDED -> addedRows++
                    RowChange.REMOVED -> removedRows++
                }
            }
        }
        return DiffSummary(
            modifiedCells = modifiedCells,
            addedRows = addedRows,
            removedRows = removedRows,
            exclusiveSheets = result.sheets.count { it.isExclusive },
        )
    }

    /**
     * The formulas when they are involved, `left -> right`, empty otherwise.
     */
    private fun formulaNote(cell: CellDiff): String {
        val left = cell.leftFormula
        val right = cell.rightFormula
        if (left == null && right == null) return ""
        return "${left ?: "—"} -> ${right ?: "—"}"
    }

    /**
     * The content of a whole added or removed row, cell by cell.
     */
    private fun joinValues(cells: List<CellDiff>, right: Boolean): String =
        cells.joinToString(" | ") { cell ->
            if (right) cell.rightValue ?: "" else cell.leftValue ?: ""
        }
}
