package ceo.villa.matty

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import ceo.villa.matty.data.api.FileInfo
import ceo.villa.matty.ui.ChatActions
import ceo.villa.matty.ui.chat.ChipOther
import ceo.villa.matty.ui.chat.ChipSelf
import ceo.villa.matty.ui.chat.ChipTag
import ceo.villa.matty.ui.chat.Markdown
import ceo.villa.matty.ui.chat.Mentions
import ceo.villa.matty.ui.chat.buildInline
import ceo.villa.matty.ui.chat.chipRoomSpacing
import ceo.villa.matty.ui.theme.Palette
import ceo.villa.matty.ui.theme.mattyColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MentionChipsTest {
    private val labels = mapOf("itzdave" to "Dave", "vic" to "Vic V")
    private val colors = mattyColors(Palette.Oled, Color(0xFF8AB4FF))

    private object NoActions : ChatActions {
        override fun openUser(userId: String) {}
        override fun openUsername(username: String) {}
        override fun openChannel(channelId: String) {}
        override fun openChannelByName(name: String) {}
        override fun openThread(rootId: String) {}
        override fun openUrl(url: String) {}
        override fun openMedia(url: String, isVideo: Boolean, title: String) {}
        override fun openFile(file: FileInfo) {}
        override fun search(terms: String) {}
        override fun openPlugin(pluginId: String, args: Map<String, String>) {}
        override fun messageUser(userId: String) {}
    }

    private fun build(message: String, me: String = "vic"): AnnotatedString {
        val inlines = Markdown.parseInlines(message)
        val drawn = Mentions.names(inlines).associateWith { Mentions.drawn(it, labels) }
        return buildInline(inlines, colors, me, emptyMap(), NoActions, edited = false, mentions = drawn).first
    }

    /** Each chip as the words it covers and whose colours it takes. */
    private fun chips(s: AnnotatedString) = s.getStringAnnotations(ChipTag, 0, s.length).map { s.text.substring(it.start, it.end) to it.item }

    @Test fun somebodyKnownAndEverybodyAreChips() {
        assertEquals(listOf("@Dave" to ChipOther, "@here" to ChipSelf), chips(build("hey @itzdave and @here")))
        // Their own mention keeps its own colours, as a chip too.
        assertEquals(listOf("@Vic V" to ChipSelf), chips(build("ping @vic")))
        // The rest of the token after a known name stays outside the chip.
        assertEquals(listOf("@Dave" to ChipOther), chips(build("@itzdave.thanks")))
    }

    @Test fun aNameNobodyHasIsTextWithItsTapAndNoChip() {
        val s = build("ask @nobody about it")
        assertEquals(emptyList(), chips(s))
        assertEquals("ask @nobody about it", s.text)
        val tap = s.getLinkAnnotations(0, s.length).single()
        assertEquals("mention", (tap.item as LinkAnnotation.Clickable).tag)
        assertEquals("@nobody", s.text.substring(tap.start, tap.end))
    }

    @Test fun theWordsAndWhatIsCopiedAreUnchanged() {
        assertEquals("hey @Dave, and @here", build("hey @itzdave, and @here").text)
        assertEquals("@Dave said hi", build("@itzdave said hi").text)
        // Bold, italics and a link around a mention keep their words, and its chip.
        val s = build("**@itzdave** _see @here_ [@itzdave](https://example.com)")
        assertEquals("@Dave see @here @Dave", s.text)
        assertEquals(listOf("@Dave" to ChipOther, "@here" to ChipSelf, "@Dave" to ChipOther), chips(s))
    }

    @Test fun theRoomInsideAChipComesFromItsNeighboursSpacing() {
        val s = build("hey @itzdave, ok")
        val spaced = s.spanStyles.filter { it.item.letterSpacing.isEm }.map { it.start }
        // The space before "@Dave" and the comma after it: nothing inside the chip, nothing else.
        assertEquals(listOf(3, 9), spaced.sorted())
        // A chip at the very start has nothing before it to space out.
        val first = build("@itzdave hi")
        assertEquals(listOf(5), first.spanStyles.filter { it.item.letterSpacing.isEm }.map { it.start })
        assertTrue(build("nobody here").spanStyles.none { it.item.letterSpacing.isEm })
    }

    @Test fun oneSpaceBetweenTwoChipsHoldsBothChipsRoom() {
        // "@Dave @Vic V": the one space between them borders two chips, so it carries the room
        // of both and the same gap a lone chip leaves before the next word.
        val s = build("@itzdave @vic")
        val shared = s.spanStyles.single { it.item.letterSpacing.isEm }
        assertEquals(5, shared.start)
        assertEquals(chipRoomSpacing(' ', 2), shared.item.letterSpacing.value, 1e-6f)
        assertEquals(0.55f, chipRoomSpacing(' ', 2), 1e-6f)
        // One chip beside a word is as before: a space 0.30em, a comma 0.6em.
        val lone = build("hey @itzdave, ok").spanStyles.filter { it.item.letterSpacing.isEm }.associate { it.start to it.item.letterSpacing.value }
        assertEquals(0.30f, lone.getValue(3), 1e-6f)
        assertEquals(0.60f, lone.getValue(9), 1e-6f)
        // A comma between two chips: half of its spacing on each side already holds a chip's room.
        val comma = build("@itzdave,@vic").spanStyles.single { it.item.letterSpacing.isEm }
        assertEquals(0.60f, comma.item.letterSpacing.value, 1e-6f)
    }
}
