package ceo.villa.matty.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ceo.villa.matty.data.api.CustomEmoji
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.api.PostImage
import ceo.villa.matty.data.emoji.EmojiStore
import ceo.villa.matty.data.repo.ChatRepository
import ceo.villa.matty.ui.ChatActions
import ceo.villa.matty.ui.LocalActions
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.theme.Matty
import ceo.villa.matty.ui.components.leftBar
import ceo.villa.matty.ui.theme.MattyColors
import coil3.compose.AsyncImage

/** Renders Mattermost markdown natively. */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Matty.colors.text,
    images: Map<String, PostImage> = emptyMap(),
    edited: Boolean = false,
) {
    val blocks = remember(text) { Markdown.parse(text) }
    val repo = LocalRepo.current
    val customs by repo.customEmojis.collectAsState()
    val jumbo = remember(text, customs.size) { EmojiStore.isEmojiOnly(text, customs.keys) }
    val baseStyle = if (jumbo) style.copy(fontSize = 34.sp, lineHeight = 42.sp) else style
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEachIndexed { idx, b ->
            MdBlockView(b, baseStyle, color, images, edited = edited && idx == blocks.lastIndex)
        }
        if (edited && blocks.lastOrNull() !is MdBlock.Paragraph) {
            Text("(edited)", style = MaterialTheme.typography.labelSmall, color = Matty.colors.textTertiary)
        }
    }
}

@Composable
private fun MdBlockView(b: MdBlock, style: TextStyle, color: Color, images: Map<String, PostImage>, edited: Boolean) {
    val c = Matty.colors
    when (b) {
        is MdBlock.Paragraph -> InlineText(b.inlines, style, color, edited)
        is MdBlock.Heading -> {
            val size = when (b.level) { 1 -> 24.sp; 2 -> 21.sp; 3 -> 19.sp; else -> 17.sp }
            InlineText(b.inlines, style.copy(fontSize = size, lineHeight = size * 1.25f, fontWeight = FontWeight.Bold), c.title, edited)
        }
        is MdBlock.Code -> CodeBlock(b)
        is MdBlock.Quote -> Row(Modifier.leftBar(c.selected)) {
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                b.blocks.forEach { MdBlockView(it, style, color, images, false) }
            }
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.items.forEachIndexed { idx, item ->
                Row {
                    val t = b.tasks.getOrNull(idx)
                    when {
                        t != null -> Icon(if (t) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null,
                            tint = if (t) c.accent else c.textSecondary, modifier = Modifier.padding(top = 2.dp, end = 6.dp).size(18.dp))
                        b.ordered -> Text("${b.start + idx}.", style = style, color = c.textSecondary, modifier = Modifier.widthIn(min = 22.dp).padding(end = 4.dp))
                        else -> Text("•", style = style, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, end = 8.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { item.forEach { MdBlockView(it, style, color, images, false) } }
                }
            }
        }
        is MdBlock.Table -> TableBlock(b, style, color)
        is MdBlock.Image -> PostMediaImage(url = b.url, meta = images[b.url], alt = b.alt)
        MdBlock.Rule -> HorizontalDivider(color = c.divider, modifier = Modifier.padding(vertical = 6.dp))
    }
}

@Composable
private fun InlineText(inlines: List<MdInline>, style: TextStyle, color: Color, edited: Boolean, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val repo = LocalRepo.current
    val actions = LocalActions.current
    val colors = Matty.colors
    val me by repo.me.collectAsState()
    val customs by repo.customEmojis.collectAsState()
    val myName = me?.username.orEmpty()
    // Only this text's own mentions are looked up, and it is rebuilt only when what they draw
    // changes, not whenever anybody the app knows changes.
    val names = remember(inlines) { Mentions.names(inlines) }
    val labels = repo.mentionLabels.collectAsState()
    val drawn by remember(names, labels) {
        derivedStateOf(structuralEqualityPolicy()) { names.associateWith { Mentions.drawn(it, labels.value) } }
    }
    val built = remember(inlines, myName, customs, colors, edited, interactive, drawn) {
        buildInline(inlines, colors, myName, customs, actions, edited, interactive, drawn)
    }
    val inline = remember(built.second, customs) {
        built.second.associateWith { name ->
            InlineTextContent(Placeholder(1.3.em, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
                val em = customs[name]
                if (em != null) AsyncImage(model = repo.client.customEmojiUrl(em.id), contentDescription = ":$name:", modifier = Modifier.fillMaxWidth())
            }
        }.mapKeys { "ce:${it.key}" }
    }
    // Mention chips: rounded shapes behind their words, from where the words were laid out. They
    // are worked out once per layout and only drawn after that, so scrolling costs nothing new.
    val chips = remember(built.first) { built.first.getStringAnnotations(ChipTag, 0, built.first.length) }
    var boxes by remember { mutableStateOf(emptyList<ChipBox>()) }
    val fontPx = with(LocalDensity.current) { (if (style.fontSize.isSp) style.fontSize else 16.sp).toPx() }
    val chipOther = colors.accent.copy(alpha = 0.14f)
    val chipSelf = colors.away.copy(alpha = 0.28f)
    // Where each web link goes, for a long press on it; the layout it was found in is kept
    // aside, not as state, so a new layout never recomposes anything.
    val hrefs = remember(built.first) { built.first.getStringAnnotations(LinkUrlTag, 0, built.first.length) }
    val laidOut = remember { arrayOfNulls<TextLayoutResult>(1) }
    val haptics = LocalHapticFeedback.current
    val withChips = if (chips.isEmpty()) modifier else modifier.drawBehind {
        val corner = CornerRadius(6.dp.toPx())
        for (b in boxes) drawRoundRect(b.color, b.topLeft, b.size, corner)
    }
    Text(built.first, style = style, color = color, inlineContent = inline, maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            laidOut[0] = layout
            if (chips.isNotEmpty()) boxes = chipBoxes(layout, chips, fontPx, chipOther, chipSelf)
        },
        modifier = if (hrefs.isEmpty()) withChips else withChips.pointerInput(hrefs) {
            // A long press on a link shows where it goes (to copy, or open). It is watched
            // before the link sees it, so the link's own tap doesn't follow when the finger lifts;
            // a tap, or a press that moves (a scroll), is left to the link and the list.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val layout = laidOut[0] ?: return@awaitEachGesture
                val href = linkAt(layout, hrefs, down.position) ?: return@awaitEachGesture
                val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || change.isConsumed) break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                    }
                    true
                }
                if (ended != null) return@awaitEachGesture
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                actions.showLink(href)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        })
}

/** Where the web link under [position] goes, if one is there (and not merely the nearest character on another line). */
private fun linkAt(layout: TextLayoutResult, hrefs: List<AnnotatedString.Range<String>>, position: Offset): String? {
    val line = layout.getLineForVerticalPosition(position.y)
    if (position.y < layout.getLineTop(line) || position.y > layout.getLineBottom(line)) return null
    if (position.x < layout.getLineLeft(line) || position.x > layout.getLineRight(line)) return null
    val at = layout.getOffsetForPosition(position)
    return hrefs.firstOrNull { at >= it.start && at < it.end }?.item
}

/** The annotation that says where a web link goes, for a long press on it. */
internal const val LinkUrlTag = "link-url"

/** The annotation that marks a mention drawn as a chip; its item says whose colours it takes. */
internal const val ChipTag = "mention-chip"
internal const val ChipOther = "other"
internal const val ChipSelf = "self"

/** The room inside a chip at each end, in ems of its text: about 4dp at the message size. */
private const val ChipRoomEm = 0.25f

private class ChipBox(val color: Color, val topLeft: Offset, val size: Size)

/**
 * One rounded box per line each chip touches (a chip broken over two lines is two pieces), from
 * the boxes of its own characters on that line. Vertically it is sized from the line's baseline
 * and the text's size, kept inside the line, so it never changes how tall a line is.
 */
private fun chipBoxes(
    layout: TextLayoutResult, chips: List<AnnotatedString.Range<String>>, font: Float, other: Color, self: Color,
): List<ChipBox> {
    val text = layout.layoutInput.text.text
    val room = font * ChipRoomEm
    val out = ArrayList<ChipBox>(chips.size)
    for (chip in chips) {
        if (chip.start >= chip.end || chip.end > text.length) continue
        val color = if (chip.item == ChipSelf) self else other
        for (line in layout.getLineForOffset(chip.start)..layout.getLineForOffset(chip.end - 1)) {
            val from = maxOf(chip.start, layout.getLineStart(line))
            // Only what the line shows: a line cut short with "…" ends where the ellipsis begins,
            // so a chip in the cut-off part draws nothing and one cut part-way stops before it.
            var to = minOf(chip.end, layout.getLineEnd(line, visibleEnd = true))
            // A name with a space in it, broken there: the space left at the line's end isn't drawn.
            while (to > from && text[to - 1].isWhitespace()) to--
            if (to <= from) continue
            // Every character's own box, not just the first and last: in right-to-left text the
            // "@" can be drawn at one end of the name and its last letter right beside it.
            var left = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            for (i in from until to) {
                val r = layout.getBoundingBox(i)
                if (r.left < left) left = r.left
                if (r.right > right) right = r.right
            }
            left -= room
            right += room
            val baseline = layout.getLineBaseline(line)
            val top = maxOf(baseline - 0.9f * font, layout.getLineTop(line))
            val bottom = minOf(baseline + 0.3f * font, layout.getLineBottom(line))
            if (bottom > top) out += ChipBox(color, Offset(left, top), Size(right - left, bottom - top))
        }
    }
    return out
}

/**
 * Makes the room inside each chip's ends by spacing out the character just outside it (an
 * ordinary space, as a rule), so the words, and what is copied or read out, stay exactly as they
 * are. A chip at the start of a line has nothing there to space out: its room hangs into the margin.
 */
internal fun withChipRoom(s: AnnotatedString): AnnotatedString {
    val chips = s.getStringAnnotations(ChipTag, 0, s.length)
    if (chips.isEmpty()) return s
    // How many chip ends each character borders: one space between two chips ("@alice @bob")
    // has both chips' room drawn into it.
    val sides = HashMap<Int, Int>()
    for (chip in chips) {
        if (chip.start > 0) sides.merge(chip.start - 1, 1, Int::plus)
        if (chip.end < s.length) sides.merge(chip.end, 1, Int::plus)
    }
    // Never a character of a chip itself: its words keep their own spacing.
    sides.keys.removeAll { i -> chips.any { i >= it.start && i < it.end } }
    val text = s.text
    return AnnotatedString.Builder(s).apply {
        for ((i, n) in sides) {
            if (text[i] == '\n') continue
            addStyle(SpanStyle(letterSpacing = chipRoomSpacing(text[i], n).em), i, i + 1)
        }
    }.toAnnotatedString()
}

/**
 * The spacing, in ems, given to a character just outside [chips] chip ends. A space's spacing
 * and its own width are one gap, which each chip beside it draws its room into: a little more
 * than the room of every chip keeps the gap a lone chip leaves before the next word, also between
 * two chips. Anything else (a comma, a bracket) has half of the spacing on each side of it, and
 * each half holds one chip's room and a little gap, so it is the same whether one chip or two
 * are beside it.
 */
internal fun chipRoomSpacing(c: Char, chips: Int): Float =
    if (c.isWhitespace()) chips * ChipRoomEm + 0.05f else 2 * (ChipRoomEm + 0.05f)

/**
 * A line of Markdown's inline formatting (bold, italics, code, links, emoji, mentions) where a
 * whole message would be too much: a channel's purpose on the chat's header pill, for one.
 */
@Composable
fun InlineMarkdown(
    text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE,
    /** False where the text sits on something tappable (the header pill): styled, but taps go through. */
    interactive: Boolean = true,
) {
    val inlines = remember(text) { Markdown.parseInlines(text) }
    InlineText(inlines, style, color, edited = false, maxLines = maxLines, modifier = modifier, interactive = interactive)
}

internal fun buildInline(
    inlines: List<MdInline>,
    c: MattyColors,
    myName: String,
    customs: Map<String, CustomEmoji>,
    actions: ChatActions,
    edited: Boolean,
    interactive: Boolean = true,
    mentions: Map<String, Mentions.Drawn> = emptyMap(),
): Pair<AnnotatedString, Set<String>> {
    val customUsed = HashSet<String>()
    val linkStyle = TextLinkStyles(SpanStyle(color = c.link))
    // Their tints are chips drawn behind the words (see InlineText), not a span's square background.
    val mentionStyle = TextLinkStyles(SpanStyle(color = c.accent, fontWeight = FontWeight.Medium))
    val selfMention = TextLinkStyles(SpanStyle(color = c.title, fontWeight = FontWeight.SemiBold))
    // Inline `code` in VillaNET's code colours too.
    val code = CodeTheme.of(c.isDark)
    val s = buildAnnotatedString {
        // A link's look without its tap, where the text sits on something tappable of its own.
        fun link(tag: String, styles: TextLinkStyles, onClick: () -> Unit, body: () -> Unit) {
            if (interactive) withLink(LinkAnnotation.Clickable(tag, styles) { onClick() }) { body() }
            else withStyle(styles.style ?: SpanStyle()) { body() }
        }
        fun chip(kind: String, body: () -> Unit) {
            pushStringAnnotation(ChipTag, kind)
            body()
            pop()
        }
        // Inside a link's words, an address written there is only words: one tap, the link's own.
        var inLink = false
        fun walk(list: List<MdInline>) {
            for (el in list) when (el) {
                is MdInline.Text -> append(el.text)
                is MdInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walk(el.children) }
                is MdInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walk(el.children) }
                is MdInline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { walk(el.children) }
                is MdInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = code.inlineBackground, fontSize = 0.9.em, color = code.inlineText)) {
                    append(" "); append(el.code); append(" ")
                }
                is MdInline.Link -> if (inLink) walk(el.children) else {
                    // Words that are themselves an address of another site than the link's: the
                    // tap asks first, naming where it really goes.
                    val elsewhere = linkTextNamesAnotherHost(shownText(el.children), el.url)
                    if (interactive) pushStringAnnotation(LinkUrlTag, el.url)
                    inLink = true
                    link("url", linkStyle, { if (elsewhere != null) actions.confirmUrl(el.url, elsewhere) else actions.openUrl(el.url) }) { walk(el.children) }
                    inLink = false
                    if (interactive) pop()
                }
                is MdInline.Mention -> {
                    val special = el.name in Mentions.special
                    // @here/@channel/@all have nobody to open: styled, never a dead tap target.
                    if (special) chip(ChipSelf) { withStyle(selfMention.style ?: SpanStyle()) { append("@"); append(el.name) } }
                    else {
                        // Somebody known by their name, still opened and told apart as me by username.
                        val d = mentions[el.name] ?: Mentions.Drawn(null, el.name, "")
                        val who = d.username ?: el.name
                        val self = who.equals(myName, true)
                        val styles = if (self) selfMention else mentionStyle
                        val open = { actions.openUsername(who) }
                        // A name nobody has isn't a mention of anybody: its words, in the mention's colour, and no chip.
                        if (d.username == null) link("mention", styles, open) { append("@"); append(d.label) }
                        else chip(if (self) ChipSelf else ChipOther) { link("mention", styles, open) { append("@"); append(d.label) } }
                        append(d.rest)
                    }
                }
                // A channel had the mentions' square tint too: a chip like theirs.
                is MdInline.ChannelLink -> chip(ChipOther) {
                    link("channel", mentionStyle, { actions.openChannelByName(el.name) }) { append("~"); append(el.name) }
                }
                is MdInline.Emoji -> {
                    val uni = EmojiStore.unicode(el.name)
                    when {
                        uni != null -> append(uni)
                        customs.containsKey(el.name) -> { customUsed += el.name; appendInlineContent("ce:${el.name}", ":${el.name}:") }
                        else -> append(":${el.name}:")
                    }
                }
                is MdInline.Hashtag -> link("tag", linkStyle, { actions.search("#${el.tag}") }) {
                    append("#"); append(el.tag)
                }
                MdInline.LineBreak -> append('\n')
            }
        }
        walk(inlines)
        if (edited) withStyle(SpanStyle(color = c.textTertiary, fontSize = 0.72.em)) { append("  (edited)") }
    }
    return withChipRoom(s) to customUsed
}

@Composable
private fun CodeBlock(b: MdBlock.Code) {
    val c = Matty.colors
    val t = CodeTheme.of(c.isDark)
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(14.dp)
    // VillaNET's code block: near-black inside a thin separator-red line, tokens in its reds.
    Column(Modifier.fillMaxWidth().clip(shape).background(t.background).border(1.dp, t.border, shape)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(b.lang.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall, color = t.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(16.dp))
                    .clickable { clipboard.setText(AnnotatedString(b.code)) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.ContentCopy, "Copy code", tint = t.comment, modifier = Modifier.size(16.dp))
            }
        }
        val highlighted = remember(b.code, b.lang, t) { highlightCode(b.code, b.lang, t) }
        Text(
            highlighted,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
            color = t.plain,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        )
    }
}

@Composable
private fun TableBlock(b: MdBlock.Table, style: TextStyle, color: Color) {
    val c = Matty.colors
    Column(Modifier.clip(RoundedCornerShape(12.dp)).background(c.well).horizontalScroll(rememberScrollState())) {
        val cols = maxOf(b.header.size, b.rows.maxOfOrNull { it.size } ?: 0)
        Row(Modifier.background(c.pill)) {
            for (i in 0 until cols) Box(Modifier.width(140.dp).padding(8.dp)) {
                b.header.getOrNull(i)?.let { InlineText(it, style.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), c.title, false) }
            }
        }
        b.rows.forEach { row ->
            Row {
                for (i in 0 until cols) Box(Modifier.width(140.dp).padding(8.dp)) {
                    row.getOrNull(i)?.let { InlineText(it, style.copy(fontSize = 14.sp), color, false) }
                }
            }
        }
    }
}

/** One-line plain text for previews, notifications and reply quotes. */
fun plainPreview(post: Post, repo: ChatRepository): String {
    val text = Markdown.plain(post.message) { EmojiStore.unicode(it) }
    if (text.isNotBlank()) return text
    val att = post.attachments.firstOrNull()
    if (att != null) return att.fallback.ifBlank { att.title.ifBlank { att.text } }
    val files = post.metadata.files.orEmpty()
    return when {
        files.isEmpty() -> ""
        files.all { it.isImage } -> if (files.size == 1) "Sent an image" else "Sent ${files.size} images"
        else -> "Sent ${files.first().name}"
    }.also { repo.hashCode() }
}
