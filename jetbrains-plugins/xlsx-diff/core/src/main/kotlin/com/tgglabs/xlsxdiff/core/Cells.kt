package com.tgglabs.xlsxdiff.core

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.FormulaError
import java.util.Locale

/**
 * Reads the content of a cell in a spreadsheet-agnostic way.
 *
 * Everything here is defensive: a cell can hold anything, and a broken cell
 * must never crash the comparison.
 *
 * This is a **class**, not an object: the `DataFormatter` it holds is not
 * safe to share between threads, and two comparisons can run at the same time
 * (two projects). There is therefore one instance — and one formatter — per
 * comparison.
 */
internal class Cells {

    private val formatter = DataFormatter(Locale.ROOT)

    /**
     * The value a user would see in the cell.
     *
     * For a formula cell this is the *cached result* of the formula (the number
     * Excel last computed), because that is what the user actually reads.
     */
    fun displayValue(cell: Cell?): String {
        if (cell == null) return ""
        return when (cell.cellType) {
            CellType.FORMULA -> cachedFormulaValue(cell)
            CellType.BLANK -> ""
            CellType.STRING -> cell.stringCellValue
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.ERROR -> errorText(cell)
            CellType.NUMERIC -> numberText(cell)
            else -> ""
        }
    }

    /**
     * The formula behind the cell, without the leading `=`.
     * Returns null when the cell holds no formula.
     */
    fun formula(cell: Cell?): String? {
        if (cell == null || cell.cellType != CellType.FORMULA) return null
        return try {
            cell.cellFormula.trim()
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * The kind of content held by the cell. For a formula cell this is the kind
     * of its result, so that `=A1` holding a number is comparable with a plain
     * number cell.
     */
    fun typeName(cell: Cell?): String? {
        if (cell == null) return null
        val effective = if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType
        return effective.name
    }

    /**
     * True when the cell holds nothing at all.
     *
     * An empty text cell counts as blank: Excel users mean "nothing" by it.
     * A formula cell is never blank, even when its result is empty.
     */
    fun isBlank(cell: Cell?): Boolean {
        if (cell == null) return true
        return when (cell.cellType) {
            CellType.BLANK -> true
            CellType.STRING -> cell.stringCellValue.isEmpty()
            else -> false
        }
    }

    private fun cachedFormulaValue(cell: Cell): String = when (cell.cachedFormulaResultType) {
        CellType.NUMERIC -> numberText(cell)
        CellType.STRING -> cell.stringCellValue
        CellType.BOOLEAN -> cell.booleanCellValue.toString()
        CellType.ERROR -> errorText(cell)
        else -> ""
    }

    private fun numberText(cell: Cell): String {
        val style = cell.cellStyle
        return try {
            formatter.formatRawCellContents(cell.numericCellValue, style.dataFormat.toInt(), style.dataFormatString)
        } catch (e: RuntimeException) {
            cell.numericCellValue.toString()
        }
    }

    private fun errorText(cell: Cell): String = try {
        FormulaError.forInt(cell.errorCellValue).string
    } catch (e: RuntimeException) {
        "#ERROR"
    }
}
