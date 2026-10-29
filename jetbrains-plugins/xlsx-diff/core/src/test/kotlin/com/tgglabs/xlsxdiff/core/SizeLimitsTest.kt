package com.tgglabs.xlsxdiff.core

import org.apache.poi.ss.usermodel.WorkbookFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The limits of a comparison, and the files that are closed properly.
 *
 * A `.xlsx` is a ZIP archive of XML files: 20 MB on the disk can hold 2 GB
 * once decompressed, and the spreadsheet library loads the whole workbook in
 * memory. The plugin must refuse such a file with a clear message instead of
 * freezing the IDE.
 */
class SizeLimitsTest {

    @TempDir
    lateinit var tmp: Path

    private fun path(name: String): Path = tmp.resolve(name)

    private fun twoFiles(): Pair<Path, Path> {
        val left = path("gauche.xlsx")
        val right = path("droite.xlsx")
        Fx.create(left) { sheet("S") { row("A", 1.0); row("B", 2.0); row("C", 3.0) } }
        Fx.create(right) { sheet("S") { row("A", 9.0); row("B", 2.0); row("C", 3.0) } }
        return left to right
    }

    // ---------------------------------------------------------------- 1
    @Test
    fun `a file bigger than the limit on the disk is refused with a clear message`() {
        val (left, right) = twoFiles()
        // 10 bytes: any real .xlsx is bigger than that.
        val engine = XlsxDiffEngine(XlsxDiffLimits(maxFileBytes = 10))

        val error = assertThrows(XlsxDiffException::class.java) { engine.diff(left, right) }

        val message = error.message ?: ""
        assertTrue(message.contains("too large"), "message attendu : $message")
        assertTrue(message.contains("gauche.xlsx"), "le fichier fautif doit etre nomme : $message")
        assertTrue(message.contains("on disk"), "la taille doit etre dite : $message")
    }

    // ---------------------------------------------------------------- 2
    @Test
    fun `a file bigger than the limit once decompressed is refused with a clear message`() {
        val (left, right) = twoFiles()
        // The file itself is small, but its entries are bigger than 100 bytes.
        val engine = XlsxDiffEngine(XlsxDiffLimits(maxUncompressedBytes = 100))

        val error = assertThrows(XlsxDiffException::class.java) { engine.diff(left, right) }

        val message = error.message ?: ""
        assertTrue(message.contains("too large"), "message attendu : $message")
        assertTrue(message.contains("decompressed"), "la taille decompressee doit etre dite : $message")
    }

    // ---------------------------------------------------------------- 3
    @Test
    fun `a workbook with too many cells is refused with a clear message`() {
        val (left, right) = twoFiles()
        val engine = XlsxDiffEngine(XlsxDiffLimits(maxCells = 3))

        val error = assertThrows(XlsxDiffException::class.java) { engine.diff(left, right) }

        val message = error.message ?: ""
        assertTrue(message.contains("too many cells"), "message attendu : $message")
    }

    // ---------------------------------------------------------------- 4
    @Test
    fun `a file under the limits is compared normally`() {
        val (left, right) = twoFiles()

        val result = XlsxDiffEngine().diff(left, right)

        assertEquals(1, result.modifiedCellCount, "une cellule doit avoir change")
    }

    /**
     * The limit asked for by the jury: 20 MB on the disk for one file.
     * The test pins the number, so that nobody changes it without noticing.
     *
     * 27/10/2026 (jury) : the decompressed limit went from 100 MB to 50 MB
     * (100 MB of XML can take more than 1 GB in the heap of the IDE).
     */
    @Test
    fun `the default limit is 20 MB on the disk`() {
        assertEquals(20L * 1024 * 1024, XlsxDiffLimits.DEFAULT_MAX_FILE_BYTES)
        assertEquals(50L * 1024 * 1024, XlsxDiffLimits.DEFAULT_MAX_UNCOMPRESSED_BYTES)
    }

    // ---------------------------------------------------------------- 5
    /**
     * When the second file cannot be opened, the first workbook must be
     * closed: otherwise the file stays open for the whole session of the IDE.
     */
    @Test
    fun `the first workbook is closed when the second one cannot be opened`() {
        val (left, _) = twoFiles()
        val missing = path("absent.xlsx")
        Assumptions.assumeTrue(canCountDescriptors(), "le systeme ne permet pas de compter les descripteurs")

        // The counting method must see an open workbook, otherwise the test
        // below would prove nothing.
        val workbook = WorkbookFactory.create(left.toFile(), null as String?, true)
        assertEquals(1, openDescriptorsFor("gauche.xlsx"), "le descripteur du fichier ouvert doit etre vu")
        workbook.close()
        assertEquals(0, openDescriptorsFor("gauche.xlsx"), "le descripteur doit etre libere")

        val error = assertThrows(XlsxDiffException::class.java) {
            XlsxDiffEngine().diff(left, missing)
        }
        assertTrue(error.message?.contains("not found") == true, "message attendu : ${error.message}")
        assertEquals(0, openDescriptorsFor("gauche.xlsx"), "le premier classeur n'a pas ete ferme")
    }

    // ---------------------------------------------------------------- outils
    private fun canCountDescriptors(): Boolean = Files.isDirectory(Paths.get("/proc/self/fd"))

    /**
     * The number of file descriptors of this process that point at [fileName].
     */
    private fun openDescriptorsFor(fileName: String): Int {
        val dir = Paths.get("/proc/self/fd")
        if (!Files.isDirectory(dir)) return -1
        Files.list(dir).use { stream ->
            return stream.filter { link ->
                try {
                    Files.readSymbolicLink(link).toString().contains(fileName)
                } catch (e: Exception) {
                    false
                }
            }.count().toInt()
        }
    }
}
