package ceo.villa.matty

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import ceo.villa.matty.ui.chat.CodeTheme
import ceo.villa.matty.ui.chat.highlightCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlightTest {
    private val t = CodeTheme.Dark

    /** The colour of the last style covering [word]'s first character. */
    private fun AnnotatedString.colorOf(word: String, from: Int = 0): Color? {
        val at = text.indexOf(word, from)
        require(at >= 0) { "$word not in $text" }
        return spanStyles.filter { it.start <= at && at < it.end }.lastOrNull { it.item.color != Color.Unspecified }?.item?.color
    }

    @Test
    fun kotlinTokens() {
        val s = highlightCode("@Composable\nfun Tile(clip: VnClip) {\n    // note\n    val n = 42\n    if (n > 1) show(\"hi\")\n}", "kotlin", t)
        assertEquals(t.annotation, s.colorOf("@Composable"))
        assertEquals(t.keyword, s.colorOf("fun"))
        assertEquals(t.function, s.colorOf("Tile"))
        assertEquals(t.type, s.colorOf("VnClip"))
        assertEquals(t.comment, s.colorOf("// note"))
        assertEquals(t.number, s.colorOf("42"))
        assertEquals(t.function, s.colorOf("show"))
        assertEquals(t.string, s.colorOf("\"hi\""))
        assertEquals(t.punctuation, s.colorOf("{"))
    }

    @Test
    fun jsonKeysAndValues() {
        val s = highlightCode("{\"streamKey\": \"victor\", \"live\": true, \"viewers\": 3}", "json", t)
        assertEquals(t.property, s.colorOf("\"streamKey\""))
        assertEquals(t.string, s.colorOf("\"victor\""))
        assertEquals(t.literal, s.colorOf("true"))
        assertEquals(t.number, s.colorOf("3"))
    }

    @Test
    fun pythonHashCommentsButNotSlashes() {
        val s = highlightCode("x = 7 // 2  # floor\nprint(x)", "python", t)
        assertEquals(t.comment, s.colorOf("# floor"))
        // `//` is floor division in Python, not a comment.
        assertEquals(t.number, s.colorOf("2"))
    }

    @Test
    fun rustLifetimesAreNotStrings() {
        val s = highlightCode("fn longest<'a>(x: &'a str) -> &'a str { let c = 'z'; let n = '\\n'; x }", "rust", t)
        // Two lifetimes on one line must not pair up into a "string" running between them.
        assertEquals(t.plain, s.colorOf("x:"))
        assertEquals(t.type, s.colorOf("'a"))
        assertEquals(t.string, s.colorOf("'z'"))
        assertEquals(t.string, s.colorOf("'\\n'"))
    }

    @Test
    fun shellPastesStayReadable() {
        // A glob is a path, not the start of a block comment that greys out the rest.
        val glob = highlightCode("ls /etc/nginx/*\nif true; then echo ok; fi", "", t)
        assertEquals(t.keyword, glob.colorOf("then"))
        val flags = highlightCode("npm install --save-dev typescript && echo done", "", t)
        assertEquals(t.plain, flags.colorOf("typescript"))
        val url = highlightCode("curl https://example.com/api # fetch", "bash", t)
        assertEquals(t.plain, url.colorOf("example"))
        assertEquals(t.comment, url.colorOf("# fetch"))
        val args = highlightCode("if [ \$# -lt 1 ]; then n=\${#arr[@]}; fi", "bash", t)
        assertEquals(t.keyword, args.colorOf("then"))
        assertEquals(t.keyword, args.colorOf("fi"))
    }

    @Test
    fun plainTextAndProse() {
        val text = highlightCode("The server isn't up for Bob", "text", t)
        assertTrue(text.spanStyles.all { it.item.color == t.plain })
        // Unlabelled: an apostrophe in a word doesn't open a string, and capitals aren't types.
        val prose = highlightCode("It's down, don't restart Bob's box", "", t)
        assertEquals(t.plain, prose.colorOf("restart"))
        assertEquals(t.plain, prose.colorOf("Bob"))
        // …but Python's f'…' is still a string.
        val py = highlightCode("print(f'{name} joined')", "python", t)
        assertEquals(t.string, py.colorOf("joined"))
    }

    @Test
    fun diffLines() {
        val d = highlightCode("--- a/x.kt\n+++ b/x.kt\n@@ -1 +1 @@\n-val a = 1\n+val a = 2", "diff", t)
        assertEquals(t.label, d.colorOf("--- a"))
        assertEquals(t.comment, d.colorOf("@@"))
        assertEquals(t.keyword, d.colorOf("-val"))
        assertEquals(t.string, d.colorOf("+val"))
    }

    @Test
    fun brokenInputNeverThrows() {
        listOf(
            "\"unterminated", "'", "\\", "\"ends with a backslash\\", "/* never closed", "\"\"\"", "@", "0x", "<", "</",
            "😀 emoji \"😀\" // 😀", "#", "--", "`multi\nline", "a".repeat(5_000),
        ).forEach { code ->
            listOf("", "kotlin", "python", "sql", "html", "json").forEach { lang ->
                val s = highlightCode(code, lang, t)
                assertEquals(code, s.text)
                assertTrue(s.spanStyles.all { it.start in 0..code.length && it.end in it.start..code.length })
            }
        }
    }

    @Test
    fun aBigPasteIsQuick() {
        val code = (1..2_000).joinToString("\n") { "val item$it = compute(\"value $it\", $it) // line $it" }
        val start = System.nanoTime()
        highlightCode(code, "kotlin", t)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took ${ms}ms", ms < 1_500)
    }
}
