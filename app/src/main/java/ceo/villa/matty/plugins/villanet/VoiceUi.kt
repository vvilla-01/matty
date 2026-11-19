package ceo.villa.matty.plugins.villanet

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ceo.villa.matty.data.api.User
import ceo.villa.matty.data.repo.ChatRepository
import ceo.villa.matty.plugins.PluginHost
import ceo.villa.matty.ui.LocalActions
import ceo.villa.matty.ui.Navigator
import ceo.villa.matty.ui.Route
import ceo.villa.matty.ui.components.Avatar
import ceo.villa.matty.ui.components.EmptyState
import ceo.villa.matty.ui.components.Pill
import ceo.villa.matty.ui.components.PillButton
import ceo.villa.matty.ui.components.PillIconButton
import ceo.villa.matty.ui.components.sheetVerticalScroll
import ceo.villa.matty.ui.home.SidebarCategoryHeader
import ceo.villa.matty.ui.home.SidebarRow
import ceo.villa.matty.ui.theme.LocalMattyColors
import ceo.villa.matty.ui.theme.Matty
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Voice channels on screen, Discord's mobile voice in Matty's own pieces: the channel list's
 * "Voice Channels" category, the sheet a channel opens, the voice screen (a page of the panel's
 * route) with its controls, and the bar while browsing elsewhere. What they show is worked out
 * in VoiceRows.kt; the session itself is [VoiceSession].
 */

/** The panel's argument that opens the voice screen in place of the panel. */
internal const val VOICE_ARG = "voice"

private val TileShape = RoundedCornerShape(16.dp)

/** Opens the voice screen (it keeps no state of its own: going back keeps the call). */
internal fun openVoiceScreen(host: PluginHost) = host.openPanel(VILLANET_ID, mapOf(VOICE_ARG to "1"))

/** What a tap on voice channel [id] opens: the voice screen when this phone is in it, its sheet otherwise. */
internal fun openVoiceChannel(plugin: VillaNetPlugin, host: PluginHost, id: Int) {
    val v = plugin.voice
    if (v.state.value.inCall && v.moving.value == null && v.channel.value == id) openVoiceScreen(host)
    else plugin.voiceSheet.value = id
}

/** The name the app shows for a Mattermost account, or null while the app doesn't know it. */
private fun accountName(repo: ChatRepository, users: Map<String, User>, id: String): String? =
    users[id]?.takeIf { it.username.isNotEmpty() }?.let { repo.nameOf(it) }

/**
 * The voice roster as the screens draw it, with this phone's own seat placed at once. Only the
 * roster and the names of the people in it are followed, not the whole snapshot or everybody the
 * app knows: the channel list asks for this in its own composition, and is drawn again only when
 * voice changes. [talking] is given only where a word spoken may redraw everything (the screen
 * and the sheet); the channel list's rows follow [VoiceSession.talking] themselves.
 */
@Composable
private fun rememberVoiceRooms(plugin: VillaNetPlugin, repo: ChatRepository, talking: Set<Long> = emptySet()): List<VoiceRoom> {
    val outdated by plugin.outdated.collectAsState()
    val voiceNow by remember(plugin) { plugin.snapshot.map { it?.voice }.distinctUntilChanged() }.collectAsState(plugin.snapshot.value?.voice)
    val seat by plugin.voice.seat.collectAsState()
    val muted by plugin.voice.muted.collectAsState()
    val deafened by plugin.voice.deafened.collectAsState()
    // Never from a plugin too old for this app, which is asked nothing.
    val roster = voiceNow?.takeIf { outdated == null }
    val ids = remember(roster, seat) {
        (roster?.people.orEmpty().map { it.userId } + listOfNotNull(seat?.userId)).filterTo(LinkedHashSet()) { it.isNotEmpty() }
    }
    val names by remember(repo, ids) { repo.users.map { u -> ids.associateWith { accountName(repo, u, it) } }.distinctUntilChanged() }
        .collectAsState(ids.associateWith { accountName(repo, repo.users.value, it) })
    // People here with an account the app hasn't met yet: their names and pictures are asked for.
    LaunchedEffect(ids) { if (ids.isNotEmpty()) repo.ensureUsers(ids) }
    return remember(roster, names, seat, muted, deafened, talking) {
        voiceRoomsOf(roster, { id -> names[id] }, seat, muted, deafened, talking)
    }
}

// ---- the channel list --------------------------------------------------------------------------

/**
 * The channel list's "Voice Channels" category ([ceo.villa.matty.plugins.MattyPlugin.sidebarSection]):
 * every voice channel in tree order with the people in it, while the server's plugin has a voice
 * server with channels; null otherwise. Folded, it shows only the channel this phone is in.
 */
@Composable
internal fun voiceSidebarSection(plugin: VillaNetPlugin, host: PluginHost): (LazyListScope.() -> Unit)? {
    val access by plugin.access.collectAsState()
    val status by plugin.voice.state.collectAsState()
    val channel by plugin.voice.channel.collectAsState()
    val folded by plugin.voiceFolded.collectAsState()
    val rooms = rememberVoiceRooms(plugin, host.repo)
    val mine = if (status.inCall) channel else null
    val rows = remember(rooms, folded, mine) { voiceListRows(rooms, folded, mine) }
    val talkOn = voiceTalkOn(access)
    if (rooms.isEmpty()) return null
    return {
        item(key = "$VILLANET_ID:voice", contentType = "voice:header") {
            SidebarCategoryHeader("Voice Channels", folded) { plugin.voiceFolded.value = !folded }
        }
        items(rows, key = { "$VILLANET_ID:${it.key}" }, contentType = { if (it is VoiceListRow.Channel) "voice:channel" else "voice:person" }) { row ->
            when (row) {
                is VoiceListRow.Channel -> VoiceChannelItem(plugin, host, row, Modifier.animateItem())
                is VoiceListRow.Person -> VoicePersonItem(plugin, host, row, talkOn, Modifier.animateItem())
            }
        }
    }
}

/** How far a channel [depth] levels down the tree is set in. */
private fun indentOf(depth: Int): Dp = (depth.coerceIn(0, 6) * 14).dp

@Composable
private fun VoiceChannelItem(plugin: VillaNetPlugin, host: PluginHost, row: VoiceListRow.Channel, modifier: Modifier) {
    val c = Matty.colors
    val r = row.room
    val count = voiceCountOf(r)
    Box(modifier.padding(start = indentOf(r.depth))) {
        SidebarRow(
            icon = {
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, tint = if (row.here) c.online else c.textTertiary, modifier = Modifier.size(20.dp))
            },
            name = r.name.ifBlank { "Voice" },
            // The one this phone is in reads as an unread channel does: brighter, and bold.
            unread = row.here, mentions = 0, selected = false, muted = false,
            trailing = if (count != null) {
                {
                    Text(
                        count, style = MaterialTheme.typography.labelMedium, color = if (r.full) c.dnd else c.textTertiary,
                        modifier = Modifier.semantics { contentDescription = if (r.full) "$count, full" else count },
                    )
                }
            } else null,
        ) { openVoiceChannel(plugin, host, r.id) }
    }
}

@Composable
private fun VoicePersonItem(plugin: VillaNetPlugin, host: PluginHost, row: VoiceListRow.Person, talkOn: Boolean, modifier: Modifier) {
    val c = Matty.colors
    val actions = LocalActions.current
    val m = row.member
    val talking by plugin.voice.talking.collectAsState()
    val speaking = talkOn && m.session in talking
    Row(
        modifier.fillMaxWidth().padding(start = 8.dp + indentOf(row.depth), end = 8.dp).clip(Matty.RowShape)
            // Somebody with an account here: their profile, as anywhere a person is shown. Else the channel.
            .clickable { if (m.userId.isNotEmpty()) actions.openUser(m.userId) else openVoiceChannel(plugin, host, row.channel) }
            .clearAndSetSemantics { contentDescription = voiceMemberWords(m, speaking); role = Role.Button }
            // Under the channel's name: past the row's own padding (10dp), its icon (20dp) and the gap (12dp).
            .padding(start = 42.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(if (m.userId.isNotEmpty()) host.repo.avatarUrl(m.userId) else null, m.name, 24.dp, ringColor = c.card, speaking = speaking)
        Spacer(Modifier.width(8.dp))
        Text(
            m.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, color = if (speaking) c.title else c.textSecondary,
        )
        VoiceMarks(m, c.textTertiary, 16.dp)
    }
}

/** A person's marks, Discord's: on a phone, muted, deafened. */
@Composable
private fun VoiceMarks(m: VoiceMember, tint: Color, size: Dp) {
    if (m.phone) { Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.Smartphone, null, tint = tint, modifier = Modifier.size(size - 2.dp)) }
    if (m.muted) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.MicOff, null, tint = tint, modifier = Modifier.size(size)) }
    if (m.deaf) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.HeadsetOff, null, tint = tint, modifier = Modifier.size(size)) }
}

// ---- the join sheet ------------------------------------------------------------------------------

/**
 * The sheet of the voice channel [VillaNetPlugin.voiceSheet] names, composed from the module's
 * Overlay: outside every other sheet, after the permission's asker. What it must keep (join muted
 * or not) is held by the plugin, so the sheet's window being built afresh loses nothing.
 */
@Composable
internal fun VoiceSheetHost(plugin: VillaNetPlugin, host: PluginHost) {
    val id by plugin.voiceSheet.collectAsState()
    id?.let { ch -> key(ch) { VoiceJoinSheet(plugin, host, ch) { if (plugin.voiceSheet.value == ch) plugin.voiceSheet.value = null } } }
}

@Composable
private fun VoiceJoinSheet(plugin: VillaNetPlugin, host: PluginHost, channelId: Int, onDismiss: () -> Unit) {
    val c = Matty.colors
    val ctx = LocalContext.current
    val voice = plugin.voice
    val access by plugin.access.collectAsState()
    val status by voice.state.collectAsState()
    val channel by voice.channel.collectAsState()
    val moving by voice.moving.collectAsState()
    val mic by voice.mic.collectAsState()
    val talking by voice.talking.collectAsState()
    val joinMuted by plugin.voiceJoinMuted.collectAsState()
    val talkOn = voiceTalkOn(access)
    // Joining asks more of the reader than seeing who is there: the routes want them linked or let in.
    val mayJoin = voiceMayJoin(access)
    val rooms = rememberVoiceRooms(plugin, host.repo, if (talkOn) talking else emptySet())
    val room = rooms.firstOrNull { it.id == channelId }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // The permission may have changed in Android's settings since it was last looked at.
    LaunchedEffect(Unit) { voice.recheckMic() }
    // The channel is gone (or the voice server): nothing left to join.
    LaunchedEffect(room == null) { if (room == null) onDismiss() }
    if (room == null) return
    val action = voiceJoinActionOf(mayJoin, status, channel, moving, channelId, room.full)
    fun close() { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.card,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.sheetVerticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(c.pill, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = c.icon, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(room.name.ifBlank { "Voice" }, style = MaterialTheme.typography.titleLarge, color = c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(voicePeopleWords(room), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
            if (room.people.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                room.people.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp).clearAndSetSemantics { contentDescription = voiceMemberWords(m, m.speaking) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(if (m.userId.isNotEmpty()) host.repo.avatarUrl(m.userId) else null, m.name, 36.dp, ringColor = c.card, speaking = m.speaking)
                        Spacer(Modifier.width(12.dp))
                        Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        VoiceMarks(m, c.textTertiary, 18.dp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            val label = voiceJoinLabel(action)
            if (label == null) {
                Text(voiceNoTalkWords(access), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Join muted or not: only for a join (a move keeps the session as it is).
                    if (action == VoiceJoinAction.Join) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(c.selected)
                                .toggleable(value = !joinMuted, role = Role.Switch) { on -> plugin.voiceJoinMuted.value = !on }
                                .semantics { contentDescription = "Microphone"; stateDescription = if (joinMuted) "Off" else "On" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(if (joinMuted) Icons.Filled.MicOff else Icons.Filled.Mic, null, tint = if (joinMuted) c.dnd else c.icon)
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    CompositionLocalProvider(LocalMattyColors provides c.copy(accent = c.join, onAccent = c.onStatus)) {
                        PillButton(
                            label, Modifier.weight(1f), primary = true,
                            enabled = action != VoiceJoinAction.Full, loading = action == VoiceJoinAction.Moving,
                        ) {
                            when (action) {
                                VoiceJoinAction.Join -> voice.join(channelId, joinMuted)
                                VoiceJoinAction.Move -> voice.move(channelId)
                                else -> {}
                            }
                            close()
                            openVoiceScreen(host)
                        }
                    }
                }
                // Joining unmuted without the microphone: it is asked for, or, refused for good,
                // the person joins muted and Android's settings are where it is allowed.
                val micWords = if (action == VoiceJoinAction.Join && !joinMuted) voiceJoinMicWords(mic) else null
                if (micWords != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        micWords.text, style = MaterialTheme.typography.bodySmall, color = if (micWords.toSettings) c.link else c.textSecondary,
                        modifier = if (micWords.toSettings) {
                            Modifier.clip(Matty.RowShape).clickable(onClickLabel = "Open Settings") { voice.micSettings(ctx) }.padding(vertical = 4.dp)
                        } else Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

// ---- the voice screen -----------------------------------------------------------------------------

/**
 * The voice screen: the channel's name and how the call is going, everybody in the channel as a
 * tile (their picture, a ring while they speak, their name and marks), and one pill of controls:
 * mute, deafen, the audio output and leave. Back keeps the call; leaving closes the screen.
 */
@Composable
internal fun VoiceScreen(plugin: VillaNetPlugin, host: PluginHost, onClose: () -> Unit) {
    val c = Matty.colors
    val ctx = LocalContext.current
    val repo = host.repo
    val voice = plugin.voice
    val access by plugin.access.collectAsState()
    val snap by plugin.snapshot.collectAsState()
    val status by voice.state.collectAsState()
    val channel by voice.channel.collectAsState()
    val moving by voice.moving.collectAsState()
    val seat by voice.seat.collectAsState()
    val muted by voice.muted.collectAsState()
    val deafened by voice.deafened.collectAsState()
    val selfMuted by voice.selfMuted.collectAsState()
    val held by voice.held.collectAsState()
    val serverMuted by voice.serverMuted.collectAsState()
    val suppressed by voice.suppressed.collectAsState()
    val talking by voice.talking.collectAsState()
    val mic by voice.mic.collectAsState()
    // The channel of a session that failed, for its name and for joining again.
    val lastChannel by voice.lastChannel.collectAsState()
    val talkOn = voiceTalkOn(access)
    val rooms = rememberVoiceRooms(plugin, repo, if (talkOn) talking else emptySet())
    // Left (from here, the bar or the notification), or never in: back to where the person was,
    // but only while this screen is the one on top (it may be on its way out already).
    LaunchedEffect(status) {
        if (status == VoiceStatus.Idle) {
            val top = (host as? Navigator)?.top
            if (top == null || (top as? Route.Plugin)?.args?.containsKey(VOICE_ARG) == true) onClose()
        }
    }

    val me = if (seat == null && status.inCall) {
        VoiceMember(-1, repo.myId, repo.nameOf(repo.myId).takeIf { it != "…" } ?: "You", muted, deafened, phone = true, speaking = false, me = true)
    } else null
    val tiles = voiceTilesOf(rooms, channel, me)
    val title = voiceChannelName(snap?.voice, channel ?: lastChannel) ?: "Voice"
    val subtitle = moving?.let { to -> "Moving to ${voiceChannelName(snap?.voice, to) ?: "another channel"}…" } ?: voiceStatusWords(status)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The room the controls (and the notice over them) take at the bottom, as they are measured.
    val density = LocalDensity.current
    var controlsHeight by remember { mutableStateOf(0.dp) }

    Box(Modifier.fillMaxSize().background(c.background).semantics { paneTitle = title }) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            val failed = status as? VoiceStatus.Failed
            if (failed != null) {
                EmptyState(
                    Icons.Filled.CallEnd, "Voice Disconnected", failed.sentence,
                    Modifier.verticalScroll(rememberScrollState()).padding(top = statusTop + 64.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val again = lastChannel
                        if (voiceMayJoin(access) && again != null) {
                            CompositionLocalProvider(LocalMattyColors provides c.copy(accent = c.join, onAccent = c.onStatus)) {
                                PillButton("Join Again", primary = true) { voice.join(again, selfMuted) }
                            }
                        }
                        PillButton("Close") { voice.leave() }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = statusTop + 68.dp,
                        bottom = navBottom + (if (status.inCall) controlsHeight else 0.dp) + 16.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(tiles, key = { it.session }) { m -> VoiceTile(host, m, Modifier.animateItem()) }
                }
                if (status.inCall) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                            .onSizeChanged { controlsHeight = with(density) { it.height.toDp() } }
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        voiceNoticeOf(held, serverMuted, suppressed, mic)?.let { n ->
                            Text(
                                n.text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                                color = if (n.toSettings) c.link else c.textSecondary,
                                modifier = Modifier.widthIn(max = 480.dp).clip(Matty.RowShape)
                                    .then(if (n.toSettings) Modifier.clickable(onClickLabel = "Open Settings") { voice.micSettings(ctx) } else Modifier)
                                    .background(c.pill).padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        VoiceControls(voice, muted, deafened)
                    }
                }
            }
            VoiceHeader(title, subtitle, connected = status == VoiceStatus.Connected && moving == null, onBack = onClose)
        }
    }
}

/** The screen's top: back, and over the tiles, the channel and how the call is going (green while connected). */
@Composable
private fun VoiceHeader(title: String, subtitle: String, connected: Boolean, onBack: () -> Unit) {
    val c = Matty.colors
    Row(
        // A solid band, so the tiles scrolling under it never show through the words.
        Modifier.fillMaxWidth().background(c.background).statusBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill { PillIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(
                subtitle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (connected) c.online else c.textSecondary, fontWeight = if (connected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/** One person on the voice screen. */
@Composable
private fun VoiceTile(host: PluginHost, m: VoiceMember, modifier: Modifier) {
    val c = Matty.colors
    val actions = LocalActions.current
    val edge by animateColorAsState(if (m.speaking) c.online else c.online.copy(alpha = 0f), label = "tileEdge")
    Box(
        modifier.aspectRatio(1f).clip(TileShape).background(c.card).border(2.dp, edge, TileShape)
            .then(if (m.userId.isNotEmpty()) Modifier.clickable(onClickLabel = "Profile") { actions.openUser(m.userId) } else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = voiceMemberWords(m, m.speaking) },
    ) {
        Avatar(
            if (m.userId.isNotEmpty()) host.repo.avatarUrl(m.userId) else null, m.name, 80.dp,
            Modifier.align(Alignment.Center), ringColor = c.card, speaking = m.speaking,
        )
        Row(
            Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(12.dp)).background(c.pill)
                .clearAndSetSemantics {}.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (m.deaf) { Icon(Icons.Filled.HeadsetOff, null, tint = c.dnd, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
            if (m.muted) { Icon(Icons.Filled.MicOff, null, tint = c.dnd, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
            Text(m.name, style = MaterialTheme.typography.labelMedium, color = c.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The control pill: mute, deafen, the audio output (a small picker), and leave in red. */
@Composable
private fun VoiceControls(voice: VoiceSession, muted: Boolean, deafened: Boolean) {
    val c = Matty.colors
    val output by voice.output.collectAsState()
    val outputs by voice.outputs.collectAsState()
    var picking by remember { mutableStateOf(false) }
    Row(
        // Solid, so a touch beside a button never reaches a tile behind it.
        Modifier.clip(RoundedCornerShape(36.dp)).background(c.pill).pointerInput(Unit) {}.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoiceControlButton(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (muted) "Unmute" else "Mute", on = muted) { voice.setMuted(!muted) }
        VoiceControlButton(if (deafened) Icons.Filled.HeadsetOff else Icons.Filled.Headset, if (deafened) "Undeafen" else "Deafen", on = deafened) { voice.setDeafened(!deafened) }
        Box {
            VoiceControlButton(outputIcon(output?.kind), "Audio output, ${output?.name ?: "automatic"}", on = false) { picking = true }
            DropdownMenu(picking, { picking = false }, shape = RoundedCornerShape(24.dp), containerColor = c.pill) {
                if (outputs.isEmpty()) DropdownMenuItem(text = { Text("No other output", color = c.textSecondary) }, enabled = false, onClick = {})
                outputs.forEach { o ->
                    DropdownMenuItem(
                        text = { Text(o.name, color = c.title) },
                        leadingIcon = { Icon(outputIcon(o.kind), null, tint = c.icon) },
                        trailingIcon = if (o.id == output?.id) { { Icon(Icons.Outlined.Check, "In use", tint = c.title) } } else null,
                        onClick = { picking = false; voice.chooseOutput(o.id) },
                    )
                }
            }
        }
        VoiceControlButton(Icons.Filled.CallEnd, "Leave", on = false, fill = c.dnd, tint = c.onStatus) { voice.leave() }
    }
}

/** One round control: filled light while [on] (muted, deafened), as Discord's are. */
@Composable
private fun VoiceControlButton(icon: ImageVector, label: String, on: Boolean, fill: Color? = null, tint: Color? = null, onClick: () -> Unit) {
    val c = Matty.colors
    val bg by animateColorAsState(fill ?: if (on) c.title else c.selected, label = "controlBg")
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(bg).clickable(onClick = onClick)
            .semantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint ?: if (on) c.background else c.icon, modifier = Modifier.size(26.dp))
    }
}

private fun outputIcon(kind: VoiceOutputKind?): ImageVector = when (kind) {
    VoiceOutputKind.Earpiece -> Icons.Filled.PhoneInTalk
    VoiceOutputKind.Headset -> Icons.Filled.Headset
    VoiceOutputKind.Bluetooth -> Icons.Filled.BluetoothAudio
    VoiceOutputKind.Other -> Icons.Filled.Speaker
    VoiceOutputKind.Speaker, null -> Icons.AutoMirrored.Filled.VolumeUp
}

// ---- the bar -----------------------------------------------------------------------------------

/**
 * The floating bar while in voice and browsing elsewhere: "Voice Connected" (or how it is going),
 * the channel and the server, the microphone and Leave; a tap opens the voice screen. Ended by
 * the server, it says why until it is put away. Keeps drawing the last state while it slides away.
 */
@Composable
internal fun VoiceBarPill(plugin: VillaNetPlugin, host: PluginHost, modifier: Modifier) {
    val c = Matty.colors
    val voice = plugin.voice
    val snap by plugin.snapshot.collectAsState()
    val status by voice.state.collectAsState()
    val channel by voice.channel.collectAsState()
    val muted by voice.muted.collectAsState()
    val now = voiceBarOf(status, voiceChannelName(snap?.voice, channel), host.repo.siteName)
    val last = remember { arrayOfNulls<VoiceBar>(1) }
    if (now != null) last[0] = now
    val bar = now ?: last[0] ?: return
    Row(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(c.pill)
            .clickable(onClickLabel = "Open voice") { openVoiceScreen(host) }.padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(if (bar.connected) c.online else c.selected, CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                if (bar.controls) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.CallEnd, null,
                tint = if (bar.connected) c.onStatus else c.icon, modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(
                bar.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (bar.connected) c.online else c.title,
            )
            Text(bar.place, style = MaterialTheme.typography.labelSmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (bar.controls) {
            PillIconButton(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (muted) "Unmute" else "Mute", tint = if (muted) c.dnd else c.icon) { voice.setMuted(!muted) }
            PillIconButton(Icons.Filled.CallEnd, "Leave", tint = c.dnd) { voice.leave() }
        } else {
            PillIconButton(Icons.Outlined.Close, "Dismiss") { voice.leave() }
        }
    }
}
