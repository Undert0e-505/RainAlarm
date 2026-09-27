package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourcesTest {
    private val resRoot: Path by lazy {
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("app/src/main/res") }
            .first { Files.isDirectory(it) }
    }

    private data class Resources(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
    )

    private fun resources(folder: String): Resources {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(resRoot.resolve(folder).resolve("strings.xml").toFile())
        val strings = document.getElementsByTagName("string").let { nodes ->
            (0 until nodes.length).associate { index ->
                val element = nodes.item(index) as Element
                element.getAttribute("name") to element.textContent
            }
        }
        val plurals = document.getElementsByTagName("plurals").let { nodes ->
            (0 until nodes.length).associate { index ->
                val element = nodes.item(index) as Element
                val items = element.getElementsByTagName("item")
                element.getAttribute("name") to (0 until items.length).associate { itemIndex ->
                    val item = items.item(itemIndex) as Element
                    item.getAttribute("quantity") to item.textContent
                }
            }
        }
        return Resources(strings, plurals)
    }

    private fun placeholders(value: String): List<String> =
        Regex("%(?:\\d+\\$)?[a-zA-Z]").findAll(value.replace("%%", ""))
            .map { it.value }.sorted().toList()

    @Test fun `all full locales match English keys placeholders and plural quantities`() {
        val english = resources("values")
        listOf("values-nl", "values-de", "values-fr", "values-cy", "values-ga").forEach { folder ->
            val translated = resources(folder)
            assertEquals("string keys for $folder", english.strings.keys, translated.strings.keys)
            english.strings.forEach { (key, source) ->
                assertEquals("placeholders for $folder/$key", placeholders(source),
                    placeholders(requireNotNull(translated.strings[key])))
            }
            assertEquals("plural keys for $folder", english.plurals.keys, translated.plurals.keys)
            english.plurals.forEach { (key, sourceItems) ->
                val translatedItems = requireNotNull(translated.plurals[key])
                assertTrue("source plural quantities for $folder/$key",
                    translatedItems.keys.containsAll(sourceItems.keys))
                sourceItems.forEach { (quantity, source) ->
                    assertEquals("plural placeholders for $folder/$key/$quantity",
                        placeholders(source), placeholders(requireNotNull(translatedItems[quantity])))
                }
                val sourceFallback = requireNotNull(sourceItems["other"])
                translatedItems
                    .filterKeys { it !in sourceItems }
                    .forEach { (quantity, translatedValue) ->
                        assertEquals(
                            "extra plural placeholders for $folder/$key/$quantity",
                            placeholders(sourceFallback),
                            placeholders(translatedValue),
                        )
                    }
            }
        }
        assertEquals(
            setOf("zero", "one", "two", "few", "many", "other"),
            resources("values-cy").plurals.getValue("notification_eta_plural").keys,
        )
        assertEquals(
            setOf("one", "two", "few", "many", "other"),
            resources("values-ga").plurals.getValue("notification_eta_plural").keys,
        )
        assertEquals(
            setOf("one", "many", "other"),
            resources("values-fr").plurals.getValue("notification_eta_plural").keys,
        )
    }

    @Test fun `Belgian Dutch overrides are genuine subset and inherit complete Dutch base`() {
        val dutch = resources("values-nl")
        val belgian = resources("values-nl-rBE")
        assertTrue(belgian.strings.isNotEmpty())
        assertTrue(dutch.strings.keys.containsAll(belgian.strings.keys))
        belgian.strings.forEach { (key, value) ->
            assertEquals(placeholders(requireNotNull(dutch.strings[key])), placeholders(value))
        }
        assertTrue(dutch.plurals.keys.containsAll(belgian.plurals.keys))
    }

    @Test fun `locale config exposes the agreed static language resources`() {
        val text = Files.readString(resRoot.resolve("xml/locales_config.xml"))
        listOf("en", "nl", "nl-BE", "de", "fr", "cy", "ga").forEach { locale ->
            assertTrue("missing $locale", text.contains("android:name=\"$locale\""))
        }
        assertTrue(text.indexOf("android:name=\"nl\"") < text.indexOf("android:name=\"nl-BE\""))
    }
}
