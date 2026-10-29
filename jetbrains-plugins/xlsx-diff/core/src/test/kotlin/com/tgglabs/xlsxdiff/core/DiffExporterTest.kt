package com.tgglabs.xlsxdiff.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The export: the file the user opens in Excel.
 *
 * Everything here is about what happens on the computer of the user: the
 * encoding, the separator and the values that could be read as a formula.
 */
class DiffExporterTest {

    @TempDir
    lateinit var tmp: Path

    private val engine = XlsxDiffEngine()

    private fun path(name: String): Path = tmp.resolve(name)

    /** Two files whose only difference is the value of B1. */
    private fun diffWith(leftValue: String, rightValue: String): DiffResult {
        val left = path("gauche.xlsx")
        val right = path("droite.xlsx")
        Fx.create(left) { sheet("Données") { row("Produit", leftValue) } }
        Fx.create(right) { sheet("Données") { row("Produit", rightValue) } }
        return engine.diff(left, right)
    }

    // ---------------------------------------------------------------- 1
    @Test
    fun `the csv separates its columns with a comma`() {
        val result = diffWith("10", "20")

        val csv = DiffExporter.toCsv(result)

        val lines = csv.trim().lines()
        assertEquals(
            "type,sheet,cell,row,column,left_value,right_value,left_formula,right_formula",
            lines.first(),
            "l'en-tete doit utiliser la virgule : ${lines.first()}",
        )
        assertEquals(9, lines.first().count { it == ',' } + 1, "l'en-tete doit avoir 9 colonnes")
        for (line in lines.drop(1)) {
            assertEquals(9, line.count { it == ',' } + 1, "chaque ligne doit avoir 9 colonnes : $line")
        }
        assertTrue(csv.contains("VALUE,Données,B1,1,B,10,20,,"), "csv inattendu :\n$csv")
    }

    // ---------------------------------------------------------------- 2
    @Test
    fun `an accented value written in utf8 is read back identically`() {
        val accent = "Café à gogo « ici »"
        val result = diffWith(accent, "Thé")

        val file = path("diff.csv")
        DiffExporter.writeCsv(file, result)

        val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            .removePrefix(DiffExporter.UTF8_BOM)
        assertTrue(text.contains(accent), "l'accent a été perdu :\n$text")
        assertTrue(text.contains("Données"), "le nom de la feuille a été perdu :\n$text")
    }

    // ---------------------------------------------------------------- 3
    @Test
    fun `the csv file starts with the utf8 byte order mark`() {
        val result = diffWith("10", "20")

        val file = path("diff.csv")
        DiffExporter.writeCsv(file, result)

        val bytes = Files.readAllBytes(file)
        assertTrue(bytes.size > 3, "le fichier est vide")
        assertEquals(0xEF.toByte(), bytes[0], "le BOM UTF-8 manque")
        assertEquals(0xBB.toByte(), bytes[1], "le BOM UTF-8 manque")
        assertEquals(0xBF.toByte(), bytes[2], "le BOM UTF-8 manque")
    }

    // ---------------------------------------------------------------- 4
    @Test
    fun `the file holds exactly the exported text`() {
        val result = diffWith("10", "20")

        val file = path("diff.csv")
        DiffExporter.writeCsv(file, result)

        val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        assertEquals(DiffExporter.UTF8_BOM + DiffExporter.toCsv(result), text)
    }

    // ---------------------------------------------------------------- 5
    @Test
    fun `a value that looks like a formula does not come out bare`() {
        val result = diffWith("=1+1", "=2+2")

        val csv = DiffExporter.toCsv(result)

        assertFalse(csv.contains(",=1+1,"), "la formule doit être neutralisée :\n$csv")
        assertTrue(csv.contains(",'=1+1,"), "la formule doit être neutralisée :\n$csv")
    }

    @Test
    fun `every kind of dangerous value is neutralised`() {
        val result = diffWith("=1+1", "@SUM(A1)")
        val csv = DiffExporter.toCsv(result)
        assertTrue(csv.contains(",'=1+1,"), "csv inattendu :\n$csv")
        assertTrue(csv.contains(",'@SUM(A1)"), "csv inattendu :\n$csv")

        val other = diffWith("+cmd", "-cmd")
        val otherCsv = DiffExporter.toCsv(other)
        assertTrue(otherCsv.contains(",'+cmd,"), "csv inattendu :\n$otherCsv")
        assertTrue(otherCsv.contains(",'-cmd,"), "csv inattendu :\n$otherCsv")
    }

    /**
     * A value that starts with a tabulation is read as a formula by some
     * readers: it must be neutralised too.
     */
    @Test
    fun `a value that starts with a tabulation is neutralised`() {
        val result = diffWith("\t=1+1", "x")

        val csv = DiffExporter.toCsv(result)

        assertTrue(csv.contains(",'\t=1+1,"), "la tabulation doit être neutralisée :\n$csv")
    }

    // ---------------------------------------------------------------- 6
    /**
     * The rule of the apostrophe looks at the **first character** of the
     * value. A negative number starts with a sign, and Excel would read
     * "-2+3" as a formula, so it is neutralised too.
     */
    @Test
    fun `a negative number is neutralised too`() {
        val left = path("gauche.xlsx")
        val right = path("droite.xlsx")
        Fx.create(left) { sheet("S") { row("A", -42.0) } }
        Fx.create(right) { sheet("S") { row("A", 20.0) } }

        val csv = DiffExporter.toCsv(engine.diff(left, right))

        assertTrue(csv.contains(",'-42,20,,"), "la regle porte sur le 1er caractere :\n$csv")
        assertFalse(csv.contains(",-42,20,,"), "la regle porte sur le 1er caractere :\n$csv")
    }

    /**
     * A positive number starts with a digit: it is left exactly as it is.
     */
    @Test
    fun `a positive number is not neutralised`() {
        val left = path("gauche.xlsx")
        val right = path("droite.xlsx")
        Fx.create(left) { sheet("S") { row("A", 42.0) } }
        Fx.create(right) { sheet("S") { row("A", 20.0) } }

        val csv = DiffExporter.toCsv(engine.diff(left, right))

        assertTrue(csv.contains(",42,20,,"), "un nombre positif ne doit pas etre neutralise :\n$csv")
        assertFalse(csv.contains("'42"), "un nombre positif ne doit pas etre neutralise :\n$csv")
    }

    /**
     * The name of a sheet comes from the file that is compared: it can be
     * hostile too, and the rule is applied to it as well.
     */
    @Test
    fun `a hostile sheet name is neutralised`() {
        val left = path("gauche.xlsx")
        val right = path("droite.xlsx")
        // Excel forbids \ / ? * [ ] : in a sheet name, but "=cmd" is allowed.
        Fx.create(left) { sheet("=cmd") { row("A", 1.0) } }
        Fx.create(right) { sheet("=cmd") { row("A", 2.0) } }

        val csv = DiffExporter.toCsv(engine.diff(left, right))

        assertFalse(csv.contains(",=cmd"), "le nom de la feuille doit etre neutralise :\n$csv")
        assertTrue(csv.contains("'=cmd"), "le nom de la feuille doit etre neutralise :\n$csv")
    }

    /**
     * Only the dangerous values are changed: a normal text comes out exactly
     * as it is in the spreadsheet.
     */
    @Test
    fun `a normal value is not neutralised`() {
        val result = diffWith("Café à gogo", "Thé")

        val csv = DiffExporter.toCsv(result)

        assertTrue(csv.contains(",Café à gogo,Thé,,"), "un texte ne doit pas etre neutralise :\n$csv")
        assertFalse(csv.contains("'Café"), "un texte ne doit pas etre neutralise :\n$csv")
    }

    /**
     * A value that holds the separator is quoted, so that the columns are
     * still cut in the right place.
     */
    @Test
    fun `a value that holds a comma is quoted`() {
        val result = diffWith("a,b", "c,d")

        val csv = DiffExporter.toCsv(result)

        assertTrue(csv.contains("\"a,b\",\"c,d\""), "la valeur doit etre protegee :\n$csv")
    }

    /**
     * A dangerous value that also holds the separator is quoted *and*
     * neutralised.
     */
    @Test
    fun `a dangerous value that holds a comma is quoted and neutralised`() {
        val result = diffWith("=1,2", "x")

        val csv = DiffExporter.toCsv(result)

        assertTrue(csv.contains("\"'=1,2\""), "la valeur doit etre protegee :\n$csv")
        assertFalse(csv.contains(",=1,2,"), "la formule doit être neutralisée :\n$csv")
    }

    // ---------------------------------------------------------------- 7
    @Test
    fun `the text export is written in utf8 and read back identically`() {
        val result = diffWith("Café à gogo", "Thé")

        val file = path("diff.txt")
        DiffExporter.writeText(file, result)

        val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        assertEquals(DiffExporter.toText(result), text)
        assertTrue(text.contains("Café à gogo"), "l'accent a été perdu :\n$text")
    }

    /**
     * The text report is written in English, like the listing of the plugin.
     */
    @Test
    fun `the text export is written in english`() {
        val result = diffWith("10", "20")

        val text = DiffExporter.toText(result)

        assertTrue(text.contains("Left file:"), "rapport en anglais attendu :\n$text")
        assertTrue(text.contains("Right file:"), "rapport en anglais attendu :\n$text")
        assertFalse(text.contains("Fichier gauche"), "rapport en anglais attendu :\n$text")
    }

    // ---------------------------------------------------------------- 8
    /**
     * The exact format of the exported file, in one test:
     *
     *  - UTF-8 **with the byte order mark** at the very beginning;
     *  - the columns separated by commas (the separator of the CSV standard,
     *    RFC 4180: an English Excel, LibreOffice, Google Sheets and every
     *    script read the file as it is);
     *  - a value that Excel would read as a formula prefixed with an
     *    apostrophe.
     *
     * This is the test that proves the format of the file the user opens.
     */
    @Test
    fun `the exported file has the expected format`() {
        val left = path("gauche.xlsx")
        val right = path("droite.xlsx")
        Fx.create(left) { sheet("Données") { row("Produit", "=1+1") } }
        Fx.create(right) { sheet("Données") { row("Produit", "=2+2") } }
        val result = engine.diff(left, right)

        val file = path("diff.csv")
        DiffExporter.writeCsv(file, result)

        // 1. the byte order mark
        val bytes = Files.readAllBytes(file)
        assertEquals(0xEF.toByte(), bytes[0], "le BOM UTF-8 manque")
        assertEquals(0xBB.toByte(), bytes[1], "le BOM UTF-8 manque")
        assertEquals(0xBF.toByte(), bytes[2], "le BOM UTF-8 manque")

        // 2. the separator and the header
        val text = String(bytes, StandardCharsets.UTF_8).removePrefix(DiffExporter.UTF8_BOM)
        val lines = text.lines()
        assertEquals(
            "type,sheet,cell,row,column,left_value,right_value,left_formula,right_formula",
            lines.first(),
            "l'en-tete doit utiliser la virgule : ${lines.first()}",
        )
        assertEquals(9, lines.first().count { it == ',' } + 1, "l'en-tete doit avoir 9 colonnes")

        // 3. the apostrophe in front of a value that Excel would run
        assertEquals(
            "VALUE,Données,B1,1,B,'=1+1,'=2+2,,",
            lines[1],
            "la formule doit etre neutralisee : ${lines[1]}",
        )
    }
}
