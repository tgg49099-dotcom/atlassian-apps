package com.tgglabs.xlsxdiff.core

import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * A formula to put in a cell.
 */
data class FxFormula(val formula: String)

/**
 * Builds real `.xlsx` files with the spreadsheet library itself.
 *
 * The factory's known trap: a `.xlsx` that was not produced by a real writer
 * behaves differently from a real one. Every fixture used by the tests is
 * therefore written by the library and then read back by it.
 */
object Fx {

    fun create(path: Path, build: WorkbookFx.() -> Unit) {
        val workbook = XSSFWorkbook()
        try {
            WorkbookFx(workbook).build()
            path.parent?.let { Files.createDirectories(it) }
            Files.newOutputStream(path).use { out: OutputStream -> workbook.write(out) }
        } finally {
            workbook.close()
        }
    }
}

class WorkbookFx(private val workbook: XSSFWorkbook) {

    fun sheet(name: String, build: SheetFx.() -> Unit) {
        val sheet = workbook.createSheet(name)
        SheetFx(sheet).build()
    }
}

class SheetFx(private val sheet: Sheet) {

    private var nextRow = 0

    /**
     * Appends one row. Each value is one of: String, Int, Double, Boolean,
     * [FxFormula] or null for an empty cell.
     */
    fun row(vararg values: Any?) {
        val row: Row = sheet.createRow(nextRow++)
        values.forEachIndexed { index, value ->
            if (value == null) return@forEachIndexed
            val cell = row.createCell(index)
            when (value) {
                is String -> cell.setCellValue(value)
                is Int -> cell.setCellValue(value.toDouble())
                is Long -> cell.setCellValue(value.toDouble())
                is Double -> cell.setCellValue(value)
                is Boolean -> cell.setCellValue(value)
                is FxFormula -> cell.setCellFormula(value.formula)
                else -> throw IllegalArgumentException("Type de cellule non géré : ${value::class.simpleName}")
            }
        }
    }

    /** Appends a row and leaves the other cells of that row empty. */
    fun rowWithGaps(vararg values: Any?) {
        row(*values)
    }
}
