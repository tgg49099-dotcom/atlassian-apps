package com.tgglabs.xlsxdiff.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The descriptor of the plugin must declare the actions of the plugin.
 *
 * This is the exact point the jury of the 26/10/2026 blocked: the plugin was
 * sent with **no** `<actions>` and **no** `<extensions>` in `plugin.xml`, so
 * that it did nothing at all once installed ("plugin without functionality
 * visible" is a reason to refuse a plugin on the Marketplace).
 *
 * The test reads the source of the descriptor: if the file is not where it
 * should be, the test fails instead of passing silently.
 */
class PluginDescriptorTest {

    private val descriptor: String by lazy {
        val file = File("src/main/resources/META-INF/plugin.xml")
        assertTrue(file.isFile, "le descripteur du plugin est introuvable : ${file.absolutePath}")
        file.readText()
    }

    @Test
    fun `the descriptor declares the compare action in the project view menu`() {
        assertTrue(descriptor.contains("<actions>"), "le descripteur ne declare aucune action")
        assertTrue(
            descriptor.contains("class=\"com.tgglabs.xlsxdiff.ui.CompareWithAction\""),
            "l'action \"Compare XLSX Files\" n'est pas declaree",
        )
        assertTrue(
            descriptor.contains("text=\"Compare XLSX Files\""),
            "l'action ne porte pas le nom attendu",
        )
        assertTrue(
            descriptor.contains("group-id=\"ProjectViewPopupMenu\""),
            "l'action n'est pas dans le menu contextuel du Project view",
        )
    }

    @Test
    fun `the descriptor declares the csv export action`() {
        assertTrue(
            descriptor.contains("class=\"com.tgglabs.xlsxdiff.ui.ExportCsvAction\""),
            "l'action \"Export CSV…\" n'est pas declaree",
        )
    }

    @Test
    fun `the descriptor has no licence element`() {
        // IntelliJ IDEA 2024.3 refuses the whole descriptor when it reads a
        // <license> element ("Unknown element: license").
        assertTrue(
            !descriptor.contains("<license"),
            "le descripteur ne doit pas contenir de balise <license>",
        )
    }
}
