package ceo.villa.matty.ui.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import ceo.villa.matty.ui.picker.CustomEmojiPopup
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Movie
import ceo.villa.matty.container
import ceo.villa.matty.data.api.AutocompleteSuggestion
import ceo.villa.matty.data.api.Channel
import ceo.villa.matty.data.api.CustomEmoji
import ceo.villa.matty.data.api.FileInfo
import ceo.villa.matty.data.api.User
import ceo.villa.matty.data.emoji.EmojiEntry
import ceo.villa.matty.data.emoji.EmojiStore
import ceo.villa.matty.ui.LocalRepo
import ceo.villa.matty.ui.components.Avatar
import ceo.villa.matty.ui.home.BotTag
import ceo.villa.matty.ui.theme.Matty
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file being sent: it uploads as soon as it's picked, like Discord. */
class PendingUpload(val uri: Uri, name: String, mime: String, val size: Long) {
    /** What it goes up as: a picture drawn again to clean it changes type (a HEIC photo goes as a JPEG). */
    var name by mutableStateOf(name)
    var mime by mutableStateOf(mime)
    /** The upload itself: cancelled (and it stops sending) when the chip is removed or the session ends. */
    var job: Job? = null
    var progress by mutableStateOf(0f)
    var info by mutableStateOf<FileInfo?>(null)
    var failed by mutableStateOf(false)
}

/** In-memory drafts per channel/thread for this session. */
object Drafts {
    val map = HashMap<String, String>()

    /**
     * The files picked for each draft, kept with its text: the box going away (the server's lock
     * coming up, leaving the conversation) loses neither, and their uploads carry on in the
     * session meanwhile. Emptied when the message is sent, and at sign-out.
     */
    private val uploads = HashMap<String, SnapshotStateList<PendingUpload>>()

    fun uploads(key: String): SnapshotStateList<PendingUpload> = uploads.getOrPut(key) { mutableStateListOf() }

    /** Every picked file goes, and the uploads still running stop. */
    fun forgetUploads() {
        uploads.values.forEach { list -> list.forEach { it.job?.cancel() } }
        uploads.clear()
    }

    /** Drafts whose message box exists right now: those boxes add text to what's in them. */
    internal val live = HashSet<String>()

    /**
     * Text to add to a draft from outside its message box (Mention on a profile, Quote on a
     * message). A [block] (a quote) goes on lines of its own; anything else joins with a space.
     */
    class Added(val key: String, val text: String, val block: Boolean = false)

    /** Picked up straight away by the box that owns its key, once that box is on screen. */
    var added by mutableStateOf<Added?>(null)
        private set

    /**
     * Adds [text] to the end of the draft for [key] ("channelId|rootId"). Always through here,
     * never by writing [map]: a box already showing that draft only reads [map] when it's made,
     * so a direct write showed up only after leaving the conversation and coming back.
     */
    fun append(key: String, text: String, block: Boolean = false) {
        // No box for it right now: write it into the saved draft, which a box starts from.
        if (key !in live) map[key] = join(map[key].orEmpty(), text, block)
        added = Added(key, text, block)
    }

    internal fun join(now: String, text: String, block: Boolean = false) = when {
        now.isBlank() -> text
        block -> now.trimEnd() + "\n\n" + text
        else -> now.trimEnd() + " " + text
    }

    internal fun taken() { added = null }
}

private sealed interface Suggest {
    data class Users(val token: String, val list: List<User>) : Suggest
    data class Channels(val token: String, val list: List<Channel>) : Suggest
    data class Emojis(val token: String, val list: List<EmojiEntry>, val custom: List<CustomEmoji>) : Suggest
    data class Commands(val list: List<AutocompleteSuggestion>) : Suggest
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(
    channelId: String,
    rootId: String,
    placeholder: String,
    editing: Pair<String, String>?,
    onCancelEdit: () -> Unit,
    onOpenPicker: (mode: PickerMode, insert: (String) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    /** False while something covers this composer (a route on top, the chat panel slid away). */
    active: Boolean = true,
    /**
     * False once the pill itself is off screen (slid away with the chat, or under a full-screen
     * view). Unlike [active] it doesn't blink off while a channel switch settles, so the keyboard
     * only goes away when the box really does.
     */
    onScreen: Boolean = active,
    /**
     * Non-null only while the box has slid out of sight (scrolled away): brings it back. Typing on
     * a physical keyboard still reaches a box that can't be seen, so the first key shows it, and
     * Enter there shows the box rather than sending words nobody has read.
     */
    reveal: (() -> Unit)? = null,
) {
    val repo = LocalRepo.current
    val context = LocalContext.current
    val container = context.container
    val settings by container.settings.state.collectAsState()
    val customs by repo.customEmojis.collectAsState()
    val c = Matty.colors
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val draftKey = "$channelId|$rootId"
    // A restored draft carries on where it ends, so anything inserted lands after the text.
    var value by remember(draftKey) { mutableStateOf(Drafts.map[draftKey].orEmpty().let { TextFieldValue(it, TextRange(it.length)) }) }
    val uploads = remember(draftKey) { Drafts.uploads(draftKey) }
    var suggest by remember { mutableStateOf<Suggest?>(null) }
    var attachMenu by remember { mutableStateOf(false) }
    // A tap on + while the menu is open closes it as an outside touch; it mustn't then reopen it.
    // Decided when the finger goes down, so a slow press doesn't count as a fresh one.
    var attachClosedAt by remember { mutableStateOf(0L) }
    var pressedWhileOpen by remember { mutableStateOf(false) }
    val attachButton = remember { MutableInteractionSource() }
    LaunchedEffect(attachButton) {
        attachButton.interactions.collect {
            if (it is PressInteraction.Press) {
                pressedWhileOpen = attachMenu || android.os.SystemClock.uptimeMillis() - attachClosedAt < 300
            }
        }
    }
    var fieldFocused by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var blocked by remember { mutableStateOf<String?>(null) }
    // The edits on their way to the server, by post: one save each, shown while it's out.
    val savingEdits = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    val saving = editing != null && editing.first in savingEdits
    // The edit as it stands when a save comes back (it may have been cancelled meanwhile).
    val editingNow by androidx.compose.runtime.rememberUpdatedState(editing)

    /**
     * An edit borrows the box and never the conversation's draft: what was being written before
     * it comes back when the edit is saved or cancelled, and an edit that was refused or
     * abandoned never turns up later as an unsent new message.
     */
    fun restoreDraft() {
        val draft = Drafts.map[draftKey].orEmpty()
        value = TextFieldValue(draft, TextRange(draft.length))
    }
    // The server's custom emoji, floating above the box while there's text in it.
    var emojiPopup by remember { mutableStateOf(false) }
    // While it's up it has the bottom of the screen, as the keyboard does: the app-wide player bar
    // keeps out of the way. Per composer, so one going away only takes back its own.
    val popupOwner = remember { Any() }
    val popupUp = emojiPopup && active
    DisposableEffect(popupUp) {
        if (popupUp) ceo.villa.matty.ui.BottomChrome.popups += popupOwner
        onDispose { ceo.villa.matty.ui.BottomChrome.popups -= popupOwner }
    }
    var reopenKeyboard by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = androidx.compose.ui.platform.LocalView.current
    val imeVisible = WindowInsets.isImeVisible
    val field = remember { MutableInteractionSource() }
    val focus = remember { FocusRequester() }
    // Never more than about half the room left above the keyboard, so the box itself always fits.
    val density = LocalDensity.current
    val roomDp = with(density) { (LocalWindowInfo.current.containerSize.height - WindowInsets.ime.getBottom(density)).toDp() }
    val popupMax = (roomDp * 0.45f).coerceIn(160.dp, 440.dp)

    fun closeEmoji(showKeyboard: Boolean) {
        if (!emojiPopup) return
        emojiPopup = false
        if (showKeyboard) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    // Another conversation (the composer is reused across channels): start with it closed.
    LaunchedEffect(draftKey) { emojiPopup = false }
    // Covered by a thread, the media viewer or the channel list: close it, so Back goes to them.
    LaunchedEffect(active) { if (!active) closeEmoji(showKeyboard = false) }
    LaunchedEffect(editing) {
        if (editing != null) value = TextFieldValue(editing.second, TextRange(editing.second.length))
    }
    DisposableEffect(draftKey) {
        Drafts.live += draftKey
        onDispose { Drafts.live -= draftKey }
    }
    // A mention (or anything else) added from outside: onto what's in the box now (including
    // anything picked or autocompleted), cursor at the end, ready to type; once the box is in view.
    val added = Drafts.added
    LaunchedEffect(added, draftKey, onScreen) {
        val add = added ?: return@LaunchedEffect
        if (add.key != draftKey || !onScreen) return@LaunchedEffect
        Drafts.taken()
        val text = Drafts.join(value.text, add.text, add.block)
        value = TextFieldValue(text, TextRange(text.length))
        if (editingNow == null) Drafts.map[draftKey] = text
        // The profile sheet is a window of its own, closing as this runs: keep asking for the
        // cursor and keyboard (briefly) until the keyboard is really up.
        repeat(15) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
            delay(100)
            if (androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) return@LaunchedEffect
        }
    }

    /** Puts [uris] up in the conversation on screen now, in the order they were picked. */
    fun startUploads(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // The work belongs to the session, not to this box: it goes on (and its chips stay in
        // the draft) while the box is away, the server's lock in front of it say.
        val appContext = context.applicationContext
        val cr = appContext.contentResolver
        // Taken now: the conversation the files were picked for, whatever is on screen later.
        val channel = channelId
        val chips = uploads
        repo.scope.launch {
            for (uri in uris) {
                // Look before showing a chip: a file that can't be opened (a recording still being
                // saved, access the picker didn't grant) is reported, never silently skipped. Off
                // the main thread, as a provider can take its time answering.
                var name = "file"
                var size = -1L
                var mime = "application/octet-stream"
                val readable = withContext(Dispatchers.IO) {
                    ceo.villa.matty.data.runCatchingCancellable {
                        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                            if (cur.moveToFirst()) {
                                name = cur.getString(0) ?: name
                                size = if (cur.isNull(1)) -1 else cur.getLong(1)
                            }
                        }
                        cr.openAssetFileDescriptor(uri, "r")?.use { fd -> if (size < 0 && fd.length >= 0) size = fd.length } ?: error("no file")
                        cr.getType(uri)?.let { mime = it }
                    }
                }
                if (readable.isFailure) {
                    repo.emitNotice("Couldn't open ${if (name == "file") "that file" else name}. If it's a recording you just made, give it a moment to finish saving, then try again.")
                    continue
                }
                if (size == 0L) {
                    repo.emitNotice("${if (name == "file") "That file" else name} is empty, so there's nothing to send.")
                    continue
                }
                // Photos and videos go up under a random MarkOut-style name; documents keep theirs.
                val shownName = name
                val claimedMime = mime
                fun mediaName(type: String) = if (ceo.villa.matty.data.net.MediaNames.isMedia(type)) ceo.villa.matty.data.net.MediaNames.random(type, shownName) else shownName
                val up = PendingUpload(uri, mediaName(mime), mime, size)
                chips += up
                up.job = repo.scope.launch {
                    // Pictures and videos lose their hidden details (where and when, the camera)
                    // first; the copy that work makes goes once the upload is over, either way.
                    var ready: ceo.villa.matty.data.media.OutgoingMedia.Outcome.Ready? = null
                    try {
                        // Removing the chip or leaving the conversation cancels the upload: that isn't a failure.
                        ceo.villa.matty.data.runCatchingCancellable {
                            when (val out = ceo.villa.matty.data.media.OutgoingMedia.prepare(appContext, uri, claimedMime)) {
                                ceo.villa.matty.data.media.OutgoingMedia.Outcome.Refused -> {
                                    chips.remove(up)
                                    repo.emitNotice(ceo.villa.matty.data.media.notAttached(if (shownName == "file") "That file" else shownName))
                                    return@launch
                                }
                                ceo.villa.matty.data.media.OutgoingMedia.Outcome.NoRoom -> {
                                    chips.remove(up)
                                    repo.emitNotice(ceo.villa.matty.data.media.noRoomToClean(if (shownName == "file") "That file" else shownName))
                                    return@launch
                                }
                                ceo.villa.matty.data.media.OutgoingMedia.Outcome.AsIs -> Unit
                                is ceo.villa.matty.data.media.OutgoingMedia.Outcome.Ready -> {
                                    ready = out
                                    // Named for what it really is now, whatever the other app called it.
                                    if (out.mime != up.mime) { up.mime = out.mime; up.name = mediaName(out.mime) }
                                }
                            }
                            val r = ready
                            if (r != null) repo.client.uploadFile(channel, up.name, r.mime, r.size, { r.open() }) { p -> up.progress = p }
                            else repo.client.uploadFile(channel, up.name, up.mime, size, { cr.openInputStream(uri)!! }) { p -> up.progress = p }
                        }.onSuccess { up.info = it; up.progress = 1f }
                            .onFailure { up.failed = true; repo.emitNotice(repo.failureText("Couldn't upload ${up.name}", it)) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Removed, or the session ended: a chip that would wait for ever doesn't stay.
                        chips.remove(up)
                        throw e
                    } finally {
                        ready?.discard()
                    }
                }
            }
        }
    }
    // Contracts made once (a new one on every redraw re-registers the launcher each time), and ones
    // that tell "backed out" from "said OK but handed nothing over", which is worth saying.
    val mediaContract = remember { PickMedia() }
    val filesContract = remember { PickFiles() }
    fun picked(p: Picked) {
        if (p.ok && p.uris.isEmpty()) repo.emitNotice("Nothing came back from the picker. If it's a recording you just made, give it a moment to finish saving, then try again.")
        startUploads(p.uris)
    }
    // This box is reused as you switch conversations, and a picker answers after you've been away.
    // The handler is rebuilt on every redraw (never remembered), so what's picked always lands in
    // the conversation on screen. Remembered, it stayed bound to the first conversation the box
    // showed: the file uploaded there, and nothing appeared here.
    val pickMedia = rememberLauncherForActivityResult(mediaContract, @DontMemoize { picked(it) })
    val pickFiles = rememberLauncherForActivityResult(filesContract, @DontMemoize { picked(it) })

    // Something shared into Matty from another app lands only in the conversation the user
    // picks (Navigator sets the target), even the one already open; never just any composer
    // that happens to appear meanwhile.
    val shareTarget = ceo.villa.matty.ui.ShareInbox.target
    LaunchedEffect(draftKey, shareTarget) {
        val me = if (rootId.isEmpty()) channelId else "thread:$rootId"
        if (ceo.villa.matty.ui.ShareInbox.pending && shareTarget == me) {
            ceo.villa.matty.ui.ShareInbox.text?.let { shared ->
                if (editingNow != null) {
                    // An edit has the box, and what's shared in is a new message, not part of that
                    // old one: it goes to the conversation's draft, which comes back when the edit
                    // is saved or cancelled.
                    val draft = Drafts.map[draftKey].orEmpty()
                    Drafts.map[draftKey] = if (draft.isBlank()) shared else draft.trimEnd() + "\n" + shared
                    repo.emitNotice("Added to your draft. It's there when you finish editing")
                } else {
                    // Added to whatever was already being written there, and kept as the draft.
                    val t = if (value.text.isBlank()) shared else value.text.trimEnd() + "\n" + shared
                    value = TextFieldValue(t, TextRange(t.length))
                    Drafts.map[draftKey] = t
                }
            }
            startUploads(ceo.villa.matty.ui.ShareInbox.uris.toList())
            ceo.villa.matty.ui.ShareInbox.clear()
        }
    }

    fun insert(text: String) {
        val v = value
        val before = v.text.substring(0, v.selection.start)
        val after = v.text.substring(v.selection.end)
        val pad = if (before.isNotEmpty() && !before.endsWith(" ") && !before.endsWith("\n")) " " else ""
        val t = before + pad + text + " " + after
        value = TextFieldValue(t, TextRange((before + pad + text + " ").length))
    }

    fun send() {
        val text = value.text
        val ready = uploads.filter { it.info != null }
        if (uploads.any { it.info == null && !it.failed }) { repo.emitNotice("Still uploading…"); return }
        if (text.isBlank() && ready.isEmpty()) return
        container.plugins.checkOutgoing(text)?.let { blocked = it; return }
        if (editing != null) {
            // The edit stays in the box until the server has it: if it's refused (too old to edit
            // here, no connection…) the words are still there, under a notice saying why.
            val id = editing.first
            // Already on its way: nothing more to do, and nothing to tick about.
            if (id in savingEdits) return
            if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            savingEdits += id
            scope.launch {
                val saved = try { repo.runAction("Couldn't save the edit") { repo.editPost(id, text); true } } finally { savingEdits -= id }
                // Typed on since the save went out: still editing, with what's been typed since
                // (Send saves that too). Otherwise the edit is over, and what was being written
                // here before it comes back.
                if (saved == true && editingNow?.first == id && value.text == text) {
                    onCancelEdit()
                    restoreDraft()
                    suggest = null
                }
            }
            closeEmoji(showKeyboard = false)
            return
        }
        if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        repo.sendMessage(channelId, rootId, text, ready.mapNotNull { it.info?.id })
        value = TextFieldValue("")
        uploads.clear()
        Drafts.map.remove(draftKey)
        suggest = null
        closeEmoji(showKeyboard = false)
    }

    /** A line break at the cursor (over the selection, if there is one). */
    fun newLine() {
        val v = value
        val t = v.text.replaceRange(v.selection.min, v.selection.max, "\n")
        value = TextFieldValue(t, TextRange(v.selection.min + 1))
        if (editing == null) Drafts.map[draftKey] = t
        repo.userTyping(channelId, rootId)
    }

    // Tapping back into the box to keep typing puts the keyboard back in place of the popup.
    LaunchedEffect(field) {
        field.interactions.collect { if (it is PressInteraction.Release) closeEmoji(showKeyboard = false) }
    }
    BackHandler(enabled = emojiPopup && active) { closeEmoji(showKeyboard = reopenKeyboard) }
    BackHandler(enabled = attachMenu) { attachMenu = false }
    LaunchedEffect(active) { if (!active) attachMenu = false }
    // The pill has slid away (or something covers it): don't leave the keyboard up typing into
    // a box nobody can see.
    LaunchedEffect(onScreen) {
        if (!onScreen && fieldFocused) { focusManager.clearFocus(); keyboard?.hide() }
    }

    // Autocomplete: work out what's being typed at the cursor.
    LaunchedEffect(value.text, value.selection) {
        val upto = value.text.substring(0, value.selection.start.coerceAtMost(value.text.length))
        val word = upto.takeLastWhile { !it.isWhitespace() }
        delay(120)
        suggest = when {
            upto.startsWith("/") && !upto.startsWith("//") && upto.length < 120 && !upto.contains('\n') -> {
                val teamId = repo.channels.value[channelId]?.teamId?.ifEmpty { null } ?: repo.currentTeamId.value ?: repo.teams.value.firstOrNull()?.id
                if (teamId == null) null else ceo.villa.matty.data.runCatchingCancellable { repo.client.commandSuggestions(teamId, channelId, rootId, upto) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }?.let { Suggest.Commands(it.take(20)) }
            }
            word.startsWith("@") && word.length >= 1 -> {
                val term = word.drop(1)
                val teamId = repo.channels.value[channelId]?.teamId?.ifEmpty { null } ?: repo.currentTeamId.value
                ceo.villa.matty.data.runCatchingCancellable { repo.client.autocompleteUsers(term, teamId, channelId) }.getOrNull()
                    ?.also { list -> repo.users.value = repo.users.value + list.associateBy { it.id } }
                    ?.let { Suggest.Users(word, it.take(12)) }
            }
            word.startsWith("~") && word.length >= 2 -> {
                val term = word.drop(1).lowercase()
                val list = repo.channels.value.values.filter { !it.isDmOrGm && (it.name.contains(term) || it.displayName.lowercase().contains(term)) }.take(10)
                if (list.isEmpty()) null else Suggest.Channels(word, list)
            }
            word.startsWith(":") && word.length >= 3 && !word.drop(1).contains(':') -> {
                val term = word.drop(1)
                val customs = repo.customEmojis.value.values.filter { it.name.contains(term.lowercase()) }.take(8)
                val list = EmojiStore.search(term, 16)
                if (list.isEmpty() && customs.isEmpty()) null else Suggest.Emojis(word, list, customs)
            }
            else -> null
        }
    }

    fun complete(token: String, replacement: String) {
        val v = value
        val start = v.selection.start - token.length
        if (start < 0) return
        val t = v.text.substring(0, start) + replacement + " " + v.text.substring(v.selection.start)
        value = TextFieldValue(t, TextRange(start + replacement.length + 1))
        suggest = null
    }

    val canSend = value.text.isNotBlank() || uploads.any { it.info != null }
    // Nothing to add emoji to any more (sent, cleared, or the server's emoji went away).
    LaunchedEffect(canSend, customs.isEmpty()) { if (!canSend || customs.isEmpty()) closeEmoji(showKeyboard = false) }

    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(
            emojiPopup,
            enter = fadeIn() + expandVertically(Matty.PillSpring, expandFrom = Alignment.Bottom) +
                scaleIn(initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 1f)),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom) +
                scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 1f)),
        ) {
            CustomEmojiPopup(
                draft = value.text,
                onEmoji = { name ->
                    insert(":$name:")
                    if (editing == null) Drafts.map[draftKey] = value.text
                    container.settings.noteEmojiUsed(name)
                    if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                onClose = { closeEmoji(showKeyboard = reopenKeyboard) },
                maxHeight = popupMax,
            )
        }
        AnimatedVisibility(suggest != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            SuggestionCard(suggest, onUser = { tok, u -> complete(tok, "@${u.username}") }, onChannel = { tok, ch -> complete(tok, "~${ch.name}") },
                onEmoji = { tok, name -> complete(tok, ":$name:") },
                onCommand = { s ->
                    val completed = completeCommand(s.complete)
                    value = TextFieldValue(completed, TextRange(completed.length))
                    if (editing == null) Drafts.map[draftKey] = completed
                })
        }
        // Everything here floats over the conversation, so each part carries its own surface.
        AnimatedVisibility(editing != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(c.pill)
                    .pointerInput(Unit) {}.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Edit, null, tint = c.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (saving) "Saving…" else "Editing message", color = c.accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Close, "Cancel edit", tint = c.textSecondary, modifier = Modifier.size(20.dp).clickable { onCancelEdit(); restoreDraft() })
            }
        }
        if (uploads.isNotEmpty()) {
            LazyRow(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uploads.toList()) { up -> UploadChip(up) { up.job?.cancel(); uploads.remove(up) } }
            }
        }
        blocked?.let { reason ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(c.pill)
                    .background(c.error.copy(alpha = 0.14f)).pointerInput(Unit) {}.padding(14.dp),
            ) {
                Text(reason, color = c.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.Close, "Dismiss", tint = c.textSecondary, modifier = Modifier.size(18.dp).clickable { blocked = null })
            }
        }
        // The same pill as the tab bar (60dp tall, fully round, 18dp above the navigation bar),
        // floating with nothing behind it; a little closer to the keyboard while that's up.
        val gap by androidx.compose.animation.core.animateDpAsState(if (imeVisible) 10.dp else 18.dp, label = "pillGap")
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = gap)
                .heightIn(min = 60.dp).clip(RoundedCornerShape(30.dp)).background(c.pill)
                // The pill floats over the conversation: a touch on it is the pill's, never the
                // hidden message behind it, and a tap anywhere on it is a tap in the box.
                .pointerInput(Unit) { detectTapGestures { runCatching { focus.requestFocus() }; keyboard?.show() } }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            ComposerIcon(Icons.Outlined.Add, "Attach", interactionSource = attachButton) {
                // Used up by this click: an accessibility click later comes without a press.
                val closedByThisPress = pressedWhileOpen
                pressedWhileOpen = false
                if (!closedByThisPress) attachMenu = true
            }
            if (attachMenu) AttachMenu(
                onDismiss = { attachMenu = false; attachClosedAt = android.os.SystemClock.uptimeMillis() },
                onMedia = { attachMenu = false; pickMedia.launch(Unit) },
                onFiles = { attachMenu = false; pickFiles.launch(Unit) },
            )
            Box(Modifier.weight(1f).padding(vertical = 14.dp, horizontal = 4.dp)) {
                if (value.text.isEmpty()) Text(placeholder, color = c.textTertiary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value = value,
                    onValueChange = { v ->
                        if (v.text != value.text) {
                            if (v.text.isNotEmpty()) repo.userTyping(channelId, rootId)
                            // Typed into a box that had slid out of sight (a physical keyboard): show it.
                            reveal?.invoke()
                        }
                        value = v
                        if (editing == null) Drafts.map[draftKey] = v.text
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = if (settings.enterToSend) ImeAction.Send else ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    interactionSource = field,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        // A physical keyboard (a tablet's, say): Enter sends, Shift+Enter adds a line,
                        // as on a desktop. The on-screen keyboard keeps its own Enter (see Settings).
                        .onPreviewKeyEvent { e ->
                            val enter = hardwareEnter(e.nativeKeyEvent)
                            // Out of sight: Enter brings the box back, and sends nothing unread.
                            if (enter != null && reveal != null) {
                                if (e.type == KeyEventType.KeyDown) reveal()
                                return@onPreviewKeyEvent true
                            }
                            when (enter) {
                                HardwareEnter.Send -> { if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) send(); true }
                                HardwareEnter.NewLine -> { if (e.type == KeyEventType.KeyDown) newLine(); true }
                                null -> false
                            }
                        },
                )
            }
            SendCluster(
                hasContent = canSend,
                gifs = repo.gifEnabled,
                hasCustomEmoji = customs.isNotEmpty(),
                emojiOpen = emojiPopup,
                onGif = { onOpenPicker(PickerMode.Gif) { insert(it) } },
                // Once there's text, the keyboard covers standard emoji: float the server's own
                // above the box, in place of the keyboard, so the message stays in view.
                onEmoji = {
                    when {
                        emojiPopup -> closeEmoji(showKeyboard = true)
                        canSend -> { reopenKeyboard = imeVisible; keyboard?.hide(); emojiPopup = true }
                        else -> onOpenPicker(PickerMode.Emoji) { insert(it) }
                    }
                },
                onSend = { send() },
                busy = saving,
            )
        }
    }
}

enum class PickerMode { Emoji, Gif }

internal enum class HardwareEnter { Send, NewLine }

/**
 * What Enter means when it comes from a physical keyboard: send, or with Shift (or Ctrl, Alt) a
 * new line. Null for any other key, and for an on-screen keyboard's Enter, which arrives marked
 * as such (or from no device at all) and keeps doing what the keyboard's own Enter does.
 */
internal fun hardwareEnter(e: android.view.KeyEvent): HardwareEnter? = hardwareEnter(
    e.keyCode, e.flags, e.deviceId, shift = e.isShiftPressed, ctrlOrAlt = e.isCtrlPressed || e.isAltPressed || e.isMetaPressed,
)

internal fun hardwareEnter(keyCode: Int, flags: Int, deviceId: Int, shift: Boolean, ctrlOrAlt: Boolean): HardwareEnter? {
    if (keyCode != android.view.KeyEvent.KEYCODE_ENTER && keyCode != android.view.KeyEvent.KEYCODE_NUMPAD_ENTER) return null
    val onScreen = flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD != 0 || deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
    if (onScreen) return null
    return if (shift || ctrlOrAlt) HardwareEnter.NewLine else HardwareEnter.Send
}

/** What a picker handed back: [ok] is false when the person backed out. */
class Picked(val ok: Boolean, val uris: List<Uri>)

/** The system photo picker (photos and videos, up to ten). */
private class PickMedia : androidx.activity.result.contract.ActivityResultContract<Unit, Picked>() {
    private val inner = ActivityResultContracts.PickMultipleVisualMedia(10)
    override fun createIntent(context: android.content.Context, input: Unit) =
        inner.createIntent(context, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    override fun parseResult(resultCode: Int, intent: android.content.Intent?) =
        Picked(resultCode == android.app.Activity.RESULT_OK, inner.parseResult(resultCode, intent))
}

/** Any files, from the system file picker. */
private class PickFiles : androidx.activity.result.contract.ActivityResultContract<Unit, Picked>() {
    private val inner = ActivityResultContracts.OpenMultipleDocuments()
    override fun createIntent(context: android.content.Context, input: Unit) = inner.createIntent(context, arrayOf("*/*"))
    override fun parseResult(resultCode: Int, intent: android.content.Intent?) =
        Picked(resultCode == android.app.Activity.RESULT_OK, inner.parseResult(resultCode, intent))
}

/**
 * Photos & videos / Files, opening just above the message pill from its + end. A menu of its
 * own rather than Material's dropdown, which takes focus: that put the keyboard away under it
 * and left the menu standing high above the box. This one leaves the keyboard as it is and
 * keeps to the pill, wherever the pill is.
 */
@Composable
private fun AttachMenu(onDismiss: () -> Unit, onMedia: () -> Unit, onFiles: () -> Unit) {
    val c = Matty.colors
    val density = LocalDensity.current
    val position = remember(density) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ): androidx.compose.ui.unit.IntOffset {
                // The anchor is the inside of the pill (6dp in, 4dp down from its edges); the menu
                // opens from the + end, which is the right in right-to-left languages.
                val nudge = with(density) { 2.dp.roundToPx() }
                val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) anchorBounds.right - popupContentSize.width + nudge
                    else anchorBounds.left - nudge
                val y = anchorBounds.top - popupContentSize.height - with(density) { 12.dp.roundToPx() }
                return androidx.compose.ui.unit.IntOffset(x.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)), maxOf(0, y))
            }
        }
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = false),
    ) {
        val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }.apply { targetState = true }
        val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
        AnimatedVisibility(
            shown,
            enter = fadeIn() + scaleIn(androidx.compose.animation.core.spring(0.8f, androidx.compose.animation.core.Spring.StiffnessMediumLow),
                initialScale = 0.85f, transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 1f)),
        ) {
            Column(
                Modifier.padding(4.dp).shadow(10.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.pill)
                    // Announced as it opens: the menu doesn't take focus, so the keyboard can stay.
                    .semantics { paneTitle = "Attach" }
                    .width(androidx.compose.foundation.layout.IntrinsicSize.Max).padding(vertical = 6.dp),
            ) {
                AttachRow(Icons.Outlined.Image, "Photos & videos", onMedia)
                AttachRow(Icons.Outlined.AttachFile, "Files", onFiles)
            }
        }
    }
}

@Composable
private fun AttachRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = Matty.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 22.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.icon, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = c.title, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * GIF and emoji while the box is empty. With something to send, the emoji button stays where
 * it is and the send button grows out of it into one accent pill, MarkOut's split button: the
 * left half floats the server's custom emoji above the box (and, while they're open, turns into
 * a keyboard button to go back to typing), the right half sends.
 */
@Composable
private fun SendCluster(
    hasContent: Boolean,
    gifs: Boolean,
    hasCustomEmoji: Boolean,
    emojiOpen: Boolean,
    onGif: () -> Unit,
    onEmoji: () -> Unit,
    onSend: () -> Unit,
    /** An edit is on its way to the server: the button says so and waits. */
    busy: Boolean = false,
) {
    val c = Matty.colors
    val showEmoji = !hasContent || hasCustomEmoji
    val pill by animateColorAsState(if (hasContent) c.accent else Color.Transparent, label = "sendPill")
    val emojiTint by animateColorAsState(if (hasContent) c.onAccent else c.icon, label = "emojiTint")
    val grow = expandHorizontally(Matty.PillSpring, expandFrom = Alignment.Start) + fadeIn()
    val fold = shrinkHorizontally(Matty.PillSpring, shrinkTowards = Alignment.Start) + fadeOut()
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(gifs && !hasContent, enter = expandHorizontally(Matty.PillSpring, expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(Matty.PillSpring, shrinkTowards = Alignment.End) + fadeOut()) {
            ComposerIcon(Icons.Outlined.Gif, "GIFs", onClick = onGif)
        }
        Row(
            Modifier.padding(vertical = 4.dp, horizontal = 2.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(pill),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(showEmoji, enter = grow, exit = fold) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onEmoji)
                    .semantics { contentDescription = when { emojiOpen -> "Back to keyboard"; hasContent -> "Server emoji"; else -> "Emoji" } },
                    contentAlignment = Alignment.Center) {
                    AnimatedContent(emojiOpen, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith fadeOut() }, label = "emojiIcon") { open ->
                        Icon(if (open) Icons.Outlined.Keyboard else Icons.Outlined.EmojiEmotions, null, tint = emojiTint,
                            modifier = Modifier.size(if (hasContent) 23.dp else 26.dp))
                    }
                }
            }
            AnimatedVisibility(hasContent && showEmoji, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.width(1.dp).height(22.dp).background(c.onAccent.copy(alpha = 0.3f)))
            }
            AnimatedVisibility(hasContent, enter = grow, exit = fold) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = !busy, onClick = onSend).semantics { contentDescription = if (busy) "Saving" else "Send" },
                    contentAlignment = Alignment.Center) {
                    if (busy) androidx.compose.material3.LoadingIndicator(Modifier.size(24.dp), color = c.onAccent)
                    else Icon(Icons.AutoMirrored.Outlined.Send, null, tint = c.onAccent, modifier = Modifier.size(21.dp))
                }
            }
        }
    }
}

@Composable
private fun ComposerIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier.padding(vertical = 2.dp).size(48.dp).clip(CircleShape)
            .clickable(interactionSource = interactionSource, indication = androidx.compose.material3.ripple(), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = Matty.colors.icon, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun UploadChip(up: PendingUpload, onRemove: () -> Unit) {
    val c = Matty.colors
    val context = LocalContext.current
    Box(Modifier.size(84.dp).clip(RoundedCornerShape(16.dp)).background(c.pill).semantics(mergeDescendants = true) {
        contentDescription = up.name + when { up.failed -> ", failed"; up.info == null -> ", uploading"; else -> ", ready" }
    }) {
        when {
            up.mime.startsWith("image/") ->
                AsyncImage(model = up.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp))
            up.mime.startsWith("video/") -> {
                // A frame from the video (the image loader can't read one), with a play mark, so a
                // picked video is plainly there rather than a blank tile.
                val frame by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, up.uri) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { context.contentResolver.loadThumbnail(up.uri, android.util.Size(240, 240), null).asImageBitmap() }.getOrNull()
                    }
                }
                frame?.let { androidx.compose.foundation.Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp)) }
                    ?: Icon(Icons.Outlined.Movie, null, tint = c.textSecondary, modifier = Modifier.align(Alignment.Center).size(30.dp))
                Box(Modifier.align(Alignment.Center).size(28.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            else -> Column(Modifier.padding(8.dp)) {
                Icon(Icons.Outlined.InsertDriveFile, null, tint = c.accent)
                Text(up.name, style = MaterialTheme.typography.labelSmall, color = c.text, maxLines = if (up.size > 0) 1 else 2, overflow = TextOverflow.Ellipsis)
                if (up.size > 0) Text(humanSize(up.size), style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 1)
            }
        }
        // A video's size sits over its frame; a document's goes under its name instead (below).
        if (up.size > 0 && up.mime.startsWith("video/")) {
            Text(humanSize(up.size), color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = if (up.info == null && !up.failed) 14.dp else 6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
        }
        if (up.info == null && !up.failed) {
            LinearProgressIndicator(progress = { up.progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp))
        }
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(c.scrim).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, "Remove", tint = c.title, modifier = Modifier.size(14.dp))
        }
        if (up.failed) Box(Modifier.matchParentSize().background(c.error.copy(alpha = 0.35f)))
    }
}

@Composable
private fun SuggestionCard(
    s: Suggest?,
    onUser: (String, User) -> Unit,
    onChannel: (String, Channel) -> Unit,
    onEmoji: (String, String) -> Unit,
    onCommand: (AutocompleteSuggestion) -> Unit,
) {
    val repo = LocalRepo.current
    val c = Matty.colors
    val statuses by repo.statuses.collectAsState()
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(Matty.CardShape).background(c.pill).pointerInput(Unit) {}) {
        LazyColumn(Modifier.heightIn(max = 260.dp).padding(vertical = 6.dp)) {
            when (s) {
                is Suggest.Users -> items(s.list, key = { it.id }) { u ->
                    SuggestRow(onClick = { onUser(s.token, u) }) {
                        Avatar(repo.avatarUrl(u.id), repo.nameOf(u), 32.dp, status = statuses[u.id], ringColor = c.pill)
                        Spacer(Modifier.width(10.dp))
                        Text(repo.nameOf(u), color = c.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(6.dp))
                        Text("@${u.username}", color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
                        if (u.isBot) BotTag()
                    }
                }
                is Suggest.Channels -> items(s.list, key = { it.id }) { ch ->
                    SuggestRow(onClick = { onChannel(s.token, ch) }) {
                        Icon(Icons.Outlined.Tag, null, tint = c.textSecondary)
                        Spacer(Modifier.width(10.dp))
                        Text(ch.displayName, color = c.title, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(6.dp))
                        Text("~${ch.name}", color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is Suggest.Emojis -> {
                    items(s.custom, key = { "c:" + it.id }) { e ->
                        SuggestRow(onClick = { onEmoji(s.token, e.name) }) {
                            AsyncImage(model = repo.client.customEmojiUrl(e.id), contentDescription = null, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(":${e.name}:", color = c.title, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    items(s.list, key = { "u:" + it.unicode }) { e ->
                        SuggestRow(onClick = { onEmoji(s.token, e.name) }) {
                            Text(e.unicode, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(10.dp))
                            Text(":${e.name}:", color = c.title, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                is Suggest.Commands -> items(s.list) { cmd ->
                    SuggestRow(onClick = { onCommand(cmd) }) {
                        Box(Modifier.size(32.dp).background(c.selected, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Terminal, null, tint = c.icon, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Row {
                                Text(commandLabel(cmd), color = c.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                if (cmd.hint.isNotBlank()) Text("  ${cmd.hint}", color = c.textTertiary, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (cmd.description.isNotBlank()) Text(cmd.description, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
                null -> {}
            }
        }
    }
}

/**
 * What picking a command suggestion leaves in the box. Mattermost gives the whole command so far
 * without its slash ("code", "call start", "call start " for an argument's hint), so the slash goes
 * back on, and a space follows, ready for what comes next.
 */
internal fun completeCommand(complete: String): String {
    val c = complete.trimStart().removePrefix("/")
    return "/" + if (c.endsWith(' ')) c else "$c "
}

/** A suggestion's name in the list: a command itself with its slash ("/code"), a subcommand bare ("start"). */
internal fun commandLabel(s: ceo.villa.matty.data.api.AutocompleteSuggestion): String {
    val whole = s.complete.trim().removePrefix("/")
    return when {
        s.suggestion.isBlank() -> "/$whole"
        !whole.contains(' ') -> "/" + s.suggestion.removePrefix("/")
        else -> s.suggestion
    }
}

@Composable
private fun SuggestRow(onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Matty.RowShape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Suppress("unused")
private val keep = listOf(expandHorizontally(), shrinkHorizontally())
