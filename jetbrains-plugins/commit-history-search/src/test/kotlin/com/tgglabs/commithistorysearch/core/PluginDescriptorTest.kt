package com.tgglabs.commithistorysearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks of the plugin descriptor, that is the XML file IntelliJ reads when it loads the
 * plugin.
 *
 * They exist because of a bug measured on 26/10/2026: the build was green, the tests were
 * green, and the IDE refused the plugin with
 * "action element should have specified \"class\" attribute" followed by
 * "contains invalid plugin descriptor" : the plugin was NEVER loaded. A green build does
 * not prove that a plugin loads, and these checks are the cheapest way to catch that class
 * of mistake before the ZIP leaves the container.
 *
 * The directory of the descriptors can be pointed elsewhere with the environment variable
 * TGG_DESCRIPTOR_DIR : that is how the counter-proof runs the same tests against the
 * descriptors of the previous version.
 */
class PluginDescriptorTest {

    @Test
    fun `every action element declares a class attribute`() {
        val actions = actions()
        assertTrue("no action element found in the descriptors", actions.isNotEmpty())
        for ((element, file) in actions) {
            val id = element.getAttribute("id")
            val className = element.getAttribute("class")
            assertTrue(
                "action '$id' of $file has no class attribute: IntelliJ 2024.3 then refuses " +
                    "the whole descriptor (invalid plugin descriptor)",
                className.isNotBlank()
            )
        }
    }

    @Test
    fun `the descriptor has no license element`() {
        for (file in descriptorFiles()) {
            val document = parse(file)
            assertEquals(
                "the <license> element is not a valid root element for IntelliJ 2024.3 " +
                    "(\"Unknown element: license\") in $file",
                0,
                document.getElementsByTagName("license").length
            )
        }
    }

    @Test
    fun `the description and the change notes are not empty`() {
        val document = parse(descriptorDir().resolve("plugin.xml"))
        assertTrue("the description is empty", text(document, "description").isNotBlank())
        assertTrue("the change-notes are empty", text(document, "change-notes").isNotBlank())
    }

    @Test
    fun `the plugin name is short enough and free of forbidden words`() {
        val name = text(parse(descriptorDir().resolve("plugin.xml")), "name")
        assertTrue("the name '$name' is longer than 30 characters", name.length <= 30)
        for (word in listOf("plugin", "intellij", "jetbrains")) {
            assertFalse("the name '$name' contains the forbidden word '$word'", name.lowercase().contains(word))
        }
    }

    @Test
    fun `the action is attached to the Git menu of the IDE`() {
        val document = parse(descriptorDir().resolve("plugin.xml"))
        val groups = (0 until document.getElementsByTagName("add-to-group").length)
            .map { document.getElementsByTagName("add-to-group").item(it) }
            .map { (it as Element).getAttribute("group-id") }
        assertTrue(
            "no action is attached to the group \"Git.MainMenu\": the plugin would have no " +
                "visible entry point in the IDE (groups found: $groups)",
            groups.contains("Git.MainMenu")
        )
    }

    @Test
    fun `the two icons are 40 by 40 svg files`() {
        for (name in listOf("pluginIcon.svg", "pluginIcon_dark.svg")) {
            val file = descriptorDir().resolve(name)
            assertTrue("$name is missing", file.isFile)
            val content = file.readText()
            val width = size(content, "width")
            val height = size(content, "height")
            assertEquals("$name must be 40 wide", 40, width)
            assertEquals("$name must be 40 high", 40, height)
        }
    }

    @Test
    fun `the description states the real limit of the scan`() {
        // git's --max-count limits the number of commits DISPLAYED, not the number of commits
        // SCANNED (measured on 29/10/2026 with git 2.39.5): the listing must not claim the
        // opposite. The false sentence ("the scan covers the 500 most recent commits") was
        // published in the description and in the change notes of the version 0.0.1 reviewed
        // on 28/10/2026.
        val description = text(parse(descriptorDir().resolve("plugin.xml")), "description")
        assertFalse(
            "the description still claims that the scan stops at the 500 most recent commits: " +
                "that is false, --max-count limits the number of results, not the depth of the scan",
            description.contains("500 most recent commits")
        )
        assertTrue(
            "the description must say that the scan covers the whole history",
            description.contains("whole history")
        )

        // The build overwrites the description of the descriptor with the one of
        // build.gradle.kts (pluginConfiguration): it is that text which ends up in the
        // delivered descriptor, so it must say the same thing.
        val gradle = gradleFile().readText()
        assertFalse(
            "build.gradle.kts still claims that the scan stops at the 500 most recent commits: " +
                "this is the text that ends up in the delivered descriptor",
            gradle.contains("500 most recent commits")
        )
        assertTrue(
            "build.gradle.kts must say that the scan covers the whole history",
            gradle.contains("whole history")
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun gradleFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = directory.resolve("build.gradle.kts")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        throw AssertionError("build.gradle.kts not found")
    }

    private fun descriptorDir(): File {
        System.getenv("TGG_DESCRIPTOR_DIR")?.let { return File(it) }
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = directory.resolve("src/main/resources/META-INF")
            if (candidate.isDirectory) return candidate
            directory = directory.parentFile
        }
        throw AssertionError("descriptor directory not found (src/main/resources/META-INF)")
    }

    private fun descriptorFiles(): List<File> =
        descriptorDir().listFiles { file: File -> file.name.endsWith(".xml") }?.sorted() ?: emptyList()

    private fun actions(): List<Pair<Element, File>> {
        val result = ArrayList<Pair<Element, File>>()
        for (file in descriptorFiles()) {
            val nodes = parse(file).getElementsByTagName("action")
            for (index in 0 until nodes.length) {
                result.add(Pair(nodes.item(index) as Element, file))
            }
        }
        return result
    }

    private fun parse(file: File): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file)
    }

    private fun text(document: org.w3c.dom.Document, tag: String): String {
        val nodes = document.getElementsByTagName(tag)
        if (nodes.length == 0) return ""
        val node: Node = nodes.item(0)
        return node.textContent?.trim() ?: ""
    }

    private fun size(content: String, attribute: String): Int {
        val match = Regex(attribute + "=\"(\\d+)\"").find(content)
        return match?.groupValues?.get(1)?.toInt() ?: -1
    }
}
