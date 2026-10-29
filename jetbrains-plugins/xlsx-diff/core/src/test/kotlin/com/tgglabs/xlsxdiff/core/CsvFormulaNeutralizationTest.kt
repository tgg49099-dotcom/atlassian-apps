package com.tgglabs.xlsxdiff.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The rule of the apostrophe in the exported CSV.
 *
 * A value that Excel would read as a formula is prefixed with an apostrophe,
 * so that opening the report cannot execute anything. The rule looks at the
 * **first character of a value**, and a value is a **cell**: in a row that was
 * added or removed, the cells are joined into a single field, and every one of
 * them must be neutralised on its own.
 *
 * Before the fix of 28/10/2026, only the first cell of the row was protected:
 * the join happened before the rule was applied, so a formula in the second
 * column (or further) came out raw and Excel ran it when the report was
 * opened. These tests are the proof of the fix.
 */
class CsvFormulaNeutralizationTest {

    @TempDir
    lateinit var tmp: Path

    private val engine = XlsxDiffEngine()

    private fun path(name: String): Path = tmp.resolve(name)

    /** The value of one cell of a row that was added. */
    private fun addedCell(column: Int, value: String): CellDiff = CellDiff(
        ref = CellRef("Sheet1", 1, column),
        changes = setOf(CellChange.ADDED),
        leftValue = null,
        rightValue = value,
        leftFormula = null,
        rightFormula = null,
        leftType = null,
        rightType = "STRING",
    )

    /** The value of one cell of a row that was removed. */
    private fun removedCell(column: Int, value: String): CellDiff = CellDiff(
        ref = CellRef("Sheet1", 1, column),
        changes = setOf(CellChange.REMOVED),
        leftValue = value,
        rightValue = null,
        leftFormula = null,
        rightFormula = null,
        leftType = "STRING",
        rightType = null,
    )

    private fun csvOfAddedRow(vararg values: String): String {
        val cells = values.mapIndexed { index, value -> addedCell(index + 1, value) }
        val result = DiffResult(
            Path.of("left.xlsx"),
            Path.of("right.xlsx"),
            listOf(
                SheetDiff(
                    name = "Sheet1",
                    rows = listOf(RowDiff("Sheet1", 1, RowChange.ADDED, cells)),
                ),
            ),
        )
        return DiffExporter.toCsv(result)
    }

    private fun csvOfRemovedRow(vararg values: String): String {
        val cells = values.mapIndexed { index, value -> removedCell(index + 1, value) }
        val result = DiffResult(
            Path.of("left.xlsx"),
            Path.of("right.xlsx"),
            listOf(
                SheetDiff(
                    name = "Sheet1",
                    rows = listOf(RowDiff("Sheet1", 1, RowChange.REMOVED, cells)),
                ),
            ),
        )
        return DiffExporter.toCsv(result)
    }

    // ------------------------------------------------------------------ 1
    /**
     * The exact case found on 28/10/2026: the formula is in the **second**
     * column of an added row.
     */
    @Test
    fun `a formula in the second column of an added row is neutralised`() {
        val csv = csvOfAddedRow("Normal Header", "=cmd|' /C calc'!A1")

        assertTrue(
            csv.contains("Normal Header | '=cmd|' /C calc'!A1"),
            "la formule de la 2e colonne doit etre neutralisee :\n$csv",
        )
        assertFalse(
            csv.contains("Normal Header | =cmd|' /C calc'!A1"),
            "la formule de la 2e colonne a ete ecrite brute :\n$csv",
        )
    }

    // ------------------------------------------------------------------ 2
    /**
     * The same rule on a row that was **removed**: the values of the left
     * file are joined into the same field.
     */
    @Test
    fun `a formula in the second column of a removed row is neutralised`() {
        val csv = csvOfRemovedRow("Normal Header", "=cmd|' /C calc'!A1")

        assertTrue(
            csv.contains("Normal Header | '=cmd|' /C calc'!A1"),
            "la formule de la 2e colonne doit etre neutralisee :\n$csv",
        )
        assertFalse(
            csv.contains("Normal Header | =cmd|' /C calc'!A1"),
            "la formule de la 2e colonne a ete ecrite brute :\n$csv",
        )
    }

    // ------------------------------------------------------------------ 3
    /**
     * Every dangerous cell of the row is neutralised, whatever its position.
     */
    @Test
    fun `every dangerous cell of an added row is neutralised`() {
        val csv = csvOfAddedRow("=1+1", "Normal", "@SUM(A1)", "+cmd", "-cmd")

        assertEquals(
            "ROW_ADDED,Sheet1,,1,,,'=1+1 | Normal | '@SUM(A1) | '+cmd | '-cmd,,",
            csv.trim().lines()[1],
            "csv inattendu :\n$csv",
        )
        assertFalse(csv.contains("| @SUM(A1)"), "csv inattendu :\n$csv")
        assertFalse(csv.contains("| +cmd"), "csv inattendu :\n$csv")
        assertFalse(csv.contains("| -cmd"), "csv inattendu :\n$csv")
    }

    // ------------------------------------------------------------------ 4
    /**
     * A normal value is left exactly as it is: the rule must not turn the
     * whole row into a string full of apostrophes.
     */
    @Test
    fun `a normal value of an added row is not neutralised`() {
        val csv = csvOfAddedRow("Normal Header", "42")

        assertTrue(csv.contains("Normal Header | 42"), "csv inattendu :\n$csv")
        assertFalse(csv.contains("'Normal Header"), "csv inattendu :\n$csv")
        assertFalse(csv.contains("'42"), "csv inattendu :\n$csv")
    }

    // ------------------------------------------------------------------ 5
    /**
     * Leading blanks do not hide a formula: " =1+1" is read as a formula too,
     * in any column of the row.
     */
    @Test
    fun `a formula with leading blanks in the second column is neutralised`() {
        val csv = csvOfAddedRow("Normal Header", " =1+1")

        assertTrue(csv.contains("Normal Header | ' =1+1"), "csv inattendu :\n$csv")
    }

    // ------------------------------------------------------------------ 6
    /**
     * The whole line, exactly: the row added, with its two columns.
     */
    @Test
    fun `the added row line holds the neutralised value of every column`() {
        val csv = csvOfAddedRow("Normal Header", "=cmd|' /C calc'!A1")

        assertEquals(
            "ROW_ADDED,Sheet1,,1,,,Normal Header | '=cmd|' /C calc'!A1,,",
            csv.trim().lines()[1],
            "ligne inattendue :\n$csv",
        )
    }

    // ------------------------------------------------------------------ 7
    /**
     * The same, on real files: the comparison of two real spreadsheets finds
     * the added row and the export neutralises the formula of its second
     * column.
     */
    @Test
    fun `a formula in the second column of a real added row is neutralised`() {
        val left = path("gauche.xlsx")
        val right = path("droite.xlsx")
        Fx.create(left) { sheet("S") { row("First") } }
        Fx.create(right) {
            sheet("S") {
                row("First")
                row("Normal Header", "=cmd|' /C calc'!A1")
            }
        }

        val csv = DiffExporter.toCsv(engine.diff(left, right))

        assertTrue(
            csv.contains("Normal Header | '=cmd|' /C calc'!A1"),
            "la formule de la 2e colonne doit etre neutralisee :\n$csv",
        )
        assertFalse(
            csv.contains("Normal Header | =cmd|' /C calc'!A1"),
            "la formule de la 2e colonne a ete ecrite brute :\n$csv",
        )
    }

    // ------------------------------------------------------------------ 8
    /**
     * And on real files, for a row that disappeared.
     */
    @Test
    fun `a formula in the second column of a real removed row is neutralised`() {
        val left = path("gauche.xlsx")
        val right = path("droite.xlsx")
        Fx.create(left) {
            sheet("S") {
                row("First")
                row("Normal Header", "=cmd|' /C calc'!A1")
            }
        }
        Fx.create(right) { sheet("S") { row("First") } }

        val csv = DiffExporter.toCsv(engine.diff(left, right))

        assertTrue(
            csv.contains("Normal Header | '=cmd|' /C calc'!A1"),
            "la formule de la 2e colonne doit etre neutralisee :\n$csv",
        )
        assertFalse(
            csv.contains("Normal Header | =cmd|' /C calc'!A1"),
            "la formule de la 2e colonne a ete ecrite brute :\n$csv",
        )
    }
}
