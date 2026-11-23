package ceo.villa.matty.plugins.villanet

import android.content.pm.ServiceInfo
import ceo.villa.matty.data.api.MattermostJson

/*
 * The pure parts of voice channels: what the data channel says, the offer's shape, what a refusal
 * means for trying again, when to try again, the voice service's type, and which audio output to
 * use. Nothing here touches Android or WebRTC, so the tests run it as it is.
 */

/** Where a voice session stands. */
sealed interface VoiceStatus {
    /** Not in voice. */
    data object Idle : VoiceStatus
    /** Joining, the first time. */
    data object Connecting : VoiceStatus
    /** In the channel, heard and hearing. */
    data object Connected : VoiceStatus
    /** The connection went and is being made again (or the server asked to wait). */
    data object Reconnecting : VoiceStatus
    /** Over, and why, in a sentence to show. */
    data class Failed(val sentence: String) : VoiceStatus
}

/**
 * Whether Matty may use the microphone: granted, not asked in this session, refused once (Android
 * asks again the next time), or refused for good (only Android's settings can allow it now; kept
 * over a restart of Matty until the permission is found granted).
 */
enum class MicPermission { Granted, NotAsked, Declined, Refused }

/** One message the server sent on the `voice` data channel. */
internal sealed interface VoiceMessage {
    /** The sessions being heard on this phone right now, and this phone's own while its voice goes out. */
    data class Talking(val sessions: Set<Long>) : VoiceMessage
    /**
     * This session's channel, whether the voice server's admin muted it ([muted]), whether it may
     * not speak in that channel ([suppressed]), and the voice server's own word on the session's
     * self mute and self deafen ([selfMuted], [selfDeafened]; false from a plugin that doesn't say).
     */
    data class Self(
        val channel: Int,
        val muted: Boolean,
        val suppressed: Boolean,
        val selfMuted: Boolean = false,
        val selfDeafened: Boolean = false,
    ) : VoiceMessage
    /** The session is over: [code] is kicked, banned, mumble, replaced, off or session. */
    data class End(val code: String, val reason: String) : VoiceMessage
}

/** A message from the data channel, or null for anything that isn't one. */
internal fun voiceMessageOf(text: String): VoiceMessage? {
    val s = runCatching { MattermostJson.decodeFromString(VnVoiceSignal.serializer(), text) }.getOrNull() ?: return null
    return when {
        s.end != null -> VoiceMessage.End(s.end, s.reason.trim())
        s.self != null -> VoiceMessage.Self(s.self.channel, s.self.muted, s.self.suppressed, s.self.selfMuted, s.self.selfDeafened)
        s.talking != null -> VoiceMessage.Talking(s.talking.toSet())
        else -> null
    }
}

/** Ends of a session that are tried again, as a lost connection is: the voice server went. */
internal const val END_MUMBLE = "mumble"

/** What to say when the server ended a session and gave no sentence of its own. */
internal fun endSentence(code: String, reason: String): String = reason.ifBlank {
    when (code) {
        "kicked" -> "You were removed from the voice channel."
        "banned" -> "You were banned from the voice server."
        "replaced" -> "You joined voice from somewhere else."
        "off" -> "Voice is no longer available here."
        "session" -> "Your session has ended."
        else -> "You left voice."
    }
}

/** What a refused join or move means: wait and try again, or stop. */
internal enum class VoiceRetry { Wait, Stop }

/**
 * A refusal, or no answer at all: whether to [retry], the [sentence] to show, and the least wait
 * the server asked for. [serverSaid]: a refusal to wait out (429, or 503) whose sentence is the
 * server's own, not the app's.
 */
internal data class VoiceRefusal(val retry: VoiceRetry, val sentence: String, val waitMs: Long = 0, val serverSaid: Boolean = false)

/** The plugin's own code for a channel that does not exist. */
internal const val VOICE_NOT_FOUND = "not_found"

/**
 * What a refusal means for a join: `429` and `503` (and no answer at all, or a server error) are
 * waited out and tried again, and so is a `404` without the plugin's own `not_found` [code]
 * (Mattermost's answer while the plugin is not running, as while it restarts or is upgraded);
 * everything else (`400`, `403 voice_denied`, `locked`, `watch_only`, `app_unverified`,
 * `404 not_found`, `409 voice_refused`, `401`) stops, with the server's own sentence. [status] is
 * null when nothing answered; [code] is the refusal's own code (empty when the answer isn't the
 * plugin's); [name] is what this server calls the plugin.
 */
internal fun voiceRefusalOf(status: Int?, sentence: String?, retryAfterMs: Long, name: String, code: String = ""): VoiceRefusal {
    val said = sentence?.trim().orEmpty()
    return when {
        status == null -> VoiceRefusal(VoiceRetry.Wait, VillaNetApi.notAnswering(name))
        status == 404 && code != VOICE_NOT_FOUND -> VoiceRefusal(VoiceRetry.Wait, VillaNetApi.notAnswering(name))
        status == 429 || status == 503 || status >= 500 -> VoiceRefusal(
            VoiceRetry.Wait, said.ifEmpty { VillaNetApi.notAnswering(name) }, retryAfterMs.coerceAtLeast(0),
            serverSaid = (status == 429 || status == 503) && said.isNotEmpty(),
        )
        else -> VoiceRefusal(VoiceRetry.Stop, said.ifEmpty { "Couldn't join voice ($status)." })
    }
}

/**
 * Whether a refused microphone is refused for good (Android won't ask again, and only its
 * settings can allow it): Android explained the request before it ([explainedBefore]) and no
 * longer does after it, or a refusal for good was [remembered] already. A question dismissed
 * without an answer leaves both false, and is not one.
 */
internal fun voiceMicRefusedForGood(granted: Boolean, explainedBefore: Boolean, explainedAfter: Boolean, remembered: Boolean): Boolean =
    !granted && (remembered || (explainedBefore && !explainedAfter))

/** How long before try number [n] (0 is the first after a loss): 1, 2, 5 and 10 seconds, then every 15. */
internal fun voiceRetryDelay(n: Int): Long = RETRY_STEPS.getOrElse(n) { RETRY_EVERY_MS }

private val RETRY_STEPS = longArrayOf(1_000, 2_000, 5_000, 10_000)
private const val RETRY_EVERY_MS = 15_000L

/**
 * The voice service's foreground type: microphone and media playback when the microphone may be
 * used at the moment the service starts (or the permission has just been granted), media
 * playback alone otherwise. Muting never changes it: the microphone is let go by the WebRTC
 * connection, not by the service.
 */
internal fun voiceServiceType(micGranted: Boolean): Int =
    if (micGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

/**
 * What reaches the voice service: its start (the person tapped Join), the notification's Mute,
 * Unmute or Leave, or the microphone found allowed with Matty on screen (the permission granted,
 * an unmute in the app, Matty coming to the front).
 */
internal enum class VoiceServiceCall { Start, NotificationAction, MicAllowed }

/**
 * The type the voice service becomes a foreground service with for [call], or null to leave it as
 * it is (startForeground is not called). [foreground]: the instance is one already; [hasMic]: its
 * type has the microphone; [micGranted]: the permission is granted now. An instance becomes a
 * foreground service once, at its start; after that its type only ever gains the microphone, and
 * only with Matty on screen. The notification's buttons never change it (they may be tapped with
 * Matty in the background, where Android refuses the microphone and can take it away).
 */
internal fun voiceServiceTypeFor(call: VoiceServiceCall, foreground: Boolean, hasMic: Boolean, micGranted: Boolean): Int? = when (call) {
    VoiceServiceCall.Start -> if (foreground) null else voiceServiceType(micGranted)
    VoiceServiceCall.NotificationAction -> null
    VoiceServiceCall.MicAllowed -> if (foreground && !hasMic && micGranted) voiceServiceType(true) else null
}

/** What the voice notification's microphone button does: mute, unmute, or open Matty to unmute there. */
internal enum class VoiceNotificationMic { Mute, Unmute, OpenToUnmute }

/**
 * The notification's microphone button for a session that is [muted], from a voice service whose
 * type has the microphone or not ([hasMic]). Without it, Android gives a service in the
 * background silence, so an unmute from there would show the person unmuted and send nothing:
 * Unmute opens Matty instead, which unmutes on screen, where the service can take the microphone.
 */
internal fun voiceNotificationMicOf(muted: Boolean, hasMic: Boolean): VoiceNotificationMic = when {
    !muted -> VoiceNotificationMic.Mute
    hasMic -> VoiceNotificationMic.Unmute
    else -> VoiceNotificationMic.OpenToUnmute
}

// ---- the offer ---------------------------------------------------------------------------------

/** What an SDP offer holds: its audio sections by direction, data sections, and anything else. */
internal data class OfferShape(
    val sending: Int,
    val receiving: Int,
    val other: Int,
    val data: Int,
    /** The sending section offers Opus. */
    val opus: Boolean,
    /** The sending section offers the audio level header extension. */
    val audioLevel: Boolean,
)

internal const val AUDIO_LEVEL_URI = "urn:ietf:params:rtp-hdrext:ssrc-audio-level"

/** The offer the contract asks for has 1 to this many receive-only audio sections; this app offers [VOICE_SLOTS]. */
internal const val MAX_RECEIVING = 8

/** How many voices the server passes to one phone at once, and so how many receive-only sections the app offers. */
internal const val VOICE_SLOTS = 4

internal fun offerShapeOf(sdp: String): OfferShape {
    var sending = 0
    var receiving = 0
    var other = 0
    var data = 0
    var opus = false
    var level = false
    // One m= section at a time: its kind, its own direction (sendrecv when it names none), and what it offers.
    var kind: String? = null
    var direction = "sendrecv"
    var hasOpus = false
    var hasLevel = false
    fun close() {
        when (kind) {
            null -> {}
            "audio" -> when (direction) {
                "sendonly" -> { sending++; opus = opus || hasOpus; level = level || hasLevel }
                "recvonly" -> receiving++
                else -> other++
            }
            "application" -> data++
            else -> other++
        }
    }
    for (raw in sdp.lineSequence()) {
        val line = raw.trimEnd('\r')
        when {
            line.startsWith("m=") -> {
                close()
                kind = line.removePrefix("m=").substringBefore(' ')
                direction = "sendrecv"
                hasOpus = false
                hasLevel = false
            }
            kind == null -> {}
            line == "a=sendonly" || line == "a=recvonly" || line == "a=sendrecv" || line == "a=inactive" -> direction = line.removePrefix("a=")
            line.startsWith("a=rtpmap:") && line.substringAfter(' ').startsWith("opus/48000", ignoreCase = true) -> hasOpus = true
            line.startsWith("a=extmap:") && line.substringAfter(' ').substringBefore(' ') == AUDIO_LEVEL_URI -> hasLevel = true
        }
    }
    close()
    return OfferShape(sending, receiving, other, data, opus, level)
}

/** Null when [sdp] is the offer the contract asks for; otherwise what is wrong with it (for the log). */
internal fun voiceOfferTrouble(sdp: String): String? {
    val s = offerShapeOf(sdp)
    return when {
        s.sending != 1 -> "${s.sending} sending audio sections"
        !s.opus -> "the microphone's section offers no Opus"
        !s.audioLevel -> "the microphone's section offers no audio level"
        s.receiving !in 1..MAX_RECEIVING -> "${s.receiving} receiving audio sections"
        s.data != 1 -> "${s.data} data sections"
        s.other != 0 -> "${s.other} other sections"
        else -> null
    }
}

// ---- audio output ------------------------------------------------------------------------------

/** The kinds of audio output a call can use. */
enum class VoiceOutputKind { Speaker, Earpiece, Headset, Bluetooth, Other }

/** One output on offer: [id] is Android's id for the device, [name] what to call it. */
data class VoiceOutput(val id: Int, val kind: VoiceOutputKind, val name: String) {
    /** Heard by the person alone: the stream playing beside a call can't reach the microphone. */
    val private: Boolean get() = kind == VoiceOutputKind.Headset || kind == VoiceOutputKind.Bluetooth
}

/**
 * The output to use from those [available]: the person's [chosen] one while it is there, else a
 * headset, else Bluetooth, else the speaker, else the earpiece, else whatever there is.
 */
internal fun pickOutput(available: List<VoiceOutput>, chosen: Int?): VoiceOutput? {
    chosen?.let { id -> available.firstOrNull { it.id == id }?.let { return it } }
    for (kind in listOf(VoiceOutputKind.Headset, VoiceOutputKind.Bluetooth, VoiceOutputKind.Speaker, VoiceOutputKind.Earpiece)) {
        available.firstOrNull { it.kind == kind }?.let { return it }
    }
    return available.firstOrNull()
}

/** What is left of the person's choice of output when [available] changes: it goes with its device. */
internal fun keptChoice(available: List<VoiceOutput>, chosen: Int?): Int? = chosen?.takeIf { id -> available.any { it.id == id } }
