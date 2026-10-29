package com.tgglabs.xlsxdiff.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The "Cancel" button of the progress bar.
 *
 * When the user cancels, the comparison must stop and **nothing** must be
 * shown: no table, no error message.
 */
class CancellationTest {

    @TempDir
    lateinit var tmp: Path

    private val engine = XlsxDiffEngine()

    private fun path(name: String): Path = tmp.resolve(name)

    /** Two files that differ on one cell. */
    private fun twoFiles(): Pair<Path, Path> {
        val left = path("gauche.xlsx")
        val right = path("droite.xlsx")
        Fx.create(left) { sheet("S") { row("A", 10.0); row("B", "x") } }
        Fx.create(right) { sheet("S") { row("A", 20.0); row("B", "x") } }
        return left to right
    }

    // ---------------------------------------------------------------- 1
    @Test
    fun `a cancelled comparison shows no result at all`() {
        val (left, right) = twoFiles()

        val result = CompareTask.run(left, right) { true }

        assertNull(result, "une comparaison annulee ne doit montrer aucun résultat")
    }

    // ---------------------------------------------------------------- 2
    @Test
    fun `the engine stops as soon as it is cancelled`() {
        val (left, right) = twoFiles()

        assertThrows<XlsxDiffCancelledException> {
            engine.diff(left, right) { true }
        }
    }

    // ---------------------------------------------------------------- 3
    @Test
    fun `a comparison that is not cancelled gives a result`() {
        val (left, right) = twoFiles()

        val result = CompareTask.run(left, right) { false }

        assertNotNull(result, "une comparaison normale doit donner un résultat")
        assertEquals(1, result!!.modifiedCellCount)
    }

    // ---------------------------------------------------------------- 4
    @Test
    fun `a cancellation that comes late still stops the comparison`() {
        val (left, right) = twoFiles()
        var checks = 0
        // The user presses "Cancel" after the comparison has started.
        val cancellation = Cancellation {
            checks++
            checks > 2
        }

        val result = CompareTask.run(left, right, cancellation)

        assertNull(result, "une comparaison annulee ne doit montrer aucun résultat")
        assertTrue(checks > 2, "l'annulation doit avoir été vue pendant la comparaison")
    }
}
