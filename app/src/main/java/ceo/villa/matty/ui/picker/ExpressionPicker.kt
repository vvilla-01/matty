package ceo.villa.matty.ui.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import ceo.villa.matty.ui.chat.MarkdownText
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ceo.villa.matty.container
import ceo.villa.matty.data.api.GiphyGif
import ceo.villa.matty.data.emoji.EmojiStore
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.components.ChipPill
import ceo.villa.matty.ui.components.CenteredLoading
import ceo.villa.matty.ui.theme.Matty
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Discord's expression picker: emoji (Mattermost's set plus the server's custom emoji) and
 * GIFs (Giphy, through the key the server hands out, exactly like the desktop app).
 */
@Composable
fun ExpressionPicker(
    startOnGif: Boolean,
    gifsEnabled: Boolean,
    onEmoji: (String) -> Unit,
    onGif: ((GiphyGif) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val c = Matty.colors
    var gif by rememberSaveable { mutableStateOf(startOnGif && gifsEnabled && onGif != null) }
    // Opened from the GIF button: the search has the keyboard at once. A tab chosen by hand doesn't.
    var typeAtOnce by remember { mutableStateOf(gif) }
    if (startOnGif) KeyboardBackOnClose()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
            if (gifsEnabled && onGif != null) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipPill("Emoji", selected = !gif) { gif = false; typeAtOnce = false }
                    ChipPill("GIFs", selected = gif) { gif = true; typeAtOnce = false }
                }
                Spacer(Modifier.height(10.dp))
            }
            if (gif && onGif != null) GifTab(onGif, if (typeAtOnce) sheet else null) else EmojiTab(onEmoji)
        }
    }
}

/**
 * The sheet is a window of its own, so its search takes the keyboard from the message box. If the
 * box had it when the picker opened, it has it again once the picker is gone: asked for a few
 * times, briefly, as the box's window only takes the keyboard back once it has the focus again.
 */
@Composable
private fun KeyboardBackOnClose() {
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val had = remember { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
    DisposableEffect(Unit) {
        onDispose { if (had) showKeyboardAgain(view, keyboard, tries = 15) }
    }
}

private fun showKeyboardAgain(view: android.view.View, keyboard: SoftwareKeyboardController?, tries: Int) {
    view.postDelayed({
        val up = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (up || tries <= 0 || !view.isAttachedToWindow) return@postDelayed
        keyboard?.show()
        showKeyboardAgain(view, keyboard, tries - 1)
    }, 100)
}

@Composable
private fun SearchField(
    value: String, placeholder: String, modifier: Modifier = Modifier.padding(horizontal = 16.dp),
    focus: FocusRequester? = null, onChange: (String) -> Unit,
) {
    val c = Matty.colors
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(c.pill).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = c.textTertiary, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                cursorBrush = SolidColor(c.accent), modifier = if (focus != null) Modifier.fillMaxWidth().focusRequester(focus) else Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EmojiTab(onEmoji: (String) -> Unit) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val settings by LocalContext.current.container.settings.state.collectAsState()
    val customs by repo.customEmojis.collectAsState()
    var query by remember { mutableStateOf("") }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val results = remember(query) { if (query.isBlank()) null else EmojiStore.search(query, 120) }
    val customResults = remember(query, customs) { if (query.isBlank()) customs.values.sortedBy { it.name } else customs.values.filter { it.name.contains(query.lowercase()) } }
    // Header index of each category, for the quick-jump chips.
    val sections = remember(customs.isEmpty()) {
        buildList {
            add("recent" to "Recent")
            if (customs.isNotEmpty()) add("custom" to "Custom")
            addAll(EmojiStore.categories)
        }
    }
    SearchField(query, "Find the perfect emoji") { query = it }
    Spacer(Modifier.height(8.dp))
    if (results == null) {
        val chips = androidx.compose.foundation.lazy.rememberLazyListState()
        val chipScroll = ceo.villa.matty.ui.components.rememberSheetScrolling(horizontal = true) { chips.canScrollForward || chips.canScrollBackward }
        LazyRow(
            Modifier.nestedScroll(chipScroll.connection), state = chips, overscrollEffect = chipScroll.overscroll,
            contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(sections) { (key, label) ->
                ChipPill(label) {
                    scope.launch {
                        var idx = 0
                        var found = -1
                        for ((k, _) in sections) {
                            if (k == key) { found = idx; break }
                            idx += 1 + when (k) {
                                "recent" -> settings.recentEmojis.size
                                "custom" -> customs.size
                                else -> EmojiStore.pickable.count { it.category == k }
                            }
                        }
                        if (found >= 0) grid.animateScrollToItem(found)
                    }
                }
            }
        }
    }
    // Bounces at either end straight away, not after the sheet has had the fling (see rememberSheetScrolling).
    val sheetScroll = ceo.villa.matty.ui.components.rememberSheetScrolling { grid.canScrollForward || grid.canScrollBackward }
    LazyVerticalGrid(GridCells.Adaptive(46.dp), state = grid, contentPadding = PaddingValues(12.dp),
        modifier = Modifier.fillMaxSize().nestedScroll(sheetScroll.connection), overscrollEffect = sheetScroll.overscroll) {
        if (results != null) {
            items(customResults, key = { "c" + it.id }) { e -> CustomCell(repo.client.customEmojiUrl(e.id)) { onEmoji(e.name) } }
            items(results, key = { "r" + it.unicode }) { e -> EmojiCell(e.unicode) { onEmoji(e.name) } }
            return@LazyVerticalGrid
        }
        header("Recent")
        items(settings.recentEmojis, key = { "recent:$it" }) { name ->
            val uni = EmojiStore.unicode(name)
            val custom = customs[name]
            when {
                uni != null -> EmojiCell(uni) { onEmoji(name) }
                custom != null -> CustomCell(repo.client.customEmojiUrl(custom.id)) { onEmoji(name) }
                else -> Box(Modifier.size(46.dp))
            }
        }
        if (customs.isNotEmpty()) {
            header("Custom · ${repo.siteName}")
            items(customResults, key = { "custom:" + it.id }) { e -> CustomCell(repo.client.customEmojiUrl(e.id)) { onEmoji(e.name) } }
        }
        EmojiStore.categories.forEach { (key, label) ->
            header(label)
            items(EmojiStore.pickable.filter { it.category == key }, key = { "e:" + it.unicode + it.name }) { e -> EmojiCell(e.unicode) { onEmoji(e.name) } }
        }
    }
    @Suppress("UNUSED_EXPRESSION") c
}

/**
 * The server's custom emoji, floating just above the composer once there's text in it (the
 * keyboard already has the standard ones). The top shows the message as it will be sent, so
 * each emoji can be seen landing in it; the popup stays open for picking several.
 */
@Composable
fun CustomEmojiPopup(
    draft: String,
    onEmoji: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp = 440.dp,
) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val customs by repo.customEmojis.collectAsState()
    var query by remember { mutableStateOf("") }
    val list = remember(query, customs) {
        val q = query.trim().trim(':').lowercase()
        customs.values.filter { q.isEmpty() || it.name.contains(q) }.sortedBy { it.name }
    }
    // Keep the end of a long message in view, where the emoji goes.
    val preview = rememberScrollState()
    LaunchedEffect(draft, preview.maxValue) { preview.scrollTo(preview.maxValue) }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).heightIn(max = maxHeight)
            .shadow(16.dp, Matty.CardShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(Matty.CardShape).background(c.card)
            // Floats over the conversation: touches on the card stay on the card.
            .pointerInput(Unit) {},
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${repo.siteName} emoji", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                color = c.textSecondary, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, "Close emoji", tint = c.icon, modifier = Modifier.size(20.dp))
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(20.dp)).background(c.pill)
                .heightIn(max = 104.dp).verticalScroll(preview).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            MarkdownText(draft, style = MaterialTheme.typography.bodyLarge)
        }
        if (customs.size > 12) {
            Spacer(Modifier.height(8.dp))
            SearchField(query, "Search ${repo.siteName} emoji", Modifier.padding(horizontal = 12.dp)) { query = it }
        }
        if (list.isEmpty()) {
            Text(if (customs.isEmpty()) "${repo.siteName} has no custom emoji yet." else "No custom emoji match “$query”.",
                color = c.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
        } else {
            // Shrinks first when room is short, so the header and preview stay readable.
            LazyVerticalGrid(GridCells.Adaptive(50.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).weight(1f, fill = false)) {
                items(list, key = { "co:" + it.id }) { e -> CustomCell(repo.client.customEmojiUrl(e.id), e.name) { onEmoji(e.name) } }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.header(text: String) {
    item(span = { GridItemSpan(maxLineSpan) }, key = "h:$text") {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            color = Matty.colors.textSecondary, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp))
    }
}

@Composable
private fun EmojiCell(unicode: String, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(unicode, fontSize = 28.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CustomCell(url: String, name: String? = null, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        AsyncImage(model = url, contentDescription = name?.let { ":$it:" }, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun ColumnScope.GifTab(
    onGif: (GiphyGif) -> Unit,
    /** The sheet, when the search is to have the keyboard as soon as it's fully up. */
    typeOnceUp: SheetState? = null,
) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val giphy = LocalContext.current.container.giphy
    var query by remember { mutableStateOf("") }
    var gifs by remember { mutableStateOf<List<GiphyGif>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    val state = rememberLazyStaggeredGridState()
    val key = repo.giphyKey
    LaunchedEffect(query) {
        delay(if (query.isBlank()) 0 else 350)
        error = null
        gifs = null
        // A newer search cancels this one: that's not "GIFs aren't loading".
        gifs = ceo.villa.matty.data.runCatchingCancellable {
            if (query.isBlank()) giphy.trending(key) else giphy.search(key, query, lang = java.util.Locale.getDefault().language)
        }.onFailure { error = "GIFs aren't loading. The server's Giphy key may be over its limit — you can set your own in Settings." }
            .getOrDefault(emptyList())
    }
    val nearEnd by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it > (gifs?.size ?: 0) - 8 } == true } }
    LaunchedEffect(nearEnd) {
        val current = gifs ?: return@LaunchedEffect
        if (!nearEnd || loadingMore || current.isEmpty()) return@LaunchedEffect
        loadingMore = true
        val q = query
        try {
            ceo.villa.matty.data.runCatchingCancellable { if (q.isBlank()) giphy.trending(key, current.size) else giphy.search(key, q, current.size) }
                .onSuccess { more -> if (q == query) gifs = (current + more).distinctBy { it.id } }
        } finally {
            // Also when scrolling back up cancels this page: otherwise paging would stop for good.
            loadingMore = false
        }
    }
    val gifScroll = ceo.villa.matty.ui.components.rememberSheetScrolling { state.canScrollForward || state.canScrollBackward }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (typeOnceUp != null) LaunchedEffect(Unit) {
        // Not while the sheet slides in: arriving then, the keyboard would shove it about mid-way.
        snapshotFlow { typeOnceUp.currentValue }.first { it == SheetValue.Expanded }
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    SearchField(query, "Search Giphy", focus = focus) { query = it }
    Spacer(Modifier.height(8.dp))
    val list = gifs
    when {
        error != null -> Text(error!!, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
        list == null -> CenteredLoading()
        list.isEmpty() -> Text("No GIFs for “$query”.", color = c.textSecondary, modifier = Modifier.padding(24.dp))
        else -> LazyVerticalStaggeredGrid(
            StaggeredGridCells.Fixed(2), state = state, contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp,
            // Bounces at either end straight away, not after the sheet has had the fling.
            modifier = Modifier.weight(1f).nestedScroll(gifScroll.connection), overscrollEffect = gifScroll.overscroll,
        ) {
            items(list, key = { it.id }) { g ->
                val img = g.images.fixedWidthSmall.takeIf { it.url.isNotBlank() } ?: g.images.fixedHeight
                val w = img.width.toFloatOrNull() ?: 1f
                val h = img.height.toFloatOrNull() ?: 1f
                AsyncImage(
                    model = img.webp.ifBlank { img.url }, contentDescription = g.title, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio((w / h).coerceIn(0.5f, 2.5f)).clip(RoundedCornerShape(14.dp))
                        .background(c.pill).clickable { onGif(g) },
                )
            }
        }
    }
    Text("Powered by GIPHY", style = MaterialTheme.typography.labelSmall, color = c.textTertiary,
        modifier = Modifier.fillMaxWidth().padding(4.dp), textAlign = TextAlign.Center)
    @Suppress("UNUSED_EXPRESSION") CircleShape
}
