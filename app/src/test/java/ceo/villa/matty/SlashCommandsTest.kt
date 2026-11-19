package ceo.villa.matty

import ceo.villa.matty.data.api.AutocompleteSuggestion
import ceo.villa.matty.ui.chat.commandLabel
import ceo.villa.matty.ui.chat.completeCommand
import org.junit.Assert.assertEquals
import org.junit.Test

/** Shapes as Mattermost 11's autocomplete_suggestions answers them (checked against a live server). */
class SlashCommandsTest {
    @Test
    fun pickingACommandKeepsItsSlash() {
        // "/" -> Complete "code": the box must read "/code ", not "code".
        assertEquals("/code ", completeCommand("code"))
        assertEquals("/me ", completeCommand("me"))
    }

    @Test
    fun subcommandsCompleteTheWholeCommand() {
        // "/call s" -> Complete "call start".
        assertEquals("/call start ", completeCommand("call start"))
    }

    @Test
    fun anArgumentHintAddsNoSecondSpace() {
        // "/call start " -> Complete "call start " (Suggestion "", a hint row).
        assertEquals("/call start ", completeCommand("call start "))
    }

    @Test
    fun aSlashAlreadyThereIsNotDoubled() {
        assertEquals("/code ", completeCommand("/code"))
    }

    @Test
    fun labelsShowCommandsWithTheirSlashAndSubcommandsBare() {
        assertEquals("/code", commandLabel(AutocompleteSuggestion(complete = "code", suggestion = "code", hint = "[text]")))
        assertEquals("start", commandLabel(AutocompleteSuggestion(complete = "call start", suggestion = "start")))
        assertEquals("/call start", commandLabel(AutocompleteSuggestion(complete = "call start ", suggestion = "", hint = "Root message for the call")))
    }
}
