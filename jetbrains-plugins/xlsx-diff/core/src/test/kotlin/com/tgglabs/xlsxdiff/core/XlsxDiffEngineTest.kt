package com.tgglabs.xlsxdiff.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The heart of the product: comparing two `.xlsx` files, cell by cell.
 */
class XlsxDiffEngineTest {

    @TempDir
    lateinit var tmp: Path

    private val engine = XlsxDiffEngine()

    private fun path(name: String): Path = tmp.resolve(name)

    // ---------------------------------------------------------------- 1
    @Test
    fun `two identical files have no difference`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("Feuille 1") { row("Produit", 10.0); row("Total", 30.0) } }
        Fx.create(right) { sheet("Feuille 1") { row("Produit", 10.0); row("Total", 30.0) } }

        val result = engine.diff(left, right)

        assertFalse(result.hasDifferences, "les deux fichiers sont identiques")
        assertEquals(0, result.modifiedCellCount)
        assertEquals(0, result.changedRowCount)
        assertEquals(0, result.exclusiveSheetCount)
    }

    // ---------------------------------------------------------------- 2
    @Test
    fun `a modified value is reported on the right cell`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("Produit", 10.0) } }
        Fx.create(right) { sheet("S") { row("Produit", 20.0) } }

        val result = engine.diff(left, right)

        assertTrue(result.hasDifferences)
        val sheet = result.sheets.single()
        val rowDiff = sheet.rows.single()
        assertEquals(RowChange.MODIFIED, rowDiff.change)
        assertEquals(1, rowDiff.row)
        val cell = rowDiff.cells.single()
        assertEquals("B1", cell.ref.address)
        assertEquals(setOf(CellChange.VALUE), cell.changes)
        assertEquals("10", cell.leftValue)
        assertEquals("20", cell.rightValue)
    }

    // ---------------------------------------------------------------- 3
    @Test
    fun `a modified formula is reported`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("Total", FxFormula("A1+A2")); row(10.0, 20.0) } }
        Fx.create(right) { sheet("S") { row("Total", FxFormula("A1+A3")); row(10.0, 20.0) } }

        val result = engine.diff(left, right)

        val cell = result.sheets.single().rows.single().cells.single()
        assertEquals("B1", cell.ref.address)
        assertTrue(cell.changes.contains(CellChange.FORMULA), "la formule doit être signalée")
        assertEquals("A1+A2", cell.leftFormula)
        assertEquals("A1+A3", cell.rightFormula)
    }

    // ---------------------------------------------------------------- 4
    @Test
    fun `an added row is reported`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("A"); row("B") } }
        Fx.create(right) { sheet("S") { row("A"); row("B"); row("C") } }

        val result = engine.diff(left, right)

        val rowDiff = result.sheets.single().rows.single()
        assertEquals(RowChange.ADDED, rowDiff.change)
        assertEquals(3, rowDiff.row)
        assertEquals("C", rowDiff.cells.single().rightValue)
    }

    // ---------------------------------------------------------------- 5
    @Test
    fun `a removed row is reported`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("A"); row("B"); row("C") } }
        Fx.create(right) { sheet("S") { row("A"); row("B") } }

        val result = engine.diff(left, right)

        val rowDiff = result.sheets.single().rows.single()
        assertEquals(RowChange.REMOVED, rowDiff.change)
        assertEquals(3, rowDiff.row)
        assertEquals("C", rowDiff.cells.single().leftValue)
    }

    // ---------------------------------------------------------------- 6
    @Test
    fun `an empty cell versus a filled cell is reported`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("A", null) } }
        Fx.create(right) { sheet("S") { row("A", "B") } }

        val result = engine.diff(left, right)

        val cell = result.sheets.single().rows.single().cells.single()
        assertEquals("B1", cell.ref.address)
        assertEquals(setOf(CellChange.ADDED), cell.changes)
        assertNull(cell.leftValue)
        assertEquals("B", cell.rightValue)
    }

    @Test
    fun `a filled cell versus an empty cell is reported`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("A", "B") } }
        Fx.create(right) { sheet("S") { row("A", null) } }

        val result = engine.diff(left, right)

        val cell = result.sheets.single().rows.single().cells.single()
        assertEquals("B1", cell.ref.address)
        assertEquals(setOf(CellChange.REMOVED), cell.changes)
        assertEquals("B", cell.leftValue)
        assertNull(cell.rightValue)
    }

    // ---------------------------------------------------------------- 7
    @Test
    fun `a sheet present in only one file is reported`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("Commune") { row("A") }; sheet("GaucheSeule") { row("B") } }
        Fx.create(right) { sheet("Commune") { row("A") }; sheet("DroiteSeule") { row("C") } }

        val result = engine.diff(left, right)

        assertEquals(2, result.exclusiveSheetCount)
        val gauche = result.sheets.single { it.name == "GaucheSeule" }
        val droite = result.sheets.single { it.name == "DroiteSeule" }
        assertEquals(SheetChange.ONLY_LEFT, gauche.change)
        assertEquals(SheetChange.ONLY_RIGHT, droite.change)
        assertTrue(result.sheets.none { it.name == "Commune" }, "la feuille commune est identique")
    }

    // ---------------------------------------------------------------- 8
    @Test
    fun `a difference in another column is reported on that column`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("A", "B", "C") } }
        Fx.create(right) { sheet("S") { row("A", "B", "D") } }

        val result = engine.diff(left, right)

        val cell = result.sheets.single().rows.single().cells.single()
        assertEquals("C1", cell.ref.address)
        assertEquals(setOf(CellChange.VALUE), cell.changes)
        assertEquals("C", cell.leftValue)
        assertEquals("D", cell.rightValue)
    }

    // ---------------------------------------------------------------- 9
    @Test
    fun `an unreadable file gives a clean error message`() {
        val left = path("casse.xlsx")
        val right = path("right.xlsx")
        Files.write(left, "ceci n'est pas un classeur Excel".toByteArray())
        Fx.create(right) { sheet("S") { row("A") } }

        val error = assertThrows<XlsxDiffException> { engine.diff(left, right) }

        assertNotNull(error.message)
        val message = error.message!!
        assertTrue(message.contains("casse.xlsx"), "le message doit nommer le fichier : $message")
        assertFalse(message.contains("poi", ignoreCase = true), "le message ne doit pas nommer la librairie : $message")
        assertFalse(message.contains("org.apache"), "le message ne doit pas nommer la librairie : $message")
    }

    @Test
    fun `a missing file gives a clean error message`() {
        val left = path("absent.xlsx")
        val right = path("right.xlsx")
        Fx.create(right) { sheet("S") { row("A") } }

        val error = assertThrows<XlsxDiffException> { engine.diff(left, right) }

        assertTrue(error.message!!.contains("absent.xlsx"))
        assertFalse(error.message!!.contains("poi", ignoreCase = true))
    }

    // --------------------------------------------------------------- 10
    @Test
    fun `a file written then read back then compared is identical`() {
        val left = path("aller-retour.xlsx")
        val right = path("aller-retour-bis.xlsx")
        Fx.create(left) {
            sheet("Données") {
                row("Nom", "Valeur")
                row("Alpha", 1.5)
                row("Bêta", 2.5)
                row("Total", FxFormula("B2+B3"))
            }
        }
        Fx.create(right) {
            sheet("Données") {
                row("Nom", "Valeur")
                row("Alpha", 1.5)
                row("Bêta", 2.5)
                row("Total", FxFormula("B2+B3"))
            }
        }

        // the files really are readable by a spreadsheet reader
        val result = engine.diff(left, right)

        assertFalse(result.hasDifferences, "un fichier écrit puis relu doit être identique à lui-même")
    }

    // --------------------------------------------------------------- 11
    @Test
    fun `the original files are never modified`() {
        val left = path("gauche.xlsx")
        val right = path("droite.xlsx")
        Fx.create(left) { sheet("S") { row("A", 1.0); row("B", 2.0) } }
        Fx.create(right) { sheet("S") { row("A", 9.0); row("B", 2.0) } }

        val beforeLeft = sha256(left)
        val beforeRight = sha256(right)
        val beforeModified = listOf(left, right).map { Files.getLastModifiedTime(it) }

        engine.diff(left, right)

        assertEquals(beforeLeft, sha256(left), "le fichier gauche a été modifié")
        assertEquals(beforeRight, sha256(right), "le fichier droit a été modifié")
        val afterModified = listOf(left, right).map { Files.getLastModifiedTime(it) }
        assertEquals(beforeModified, afterModified, "la date de modification a changé")
        Files.newDirectoryStream(tmp).use { entries ->
            assertTrue(
                entries.none { it.fileName.toString().startsWith(".~lock") },
                "un fichier de verrou a été créé à côté de l'original",
            )
        }
    }

    // --------------------------------------------------------------- 12
    @Test
    fun `the differences can be exported to csv`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("A", 10.0) } }
        Fx.create(right) { sheet("S") { row("A", 20.0) } }

        val csv = DiffExporter.toCsv(engine.diff(left, right))

        val lines = csv.trim().lines()
        assertEquals(
            "type,sheet,cell,row,column,left_value,right_value,left_formula,right_formula",
            lines.first(),
        )
        assertTrue(csv.contains("VALUE,S,B1,1,B,10,20,,"), "csv inattendu :\n$csv")
    }

    // --------------------------------------------------------------- 13
    @Test
    fun `a row inserted in the middle does not shift every following row`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("A"); row("B"); row("C") } }
        Fx.create(right) { sheet("S") { row("A"); row("NOUVEAU"); row("B"); row("C") } }

        val result = engine.diff(left, right)

        val rows = result.sheets.single().rows
        assertEquals(1, rows.size, "seule la ligne ajoutée doit être signalée, pas les suivantes")
        assertEquals(RowChange.ADDED, rows.single().change)
        assertEquals(2, rows.single().row)
    }

    // --------------------------------------------------------------- 14
    @Test
    fun `a changed row is reported as modified and not as added plus removed`() {
        val left = path("left.xlsx")
        val right = path("right.xlsx")
        Fx.create(left) { sheet("S") { row("A"); row("B"); row("C") } }
        Fx.create(right) { sheet("S") { row("A"); row("CHANGÉ"); row("C") } }

        val result = engine.diff(left, right)

        val rows = result.sheets.single().rows
        assertEquals(1, rows.size)
        assertEquals(RowChange.MODIFIED, rows.single().change)
        assertEquals(2, rows.single().row)
        assertEquals(1, result.modifiedCellCount)
    }

    // --------------------------------------------------------------- 15 Edge cases
    @Test
    fun `empty sheets comparison gives no differences`() {
        val left = path("empty_left.xlsx")
        val right = path("empty_right.xlsx")
        Fx.create(left) { sheet("EmptySheet") {} }
        Fx.create(right) { sheet("EmptySheet") {} }

        val result = engine.diff(left, right)

        assertFalse(result.hasDifferences)
    }

    @Test
    fun `sheet names with accents and apostrophes are correctly compared`() {
        val left = path("accent_left.xlsx")
        val right = path("accent_right.xlsx")
        Fx.create(left) { sheet("Données d'été") { row("Clé", "Valeur 1") } }
        Fx.create(right) { sheet("Données d'été") { row("Clé", "Valeur 2") } }

        val result = engine.diff(left, right)

        assertTrue(result.hasDifferences)
        val sheet = result.sheets.single()
        assertEquals("Données d'été", sheet.name)
        val cell = sheet.rows.single().cells.single()
        assertEquals("Valeur 1", cell.leftValue)
        assertEquals("Valeur 2", cell.rightValue)
    }

    @Test
    fun `column to name conversion handles single and multi-letter columns`() {
        assertEquals("A", CellRef("S", 1, 1).columnName)
        assertEquals("Z", CellRef("S", 1, 26).columnName)
        assertEquals("AA", CellRef("S", 1, 27).columnName)
        assertEquals("AZ", CellRef("S", 1, 52).columnName)
        assertEquals("BA", CellRef("S", 1, 53).columnName)
        assertEquals("ZZ", CellRef("S", 1, 702).columnName)
        assertEquals("AAA", CellRef("S", 1, 703).columnName)
    }

    @Test
    fun `boolean and numeric cells are correctly formatted and compared`() {
        val left = path("types_left.xlsx")
        val right = path("types_right.xlsx")
        Fx.create(left) { sheet("Types") { row(true, 123.45) } }
        Fx.create(right) { sheet("Types") { row(false, 123.45) } }

        val result = engine.diff(left, right)

        assertTrue(result.hasDifferences)
        val cell = result.sheets.single().rows.single().cells.single()
        assertEquals("A1", cell.ref.address)
        assertEquals("true", cell.leftValue)
        assertEquals("false", cell.rightValue)
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
