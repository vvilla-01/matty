package ceo.villa.matty.service

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import ceo.villa.matty.AppContainer
import ceo.villa.matty.MainActivity
import ceo.villa.matty.R
import ceo.villa.matty.container
import ceo.villa.matty.data.api.Post
import ceo.villa.matty.data.api.RequestDeadline
import ceo.villa.matty.data.api.WsEvent
import ceo.villa.matty.data.api.str
import ceo.villa.matty.data.net.TransportPolicy
import ceo.villa.matty.data.repo.ChatRepository
import ceo.villa.matty.data.session.SavedServer
import ceo.villa.matty.push.Clears
import ceo.villa.matty.push.PushPayload
import ceo.villa.matty.push.PushStore
import ceo.villa.matty.ui.chat.plainPreview
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local notifications for mentions and DMs, fed by the live WebSocket or by a UnifiedPush push
 * instead of Google push, so they work on de-Googled phones and on self-hosted servers with no
 * push proxy. Both paths post through [show], under the identity [NoteIdentity] gives: one
 * notification per saved server and conversation, read and posted by one caller at a time. A
 * conversation's earlier lines are the ones this process last posted, or else read back from the
 * notification already showing.
 *
 * A notification the person replied from is held by Android: the app's cancel does nothing to
 * it until the app has posted it again, and moments after the reply Android can put the held
 * version back. Every way a notification goes here lets go of a held one and sees that it stays
 * gone.
 */
class Notifier(private val app: Application, private val c: AppContainer) {
    private val nm = NotificationManagerCompat.from(app)
    private var job: Job? = null
    private val shown by lazy { ShownPosts(kept = PushStore.of(app).shownKept) }
    private val clears by lazy { Clears(kept = PushStore.of(app).clearsKept) }
    /** Few: each line may hold its sender's picture. */
    private val posted = LastLines<NotificationCompat.MessagingStyle.Message>(max = 16)
    /** A conversation's notification is read and posted under its stripe's lock: lines are never lost between two posts. */
    private val stripes = Array(32) { Mutex() }

    init {
        val mgr = app.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Messages your server notifies you about"
        })
        mgr.createNotificationChannel(NotificationChannel(CH_CONNECTION, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps Matty connected to your server so messages arrive instantly"
            setShowBadge(false)
        })
    }

    private fun lockOf(tag: String?, id: Int) = stripes[Math.floorMod((tag?.hashCode() ?: 0) * 31 + id, stripes.size)]

    private fun key(tag: String?, id: Int) = "$tag/$id"

    fun attach(repo: ChatRepository) {
        job?.cancel()
        job = repo.scope.launch {
            launch { repo.alerts.collect { (post, event) -> show(noteFor(repo, post, event)) } }
            launch { repo.activeChannelId.collect { id -> if (id != null) cancel(repo.server.id, id) } }
            // A collapsed thread read, here or on another device: its notification goes.
            launch { repo.events.collect { e -> NoteRules.threadRead(e, repo.myId)?.let { cancel(repo.server.id, it) } } }
        }
    }

    /** The notification of [conversation] (a channel, or a collapsed thread's root) on [serverId]. */
    suspend fun cancel(serverId: String, conversation: String) =
        remove(NoteIdentity.tag(serverId), NoteIdentity.id(conversation))

    /** A conversation read elsewhere ([clear]): remembered, so a late push of it stays hidden, and its notification goes. */
    suspend fun cleared(serverId: String, clear: PushPayload.Clear) {
        clears.remember(serverId, clear)
        cancel(serverId, NoteIdentity.conversation(clear.channel, Clears.threadRoot(clear.root, clear.crt)))
    }

    /** A notification posted before notifications named their server (no tag, the channel's id). */
    suspend fun removeUnnamed(channelId: String) = remove(null, channelId.hashCode())

    private suspend fun remove(tag: String?, id: Int) = lockOf(tag, id).withLock { removeLocked(tag, id) }

    /** [remove], under the conversation's lock. */
    private suspend fun removeLocked(tag: String?, id: Int) {
        posted.forget(key(tag, id))
        if (record(tag, id)?.let { NoteRules.heldByReply(it.flags) } == true) gone(tag, id)
        else runCatching { nm.cancel(tag, id) }
    }

    /**
     * The conversation of [head] was marked read on its server: its notification goes, as
     * [cancel] takes it; but when a message was posted to it after the action began ([since]),
     * which the read did not cover, it stays ([NoteRules.keptAfterReply]).
     */
    suspend fun markedRead(head: NoteHead, since: Long) {
        val tag = NoteIdentity.tag(head.serverId)
        val id = NoteIdentity.id(head.conversation)
        lockOf(tag, id).withLock {
            if (NoteRules.keptAfterReply(current(tag, id, head)?.shownAt, since)) return@withLock
            removeLocked(tag, id)
        }
    }

    /** Cancels a notification the person replied from, and sees that it stays gone ([NoteRules.stayGone]). */
    private suspend fun gone(tag: String?, id: Int) = NoteRules.stayGone(object : NoteRules.Showing {
        override fun flags(): Int? = record(tag, id)?.flags
        override suspend fun release() { record(tag, id)?.let { release(tag, id, it) } }
        override fun cancel() { runCatching { nm.cancel(tag, id) } }
    })

    /** Posts held notification [n] again, quietly, so that it can be cancelled, and waits until Android has let go of it. */
    private suspend fun release(tag: String?, id: Int, n: android.app.Notification) {
        val copy = n.clone().apply { flags = NoteRules.released(flags) }
        runCatching { nm.notify(tag, id, copy) }
        awaitReleased(tag, id)
    }

    private suspend fun awaitReleased(tag: String?, id: Int) {
        repeat(RELEASE_TRIES) {
            val n = record(tag, id) ?: return
            if (!NoteRules.heldByReply(n.flags)) return
            delay(RELEASE_STEP_MS)
        }
    }

    /** Every message notification of [serverId]: its session ended, or it was signed out of. */
    fun clearMessages(serverId: String) = clearWhere { tag -> tag == NoteIdentity.tag(serverId) }

    /**
     * The message notifications of every server not in [kept] (and any without a server): no
     * message preview outlives its session. A server's notices stay.
     */
    fun clearDropped(kept: Set<String>) {
        val tags = kept.mapTo(HashSet()) { NoteIdentity.tag(it) }
        clearWhere { tag -> !NoteIdentity.isNotice(tag) && tag !in tags }
    }

    private fun clearWhere(test: (String?) -> Boolean) {
        posted.forgetWhere { k -> test(k.substringBeforeLast('/').takeUnless { it == "null" }) }
        runCatching {
            app.getSystemService(NotificationManager::class.java).activeNotifications
                .filter { it.notification.channelId == CH_MESSAGES && test(it.tag) }
                .forEach { sbn ->
                    if (NoteRules.heldByReply(sbn.notification.flags)) c.appScope.launch { remove(sbn.tag, sbn.id) }
                    else nm.cancel(sbn.tag, sbn.id)
                }
        }
    }

    /** What the live connection's alert for [post] shows. */
    private suspend fun noteFor(repo: ChatRepository, post: Post, e: WsEvent): MessageNote {
        val ch = repo.channels.value[post.channelId]
        val senderName = post.overrideUsername(repo.postUsernameOverride) ?: e.data.str("sender_name")?.trimStart('@')?.let { n ->
            repo.users.value[post.userId]?.let { repo.nameOf(it) } ?: n
        } ?: repo.nameOf(post.userId)
        val kind = when {
            // A channel the live session does not know yet (a direct message just made): as the event says.
            ch == null -> NoteRules.unknownChannel(e.data.str("channel_type"), e.data.str("channel_display_name"))
            ch.isDirect -> NoteKind(title = null, group = false)
            ch.isGroup -> NoteKind(title = ch.displayName, group = true)
            else -> NoteKind(title = "#" + ch.displayName + (repo.teams.value.firstOrNull { it.id == ch.teamId }?.let { " · ${it.displayName}" } ?: ""), group = true)
        }
        val avatarUrl = repo.avatarUrl(post.userId)
        return MessageNote(
            serverId = repo.server.id, me = repo.myId, channelId = post.channelId,
            threadRoot = if (repo.collapsedThreads) post.rootId else "", postId = post.id,
            senderId = post.userId, senderName = senderName,
            title = kind.title, group = kind.group,
            text = plainPreview(post, repo).ifBlank { "Sent an attachment" }, at = post.createAt,
            avatar = Avatar(avatarUrl, TransportPolicy.mediaHeaders(avatarUrl, repo.client.origin, repo.client.token)),
            // A direct or group message has no team of its own: the one in view is one of the person's.
            team = ch?.teamId?.ifEmpty { null } ?: e.teamId.ifEmpty { null } ?: repo.currentTeamId.value.orEmpty(),
        )
    }

    /**
     * Shows [m], a push for [server], with no live session needed. Not when that conversation was
     * read before it came, not when the post was shown already, and not while the person is
     * looking at that channel of that server; only then is the one-off client [open]ed. In
     * id-loaded mode the post, its sender and its channel are read through it; the sender's
     * picture is loaded with that server's own authorisation, and left out without a client.
     */
    suspend fun showPush(server: SavedServer, m: PushPayload.Message, silent: Boolean, open: suspend () -> OneOff?) {
        if (clears.hides(server.id, m)) return
        if (shown.seen(server.id, m.post)) return
        if (viewing(server.id, m.channel)) return
        val one = open()
        val message = if (m.idLoaded && one != null) PushNotes.idLoaded(one.client, m) else m
        val draft = PushNotes.note(server.id, server.url, server.userId, message, silent)
        val avatar = if (one == null) null else draft.avatar?.let { Avatar(it.url, TransportPolicy.mediaHeaders(it.url, one.client.origin, one.client.token)) }
        show(draft.copy(avatar = avatar), pushed = true)
    }

    /** The live session of [serverId] has [channelId] open in front of the person. */
    private fun viewing(serverId: String, channelId: String): Boolean {
        val started = runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)
        if (!started) return false
        val repo = c.sessions.current.value?.takeIf { it.server.id == serverId } ?: return false
        return repo.readingChannel == channelId
    }

    /**
     * Posts [note], after the conversation's earlier lines. The post is counted as shown only as
     * it is posted, so a show cut short (out of time while the picture loads or the lock is
     * waited for) is not lost; two shows of one post still make one notification. [pushed]: it
     * came by push, and a clear of its conversation that came meanwhile hides it.
     */
    suspend fun show(note: MessageNote, pushed: Boolean = false) {
        if (!permitted()) return
        if (shown.seen(note.serverId, note.postId)) return
        val picture = note.avatar?.let { loadAvatar(it) }
        val sender = Person.Builder().setName(note.senderName).setKey(note.senderId.ifEmpty { null }).apply {
            picture?.let { setIcon(IconCompat.createWithBitmap(it)) }
        }.build()
        val line = NotificationCompat.MessagingStyle.Message(note.text, note.at, sender)
        val tag = NoteIdentity.tag(note.serverId)
        val id = NoteIdentity.id(note.conversation)
        lockOf(tag, id).withLock {
            if (pushed && clears.hides(note.serverId, note.channelId, note.threadRoot, note.at)) return@withLock
            if (!shown.firstShow(note.serverId, note.postId)) return@withLock
            val earlier = current(tag, id, note.head)?.lines.orEmpty()
            post(note.head, noteLines(earlier, line), silent = note.silent, shown = true)
        }
    }

    /**
     * The conversation's notification as last posted: from this process when it posted it, else
     * read back from the one showing (Matty's own "Couldn't send" left out), with [fallback]'s
     * server, conversation and team. Null when there is none. Under the conversation's lock.
     */
    private fun current(tag: String, id: Int, fallback: NoteHead): LastLines.Entry<NotificationCompat.MessagingStyle.Message>? {
        val showing = record(tag, id)
        return when (val r = posted.recall(key(tag, id), showing != null)) {
            is LastLines.Recall.Here -> r.entry
            LastLines.Recall.Dismissed -> null
            LastLines.Recall.Unknown -> showing?.let { n ->
                val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull() ?: return null
                val head = fallback.copy(
                    title = style.conversationTitle?.toString() ?: fallback.title,
                    group = style.isGroupConversation,
                    me = style.user.key ?: fallback.me,
                )
                LastLines.Entry(head, style.messages.filterNot { NoteRules.isNotice(it.text, it.person?.key) }, 0L)
            }
        }
    }

    /**
     * Posts the notification of [head] with [lines] (and Matty's [notice] after them, which is
     * never kept as a line), its tap and its actions built afresh, and remembers what it posted.
     * With more than one server saved, it says whose it is ([NoteRules.whose]). Mark as read
     * carries the time of the newest line from somebody else, which a thread is read up to.
     * [quiet]: an update that makes no sound and no alert. [shown]: it shows a message; Matty's
     * own posts again (a reply, "Couldn't send") keep the time one was last shown. Under the
     * conversation's lock.
     */
    private fun post(
        head: NoteHead, lines: List<NotificationCompat.MessagingStyle.Message>, silent: Boolean,
        quiet: Boolean = false, notice: NotificationCompat.MessagingStyle.Message? = null, shown: Boolean = false,
    ) {
        val tag = NoteIdentity.tag(head.serverId)
        val conversation = head.conversation
        val id = NoteIdentity.id(conversation)
        val me = Person.Builder().setName("You").setKey(head.me.ifEmpty { null }).build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(head.title).setGroupConversation(head.group)
        lines.forEach { style.addMessage(it) }
        notice?.let { style.addMessage(it) }

        val data = Uri.parse(NoteIdentity.data(head.serverId, conversation))
        val open = PendingIntent.getActivity(app, id, Intent(app, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_CHANNEL
            setData(data)
            putExtra(MainActivity.EXTRA_CHANNEL_ID, head.channelId)
            putExtra(MainActivity.EXTRA_SERVER_ID, head.serverId)
            putExtra(EXTRA_ROOT_ID, head.threadRoot)
            putExtra(MainActivity.EXTRA_KEY, c.intentKey)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val replyInput = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
        // The server's time of the newest line shown that is not the person's own (0: none).
        val shownAt = lines.filter { it.person != null }.maxOfOrNull { it.timestamp } ?: 0L
        // Both actions name the server and the conversation they belong to: a notification from
        // one server (or account) must never post into another.
        fun action(name: String) = Intent(app, NotificationActionReceiver::class.java).apply {
            action = name
            setData(data)
            putExtra(MainActivity.EXTRA_SERVER_ID, head.serverId)
            putExtra(MainActivity.EXTRA_CHANNEL_ID, head.channelId)
            putExtra(EXTRA_ROOT_ID, head.threadRoot)
            putExtra(EXTRA_TEAM_ID, head.team)
            putExtra(EXTRA_AT, shownAt)
        }
        val replyIntent = PendingIntent.getBroadcast(app, id, action(ACTION_REPLY), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val readIntent = PendingIntent.getBroadcast(app, id, action(ACTION_READ), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(app, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_matty)
            .setStyle(style)
            .setSubText(NoteRules.whose(head.serverId, c.sessionStore.servers.value))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(silent || quiet)
            .setOnlyAlertOnce(quiet)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setColor(0xFF8AB4FF.toInt())
            // On a locked screen (when the user hides sensitive content) show only that something arrived.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(app, CH_MESSAGES).setSmallIcon(R.drawable.ic_stat_matty)
                    .setContentTitle("Matty").setContentText("New message").setColor(0xFF8AB4FF.toInt()).build()
            )
            .addAction(NotificationCompat.Action.Builder(R.drawable.ic_stat_matty, "Reply", replyIntent)
                .addRemoteInput(replyInput).setAllowGeneratedReplies(true)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build())
            // Like Reply, it needs the phone unlocked: from the lock screen, whoever holds the
            // phone could otherwise mark a conversation read and make its alert go.
            .addAction(NotificationCompat.Action.Builder(R.drawable.ic_stat_matty, "Mark as read", readIntent)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setAuthenticationRequired(true).build())
            .build()
        runCatching { nm.notify(tag, id, n) }
        posted.put(key(tag, id), head, lines, shown)
    }

    /**
     * Reply or Mark as read did not reach the server: the conversation's notification, if it is
     * still there, is posted again, quietly, with Matty's line "Couldn't send" after its lines
     * (which also ends the reply's spinner). [head] names the server, the conversation and the team.
     */
    suspend fun couldNotSend(head: NoteHead, now: Long = System.currentTimeMillis()) {
        val tag = NoteIdentity.tag(head.serverId)
        val id = NoteIdentity.id(head.conversation)
        lockOf(tag, id).withLock {
            val entry = current(tag, id, head) ?: return
            val matty = Person.Builder().setName(NoteRules.NOTICE_NAME).setKey(NoteRules.NOTICE_KEY).build()
            post(entry.head, entry.lines, silent = true, quiet = true,
                notice = NotificationCompat.MessagingStyle.Message(NoteRules.COULD_NOT_SEND, now, matty))
        }
    }

    /**
     * The person's reply [text] reached the server: the conversation's notification is posted
     * once more, quietly, with the reply as their own last line (which lets Android let go of
     * it), and then goes for good; but when a message was posted to it after the action began
     * ([since]), it stays, with the reply after that message ([NoteRules.keptAfterReply]).
     * [head] names the server, the conversation and the team.
     */
    suspend fun replied(head: NoteHead, text: String, since: Long, now: Long = System.currentTimeMillis()) {
        val tag = NoteIdentity.tag(head.serverId)
        val id = NoteIdentity.id(head.conversation)
        lockOf(tag, id).withLock {
            val entry = current(tag, id, head)
            val kept = NoteRules.keptAfterReply(entry?.shownAt, since)
            if (entry != null && (kept || record(tag, id) != null)) {
                val own = text.trimEnd().takeIf { it.isNotBlank() }?.let { NotificationCompat.MessagingStyle.Message(it, now, null as Person?) }
                post(entry.head, if (own == null) entry.lines else noteLines(entry.lines, own), silent = true, quiet = true)
                if (kept) return@withLock
                awaitReleased(tag, id)
            }
            posted.forget(key(tag, id))
            gone(tag, id)
        }
    }

    /** "Push notifications are working", for [server]. */
    fun showTested(server: SavedServer) =
        notice(server, "Push notifications are working", "Matty can notify you about ${server.name} without staying connected.")

    /**
     * The session on [server] is over: told once, after its message notifications have gone. The
     * notice carries the server's address (not shown), and takes the place of an earlier
     * "Signed out" notice for that address. Its title names the account too when another saved
     * server has that server's name ([NoteRules.signedOutTitle]).
     */
    fun showEnded(server: SavedServer) {
        clearSignedOut(server.url)
        notice(server, NoteRules.signedOutTitle(server, c.sessionStore.servers.value),
            "Your session on ${server.name} ended. Sign in again to get notifications.", signedOut = server.url)
    }

    /** Signed in to the server at [address]: its "Signed out" notices go. Other servers' stay. */
    fun signedIn(address: String) = clearSignedOut(address)

    private fun clearSignedOut(address: String) {
        runCatching {
            app.getSystemService(NotificationManager::class.java).activeNotifications
                .filter { NoteIdentity.isNotice(it.tag) && NoteRules.sameAddress(it.notification.extras?.getString(EXTRA_SIGNED_OUT), address) }
                .forEach { nm.cancel(it.tag, it.id) }
        }
    }

    private fun notice(server: SavedServer, title: String, text: String, signedOut: String? = null) {
        if (!permitted()) return
        val n = NotificationCompat.Builder(app, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_matty)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setColor(0xFF8AB4FF.toInt())
            .setContentIntent(PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .apply { signedOut?.let { addExtras(Bundle().apply { putString(EXTRA_SIGNED_OUT, it) }) } }
            .build()
        runCatching { nm.notify(NoteIdentity.noticeTag(server.id), NOTICE_ID, n) }
    }

    private fun permitted() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun record(tag: String?, id: Int): android.app.Notification? = runCatching {
        app.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.tag == tag && it.id == id }?.notification
    }.getOrNull()

    /** The picture, with exactly the headers [avatar] names: never the live session's token for another server. */
    private suspend fun loadAvatar(avatar: Avatar): Bitmap? = ceo.villa.matty.data.runCatchingCancellable {
        val req = ImageRequest.Builder(app).data(avatar.url).size(128).allowHardware(false).apply {
            if (avatar.headers.isNotEmpty()) httpHeaders(NetworkHeaders.Builder().apply { avatar.headers.forEach { (k, v) -> set(k, v) } }.build())
        }.build()
        val res = withTimeoutOrNull(AVATAR_MS) { c.imageLoader.execute(req) } as? SuccessResult ?: return null
        withContext(Dispatchers.Default) { circle(res.image.toBitmap()) }
    }.getOrNull()

    private fun circle(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, Rect(0, 0, size, size), Rect(0, 0, size, size), paint)
        return out
    }

    fun connectionNotification(serverName: String): android.app.Notification =
        NotificationCompat.Builder(app, CH_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_matty)
            .setContentTitle("Connected to $serverName")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_CONNECTION = "connection"
        const val KEY_REPLY = "reply"
        const val EXTRA_ROOT_ID = "root_id"
        const val EXTRA_TEAM_ID = "team_id"
        /** The server's time (Unix ms) of the newest line a notification showed; 0 when none is known. */
        const val EXTRA_AT = "shown_at"
        /** A "Signed out" notice's server address. */
        private const val EXTRA_SIGNED_OUT = "ceo.villa.matty.SIGNED_OUT"
        const val ACTION_REPLY = "ceo.villa.matty.REPLY"
        const val ACTION_READ = "ceo.villa.matty.READ"
        private const val NOTICE_ID = 1
        /** A picture that takes longer is left out: a push has seconds in all. */
        private const val AVATAR_MS = 3_000L
        /** How long a held notification is waited on after it was posted again (20 x 50 ms). */
        private const val RELEASE_TRIES = 20
        private const val RELEASE_STEP_MS = 50L
    }
}

/**
 * Reply and Mark as read, for the saved server and conversation the intent names, whether or
 * not that server is connected: always through a one-off client ([OneOff]), so the reply goes
 * out without making the person show as online and nothing starts the app's live session. On
 * success the notification goes; on failure, or while the server's sign-in can't be opened, it
 * says "Couldn't send"; it goes unanswered only for a server no longer saved. A session the
 * server confirms is over is ended as a push's `ended` would end it.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // When the action began: what is posted to the conversation after this stays.
        val since = System.currentTimeMillis()
        // Counted from here: the time Android gives the broadcast runs from its arrival.
        val deadline = RequestDeadline.after(REQUESTS_MS)
        val action = intent.action
        if (action != Notifier.ACTION_REPLY && action != Notifier.ACTION_READ) return
        val channelId = intent.getStringExtra(MainActivity.EXTRA_CHANNEL_ID) ?: return
        val c = context.container
        if (intent.data == null) {
            // From a notification posted before notifications named their server: nothing is
            // sent, and it goes by the identity it was posted under.
            val pending = goAsync()
            c.appScope.launch { try { c.notifier.removeUnnamed(channelId) } finally { pending.finish() } }
            return
        }
        val serverId = intent.getStringExtra(MainActivity.EXTRA_SERVER_ID) ?: return
        val threadRoot = intent.getStringExtra(Notifier.EXTRA_ROOT_ID).orEmpty()
        val team = intent.getStringExtra(Notifier.EXTRA_TEAM_ID).orEmpty()
        val head = NoteHead(serverId, me = "", channelId = channelId, threadRoot = threadRoot, team = team, title = null, group = false)
        // The data names the same server and conversation as the extras, or it isn't ours.
        if (intent.data?.toString() != NoteIdentity.data(serverId, head.conversation)) return
        val text = if (action == Notifier.ACTION_REPLY) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString() ?: return
        } else null
        val shownAt = intent.getLongExtra(Notifier.EXTRA_AT, 0L)
        val pending = goAsync()
        c.appScope.launch(deadline) {
            try {
                val done = withTimeoutOrNull(WORK_MS) { act(c, head, text, shownAt) }
                if (done == null) c.notifier.couldNotSend(head)
                // The server took it: never "Couldn't send" after that, and what follows is held
                // to the broadcast's time, not to the sending's.
                else if (done) withTimeoutOrNull(deadline.leftMs() + (FINISH_MS - REQUESTS_MS)) {
                    if (text != null) c.notifier.replied(head, text, since) else c.notifier.markedRead(head, since)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Sends the reply or the mark: true when the server took it; otherwise what that came to is shown here. */
    private suspend fun act(c: AppContainer, head: NoteHead, text: String?, shownAt: Long): Boolean {
        val serverId = head.serverId
        // Signed out of, ended, or a sign-in that doesn't open now: never act on another account.
        val one = withContext(Dispatchers.IO) { c.sessions.oneOff(serverId, OneOff.ACTION_SECONDS) }
        if (one == null) {
            // Still saved: its sign-in can't be opened right now (an ended session's
            // notifications went when it was marked). Gone: so does its notification.
            if (c.sessionStore.servers.value.any { it.id == serverId }) c.notifier.couldNotSend(head)
            else c.notifier.cancel(serverId, head.conversation)
            return false
        }
        val outcome = if (text != null) NotificationActions.reply(one.client, one.server.userId, head.channelId, head.threadRoot, text,
            NotificationActions.replyId(serverId, one.server.userId, head.conversation, shownAt, text))
        else NotificationActions.markRead(one.client, one.server.userId, head.channelId, head.threadRoot, head.team, shownAt)
        return when (outcome) {
            NotificationActions.Outcome.Done -> true
            NotificationActions.Outcome.Failed -> { c.notifier.couldNotSend(head); false }
            NotificationActions.Outcome.Ended -> { c.sessions.endedAway(serverId); false }
        }
    }

    private companion object {
        /**
         * A notification's action comes as a foreground broadcast, which Android gives about 10
         * seconds in all. Every request it makes (the call, the older route a server without the
         * new one needs or the team a thread is marked in, and the check of a 401) is over by
         * [REQUESTS_MS] after it arrived, and the sending by [WORK_MS]; then "Couldn't send".
         * Once the server took it, the notification is seen to by [FINISH_MS] after it arrived.
         */
        const val REQUESTS_MS = 7_000L
        const val WORK_MS = 8_000L
        const val FINISH_MS = 9_500L
    }
}
