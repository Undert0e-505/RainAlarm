package com.rainalarm.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test fun `language choices expose exact stable tags and native names`() {
        assertEquals(
            listOf(null, "en", "nl", "nl-BE", "de", "fr", "cy", "ga"),
            AppLanguagePolicy.options.map { it.tag },
        )
        assertEquals(
            listOf("Device language", "English", "Nederlands", "Nederlands (België)",
                "Deutsch", "Français", "Cymraeg", "Gaeilge"),
            AppLanguagePolicy.options.map { it.nativeName },
        )
        assertEquals("nl-BE", AppLanguagePolicy.normalizedTag("nl-be"))
        assertEquals("fr", AppLanguagePolicy.normalizedTag("fr,de"))
        assertNull(AppLanguagePolicy.normalizedTag(""))
        assertNull(AppLanguagePolicy.normalizedTag("es"))
    }

    @Test fun `fresh use waits for a language choice while upgrades do not regress`() {
        assertFalse(LanguageChoiceGatePolicy.initialCompletion(freshInstall = true, storedCompletion = null))
        assertTrue(LanguageChoiceGatePolicy.initialCompletion(freshInstall = false, storedCompletion = null))
        assertFalse(LanguageChoiceGatePolicy.initialCompletion(freshInstall = false, storedCompletion = false))
        assertTrue(LanguageChoiceGatePolicy.initialCompletion(freshInstall = true, storedCompletion = true))
        assertTrue(LanguageChoiceGatePolicy.showChoice(startupReady = true, completed = false))
        assertFalse(LanguageChoiceGatePolicy.showChoice(startupReady = false, completed = false))
        assertFalse(LanguageChoiceGatePolicy.showChoice(startupReady = true, completed = true))
        assertFalse(LanguageChoiceGatePolicy.showChoice(startupReady = true, completed = null))
    }
}
