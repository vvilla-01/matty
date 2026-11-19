package ceo.villa.matty

import ceo.villa.matty.plugins.villanet.VillaNetApi
import ceo.villa.matty.ui.chat.Markdown
import ceo.villa.matty.ui.chat.MdBlock
import ceo.villa.matty.ui.chat.MdInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownTest {
    private fun inlines(s: String) = (Markdown.parse(s).single() as MdBlock.Paragraph).inlines

    @Test fun mentionsChannelsEmojiAndTags() {
        val i = inlines("hey @bob. see ~off-topic :tada: #release-notes")
        assertTrue(i.any { it == MdInline.Mention("bob") }, "trailing dot is not part of the name")
        assertTrue(i.any { it == MdInline.ChannelLink("off-topic") })
        assertTrue(i.any { it == MdInline.Emoji("tada") })
        assertTrue(i.any { it == MdInline.Hashtag("release-notes") })
    }

    @Test fun emphasisAndCode() {
        val i = inlines("**bold** *it* ~~gone~~ `x = 1` snake_case_name")
        assertIs<MdInline.Bold>(i[0])
        assertTrue(i.any { it is MdInline.Italic })
        assertTrue(i.any { it is MdInline.Strike })
        assertTrue(i.any { it == MdInline.Code("x = 1") })
        assertFalse(i.any { it is MdInline.Italic && it.children.toString().contains("case") }, "underscores inside words are literal")
    }

    @Test fun autolinksDropTrailingPunctuation() {
        val i = inlines("see https://example.com/a(b), ok")
        val link = i.filterIsInstance<MdInline.Link>().single()
        assertEquals("https://example.com/a(b)", link.url)
    }

    @Test fun gifMarkdownBecomesImageBlock() {
        val b = Markdown.parse("look\n![party](https://media.giphy.com/x.gif)")
        assertIs<MdBlock.Paragraph>(b[0])
        assertEquals(MdBlock.Image("party", "https://media.giphy.com/x.gif"), b[1])
    }

    @Test fun blocks() {
        val b = Markdown.parse("# Title\n\n```kotlin\nval a = 1\n```\n> quoted\n\n- one\n- [x] two\n\n| a | b |\n|---|---|\n| 1 | 2 |")
        assertIs<MdBlock.Heading>(b[0])
        assertEquals(MdBlock.Code("kotlin", "val a = 1"), b[1])
        assertIs<MdBlock.Quote>(b[2])
        val list = assertIs<MdBlock.ListBlock>(b[3])
        assertEquals(listOf(null, true), list.tasks)
        val table = assertIs<MdBlock.Table>(b[4])
        assertEquals(1, table.rows.size)
    }

    @Test fun plainPreview() {
        assertEquals("hi 🎉 GIF", Markdown.plain("**hi** :tada:\n![g](https://x/y.gif)") { if (it == "tada") "🎉" else null })
    }

    @Test fun villaNetTokenGuard() {
        assertTrue(VillaNetApi.looksLikeToken("Bearer 0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertTrue(VillaNetApi.looksLikeToken("  0F8FAD5B-D9CB-469F-A165-70867728950E "))
        assertFalse(VillaNetApi.looksLikeToken("my token is 0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertEquals("", VillaNetApi.cleanToken("Bearer"))
        assertEquals("abc", VillaNetApi.cleanToken(" bearer   abc "))
    }

    @Test fun indentedCodeAsSlashCodePostsIt() {
        // Mattermost's /code posts "    " + the text.
        assertEquals(listOf<MdBlock>(MdBlock.Code("", "hello from slash code")), Markdown.parse("    hello from slash code"))
        // Lines (and blank lines) keep their own indentation past the first four spaces; trailing blanks go.
        assertEquals(listOf<MdBlock>(MdBlock.Code("", "fun f() {\n    return 1\n\n}")), Markdown.parse("    fun f() {\n        return 1\n\n    }\n\n"))
        assertEquals(listOf<MdBlock>(MdBlock.Code("", "x")), Markdown.parse("\tx"))
    }

    @Test fun anIndentedLineInsideAParagraphIsJustText() {
        val blocks = Markdown.parse("first line\n    still the same paragraph")
        assertIs<MdBlock.Paragraph>(blocks.single())
        // After a blank line it's code, as CommonMark has it.
        val after = Markdown.parse("text\n\n    code")
        assertIs<MdBlock.Paragraph>(after[0]); assertEquals(MdBlock.Code("", "code"), after[1])
    }

    @Test fun indentedLinesInsideListsStayInTheList() {
        // A nested list after a blank line.
        val nested = Markdown.parse("- a\n\n    - b").single()
        assertIs<MdBlock.ListBlock>(nested)
        assertIs<MdBlock.ListBlock>(nested.items.single().last())
        // A second paragraph of the same item.
        val more = Markdown.parse("- item\n\n    more").single()
        assertIs<MdBlock.ListBlock>(more)
        assertEquals(2, more.items.single().size)
        assertTrue(more.items.single().all { it is MdBlock.Paragraph })
        // A fence inside an item is still a fence.
        val fenced = Markdown.parse("- run:\n\n    ```bash\n    ls\n    ```").single()
        assertIs<MdBlock.ListBlock>(fenced)
        assertEquals(MdBlock.Code("bash", "ls"), fenced.items.single().last())
        // Not indented into the item: the list ends, as before.
        val after = Markdown.parse("- a\n\nafter")
        assertIs<MdBlock.ListBlock>(after[0]); assertIs<MdBlock.Paragraph>(after[1])
    }

    @Test fun anIndentedLineCarriesOnAQuote() {
        val q = Markdown.parse("> a\n    b").single()
        assertIs<MdBlock.Quote>(q)
        assertIs<MdBlock.Paragraph>(q.blocks.single())
        // After a blank line, it's code after the quote.
        val after = Markdown.parse("> a\n\n    b")
        assertIs<MdBlock.Quote>(after[0]); assertEquals(MdBlock.Code("", "b"), after[1])
    }

    @Test fun tabsCountAsFourColumns() {
        // CommonMark's tab example: one item, two paragraphs, no code.
        val item = Markdown.parse("- foo\n\n\tbar").single()
        assertIs<MdBlock.ListBlock>(item)
        assertEquals(2, item.items.single().size)
        val nested = Markdown.parse("- a\n\n\t- b").single()
        assertIs<MdBlock.ListBlock>(nested)
        assertIs<MdBlock.ListBlock>(nested.items.single().last())
        assertEquals(listOf<MdBlock>(MdBlock.Code("", "x")), Markdown.parse("\tx"))
    }

    @Test fun lazyQuoteLinesStayText() {
        // A pasted comment after a quoted line isn't a heading inside the quote.
        val q = Markdown.parse("> q\n    # c\n    x").single()
        assertIs<MdBlock.Quote>(q)
        assertIs<MdBlock.Paragraph>(q.blocks.single())
    }

    @Test fun numberedItemsMeasureTheirOwnMarker() {
        // Six spaces under "1." after a blank line: a second paragraph of the item, not code.
        val steps = Markdown.parse("1. Configure:\n\n      set x=1\n\n2. done").first()
        assertIs<MdBlock.ListBlock>(steps)
        assertTrue(steps.items.first().none { it is MdBlock.Code })
        assertEquals(2, steps.items.first().size)
        // Under "10." a six-space bullet list nests rather than turning into code.
        val wide = Markdown.parse("10. Restart\n\n      - check logs\n      - restart").single()
        assertIs<MdBlock.ListBlock>(wide)
        assertIs<MdBlock.ListBlock>(wide.items.single().last())
        // Two-space nesting under numbers stays nested, three deep.
        val deep = Markdown.parse("1. a\n  1. b\n    1. c").single()
        assertIs<MdBlock.ListBlock>(deep)
        val inner = deep.items.single().last()
        assertIs<MdBlock.ListBlock>(inner)
        assertIs<MdBlock.ListBlock>(inner.items.single().last())
    }
}
