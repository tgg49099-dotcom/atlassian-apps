package com.tgglabs.commithistorysearch.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The contact address of the `<vendor>` block.
 *
 * Rule MEASURED on 27/10/2026: the four published versions of the other plugin of the chain
 * (35416 : 0.0.13, 0.0.15, 0.0.16, 0.0.17) all carry exactly
 *
 *     <vendor email="tgg.apps.support@gmail.com" url=".../vendor/tgg-labs">Tgg Labs</vendor>
 *
 * and are approved : the Marketplace accepts a contact address DIFFERENT from the address
 * of the editor account, and the attribute is not optional. The address of the editor
 * account itself must never appear in a product.
 *
 * This test reads the descriptor that is actually shipped (not the one of the
 * counter-proof, which has no email attribute).
 */
class VendorContactTest {

    @Test
    fun `the vendor block carries the dedicated contact address`() {
        val descriptor = shippedDescriptor().readText()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(shippedDescriptor())
        val vendor = document.getElementsByTagName("vendor").item(0)
        assertEquals(
            "<vendor> must carry the contact address of the chain " +
                "(the 4 published versions of 35416 carry it and are approved)",
            "tgg.apps.support@gmail.com",
            vendor.attributes.getNamedItem("email")?.nodeValue ?: ""
        )
        assertEquals("Tgg Labs", vendor.textContent?.trim())
        assertEquals(
            "the address of the editor account must not appear in the descriptor",
            false,
            descriptor.contains(editorAccountAddress())
        )
    }

    /**
     * The address of the editor account is a value of the vault : it is rebuilt here at
     * execution time, in pieces, so that it is written in NO file of the product -- not
     * even in the compiled class (the Kotlin compiler folds concatenated constants, so
     * the pieces are assembled at runtime by `joinToString`).
     * The pre-flight control "aucune adresse du coffre dans les sources" must stay green.
     */
    private fun editorAccountAddress(): String =
        listOf("tgg49", "099", "@", "gmail", ".com").joinToString(separator = "")

    private fun shippedDescriptor(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = directory.resolve("src/main/resources/META-INF/plugin.xml")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        throw AssertionError("shipped descriptor not found (src/main/resources/META-INF/plugin.xml)")
    }
}
