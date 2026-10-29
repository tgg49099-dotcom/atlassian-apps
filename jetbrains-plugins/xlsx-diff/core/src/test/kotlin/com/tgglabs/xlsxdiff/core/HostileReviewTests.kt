package com.tgglabs.xlsxdiff.core

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class HostileReviewTests {

    /**
     * The rule of the apostrophe applies to **every** cell of the row, not
     * only to the first one.
     *
     * The original version of this test asserted the opposite (it demonstrated
     * the flaw): it was rewritten on 28/10/2026 to assert the correct
     * behaviour. It fails on the code before the fix (the formula of the
     * second column came out raw) and passes on the code after the fix.
     */
    @Test
    fun `test row added csv sanitizes the formula of every column`() {
        val leftPath = Path.of("fake1.xlsx")
        val rightPath = Path.of("fake2.xlsx")
        val cellDiffs = listOf(
            CellDiff(
                ref = CellRef("Sheet1", 1, 1),
                changes = setOf(CellChange.ADDED),
                leftValue = null,
                rightValue = "Normal Header",
                leftFormula = null,
                rightFormula = null,
                leftType = null,
                rightType = "STRING",
            ),
            CellDiff(
                ref = CellRef("Sheet1", 1, 2),
                changes = setOf(CellChange.ADDED),
                leftValue = null,
                rightValue = "=cmd|' /C calc'!A1",
                leftFormula = null,
                rightFormula = null,
                leftType = null,
                rightType = "STRING",
            ),
        )
        val sheetDiff = SheetDiff(
            name = "Sheet1",
            rows = listOf(
                RowDiff("Sheet1", 1, RowChange.ADDED, cellDiffs),
            ),
        )
        val result = DiffResult(leftPath, rightPath, listOf(sheetDiff))
        val csv = DiffExporter.toCsv(result)

        // The value of the second column starts with '=': it must come out
        // neutralised, exactly like the value of the first column would be.
        assertTrue(
            csv.contains("Normal Header | '=cmd|' /C calc'!A1"),
            "Formula in second column was not neutralized in joined ROW_ADDED string: $csv",
        )
        assertFalse(
            csv.contains("Normal Header | =cmd|' /C calc'!A1"),
            "Formula in second column was written raw in joined ROW_ADDED string: $csv",
        )
        assertEquals(
            "ROW_ADDED,Sheet1,,1,,,Normal Header | '=cmd|' /C calc'!A1,,",
            csv.trim().lines()[1],
            "Unexpected ROW_ADDED line: $csv",
        )
    }

    @Test
    fun `test number formatting difference 1 vs 1_0`(@TempDir tempDir: Path) {
        val file1 = tempDir.resolve("num1.xlsx")
        val file2 = tempDir.resolve("num2.xlsx")

        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Data")
            val row = sheet.createRow(0)
            val cell = row.createCell(0)
            cell.setCellValue(1.0)
            val style = wb.createCellStyle()
            val format = wb.createDataFormat()
            style.dataFormat = format.getFormat("0")
            cell.cellStyle = style
            file1.toFile().outputStream().use { wb.write(it) }
        }

        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Data")
            val row = sheet.createRow(0)
            val cell = row.createCell(0)
            cell.setCellValue(1.0)
            val style = wb.createCellStyle()
            val format = wb.createDataFormat()
            style.dataFormat = format.getFormat("0.0")
            cell.cellStyle = style
            file2.toFile().outputStream().use { wb.write(it) }
        }

        val engine = XlsxDiffEngine()
        val diffResult = engine.diff(file1, file2)

        // Formatting differences cause false positive CellChange.VALUE differences
        assertTrue(diffResult.hasDifferences, "Engine reports difference due to format string '0' vs '0.0'")
        val cellDiff = diffResult.cells.first()
        assertEquals("1", cellDiff.leftValue)
        assertEquals("1.0", cellDiff.rightValue)
    }

    @Test
    fun `test multiline text in quote rendering`() {
        val leftPath = Path.of("fake1.xlsx")
        val rightPath = Path.of("fake2.xlsx")
        val cellDiff = CellDiff(
            ref = CellRef("Sheet1", 1, 1),
            changes = setOf(CellChange.VALUE),
            leftValue = "Line 1\nLine 2",
            rightValue = "Line 1\nModified Line 2",
            leftFormula = null,
            rightFormula = null,
            leftType = "STRING",
            rightType = "STRING",
        )
        val sheetDiff = SheetDiff(
            name = "Sheet1",
            rows = listOf(RowDiff("Sheet1", 1, RowChange.MODIFIED, listOf(cellDiff))),
        )
        val result = DiffResult(leftPath, rightPath, listOf(sheetDiff))
        val textReport = DiffExporter.toText(result)

        // The multiline value breaks into unescaped raw newlines in text report
        assertTrue(textReport.contains("left=\"Line 1\nLine 2\""))
    }

    @Test
    fun `test multi-letter column conversion`() {
        assertEquals("A", columnToName(1))
        assertEquals("Z", columnToName(26))
        assertEquals("AA", columnToName(27))
        assertEquals("AZ", columnToName(52))
        assertEquals("ZZ", columnToName(702))
        assertEquals("AAA", columnToName(703))
    }

    @Test
    fun `test boolean cell formatting`() {
        val cellsReader = Cells()
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet()
        val row = sheet.createRow(0)
        val cell = row.createCell(0)
        cell.setCellValue(true)

        // Returns lowercase 'true' instead of Excel standard UPPERCASE 'TRUE'
        assertEquals("true", cellsReader.displayValue(cell))
        assertEquals("BOOLEAN", cellsReader.typeName(cell))
        wb.close()
    }

    @Test
    fun `test empty vs missing cell behavior`() {
        val cellsReader = Cells()
        val wb = XSSFWorkbook()
        val sheet = wb.createSheet()
        val row = sheet.createRow(0)

        // Missing cell
        val missingCell = row.getCell(0)
        assertTrue(cellsReader.isBlank(missingCell))
        assertNull(cellsReader.typeName(missingCell))

        // Blank cell
        val blankCell = row.createCell(1)
        assertTrue(cellsReader.isBlank(blankCell))

        // String cell with empty string
        val emptyStringCell = row.createCell(2)
        emptyStringCell.setCellValue("")
        assertTrue(cellsReader.isBlank(emptyStringCell))

        wb.close()
    }

    @Test
    fun `test sheet name unicode normalization NFC vs NFD`(@TempDir tempDir: Path) {
        val file1 = tempDir.resolve("nfc.xlsx")
        val file2 = tempDir.resolve("nfd.xlsx")

        val nfcName = "Donn\u00E9es" // NFC: é as single codepoint
        val nfdName = "Donne\u0301es" // NFD: e + combining acute accent

        XSSFWorkbook().use { wb ->
            wb.createSheet(nfcName)
            file1.toFile().outputStream().use { wb.write(it) }
        }

        XSSFWorkbook().use { wb ->
            wb.createSheet(nfdName)
            file2.toFile().outputStream().use { wb.write(it) }
        }

        val engine = XlsxDiffEngine()
        val result = engine.diff(file1, file2)

        // NFC vs NFD sheet names are treated as 2 distinct sheets (ONLY_LEFT and ONLY_RIGHT)
        assertEquals(2, result.sheets.size)
        assertTrue(result.sheets.any { it.change == SheetChange.ONLY_LEFT })
        assertTrue(result.sheets.any { it.change == SheetChange.ONLY_RIGHT })
    }
}
