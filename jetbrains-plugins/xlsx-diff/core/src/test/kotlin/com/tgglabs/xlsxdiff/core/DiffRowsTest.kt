package com.tgglabs.xlsxdiff.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The presentation layer: what the user reads in the diff table.
 */
class DiffRowsTest {

    @TempDir
    lateinit var tmp: Path

    private val engine = XlsxDiffEngine()

    private fun path(name: String): Path = tmp.resolve(name)

    // ---------------------------------------------------------------- 1
    @Test
    fun `identical files give no row at all`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("a", 1.0) } }
        Fx.create(right) { sheet("S") { row("a", 1.0) } }

        val result = engine.diff(left, right)

        assertTrue(DiffRows.of(result).isEmpty())
        val summary = DiffRows.summary(result)
        assertEquals(0, summary.total)
        assertEquals("The two files are identical.", summary.asText())
    }

    // ---------------------------------------------------------------- 2
    @Test
    fun `a modified cell gives one row with both values`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("a", 1.0) } }
        Fx.create(right) { sheet("S") { row("a", 2.0) } }

        val rows = DiffRows.of(engine.diff(left, right))

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(DiffKind.CELL_MODIFIED, row.kind)
        assertEquals("S", row.sheet)
        assertEquals("B1", row.cell)
        assertEquals(1, row.row)
        assertEquals("1", row.left)
        assertEquals("2", row.right)
        assertEquals("", row.formula)
    }

    // ---------------------------------------------------------------- 3
    @Test
    fun `a changed formula is shown`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("a", 1.0); row(FxFormula("A1*2")) } }
        Fx.create(right) { sheet("S") { row("a", 1.0); row(FxFormula("A1*3")) } }

        val rows = DiffRows.of(engine.diff(left, right))

        val row = rows.single { it.cell == "A2" }
        assertEquals(DiffKind.CELL_MODIFIED, row.kind)
        assertTrue(row.formula.contains("A1*2"), "formule gauche absente : ${row.formula}")
        assertTrue(row.formula.contains("A1*3"), "formule droite absente : ${row.formula}")
    }

    // ---------------------------------------------------------------- 4
    @Test
    fun `an added row is one line with the content of the row`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("a", 1.0) } }
        Fx.create(right) { sheet("S") { row("a", 1.0); row("b", 2.0) } }

        val rows = DiffRows.of(engine.diff(left, right))

        val row = rows.single { it.kind == DiffKind.ROW_ADDED }
        assertEquals("S", row.sheet)
        assertEquals("", row.cell)
        assertEquals(2, row.row)
        assertEquals("", row.left)
        assertTrue(row.right.contains("b"), "contenu de la ligne absente : ${row.right}")
    }

    // ---------------------------------------------------------------- 5
    @Test
    fun `a removed row is one line with the content of the row`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("a", 1.0); row("b", 2.0) } }
        Fx.create(right) { sheet("S") { row("a", 1.0) } }

        val rows = DiffRows.of(engine.diff(left, right))

        val row = rows.single { it.kind == DiffKind.ROW_REMOVED }
        assertEquals("", row.right)
        assertTrue(row.left.contains("b"), "contenu de la ligne absente : ${row.left}")
    }

    // ---------------------------------------------------------------- 6
    @Test
    fun `a sheet that exists in one file only is one line`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("a", 1.0) }; sheet("Gauche") { row("x", 1.0) } }
        Fx.create(right) { sheet("S") { row("a", 1.0) }; sheet("Droite") { row("y", 1.0) } }

        val rows = DiffRows.of(engine.diff(left, right))

        val onlyLeft = rows.single { it.kind == DiffKind.SHEET_ONLY_LEFT }
        val onlyRight = rows.single { it.kind == DiffKind.SHEET_ONLY_RIGHT }
        assertEquals("Gauche", onlyLeft.sheet)
        assertEquals("Droite", onlyRight.sheet)
        assertFalse(onlyLeft.right.contains("Droite"))
    }

    // ---------------------------------------------------------------- 7
    @Test
    fun `the summary counts every kind of difference`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        // S : une ligne de trop a gauche -> SUPPRIMEE
        // T : une ligne de trop a droite -> AJOUTEE
        // U : une cellule changee -> MODIFIEE
        // + une feuille de chaque cote -> EXCLUSIVITE
        Fx.create(left) {
            sheet("S") { row("a", 1.0); row("b", 2.0); row("z", 9.0) }
            sheet("T") { row("a", 1.0) }
            sheet("U") { row("x", 1.0) }
            sheet("Gauche") { row("x", 1.0) }
        }
        Fx.create(right) {
            sheet("S") { row("a", 1.0); row("b", 2.0) }
            sheet("T") { row("a", 1.0); row("n", 5.0) }
            sheet("U") { row("x", 2.0) }
            sheet("Droite") { row("y", 1.0) }
        }

        val result = engine.diff(left, right)
        val summary = DiffRows.summary(result)

        assertEquals(1, summary.modifiedCells, "cellules modifiees : ${DiffRows.of(result)}")
        assertEquals(1, summary.addedRows, "lignes ajoutees : ${DiffRows.of(result)}")
        assertEquals(1, summary.removedRows, "lignes supprimees : ${DiffRows.of(result)}")
        assertEquals(2, summary.exclusiveSheets)
        assertEquals(5, summary.total)
        assertTrue(summary.asText().contains("cell(s) modified"))
        assertTrue(summary.asText().contains("row(s) added"))
        assertTrue(summary.asText().contains("row(s) removed"))
        assertTrue(summary.asText().contains("sheet(s) in one file only"))
    }

    // ---------------------------------------------------------------- 8
    @Test
    fun `the number of rows of the table is the number of differences`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("a", 1.0); row("b", 2.0) } }
        Fx.create(right) { sheet("S") { row("a", 9.0); row("b", 2.0); row("c", 3.0) } }

        val rows = DiffRows.of(engine.diff(left, right))

        // une cellule modifiée + une ligne ajoutée = deux lignes dans le tableau
        assertEquals(2, rows.size)
        assertEquals(DiffKind.CELL_MODIFIED, rows[0].kind)
        assertEquals(DiffKind.ROW_ADDED, rows[1].kind)
    }

    // ---------------------------------------------------------------- 9
    @Test
    fun `inside a gap the rows are paired by position, not removed and added`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("a", 1.0); row("b", 2.0) } }
        Fx.create(right) { sheet("S") { row("a", 9.0); row("c", 3.0) } }

        val rows = DiffRows.of(engine.diff(left, right))

        // La deuxieme ligne a change de contenu : elle est MODIFIEE (B1, A2, B2),
        // et non "une ligne supprimee + une ligne ajoutee".
        assertTrue(rows.all { it.kind == DiffKind.CELL_MODIFIED }, "lignes : $rows")
        assertEquals(3, rows.size, "lignes : $rows")
    }
}
