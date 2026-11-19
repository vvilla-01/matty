# Plugins in Matty

Mattermost plugins have two halves: a **server** part (Go) and an optional **webapp** part
(React). The official mobile app can't run the webapp half, so anything a plugin draws in the
browser — panels, buttons in the channel header, custom post cards — simply isn't there on a
phone.

Matty handles plugins at two levels.

## 1. Works for every plugin, automatically

These ride on standard Mattermost APIs, so they work for any plugin with no extra code:

| Surface | How Matty handles it |
|---|---|
| Slash commands | `/` in the composer lists commands from `GET /teams/{id}/commands/autocomplete_suggestions`, including plugins' dynamic arguments |
| Message attachments | `props.attachments` render natively — fields, images, colors, footers — with **buttons** and **select menus** that call `POST /posts/{id}/actions/{action}` |
| Interactive dialogs | The `open_dialog` WebSocket event opens a native dialog (text, textarea, select with static/users/channels sources, bool, radio), submitted to `/actions/dialogs/submit` with per-field errors |
| Ephemeral messages | Shown inline, marked "Only visible to you" |
| Bot & webhook posts | Override names and icons are honoured; BOT tags shown |
| Unknown custom post types | The message text and attachments render, with a quiet "sent by a plugin" note instead of "unsupported" |

## 2. Native modules

A native module is a Kotlin class implementing `ceo.villa.matty.plugins.MattyPlugin`. It is
switched on automatically when the server reports the plugin as active (`GET /plugins/webapp`).

```kotlin
interface MattyPlugin {
    val id: String                    // Mattermost plugin id, e.g. "com.example.polls"
    val name: String
    val description: String
    val formerIds: Set<String>        // ids the plugin had before: their /plug/<id>/ links open here too
    @Composable fun displayName(): String // the name on the Plugins screen, if it can change
                                          // (Matty Plugin: what the server calls it); else name
    @Composable fun panelTitle(): String  // the header button's name, if it can change; else panel.title

    val postTypes: Set<String>        // custom post types you draw
    @Composable fun PostContent(post: Post, host: PluginHost)

    val panel: PluginPanel?           // full-screen panel: channel header button + Plugins hub
    @Composable fun panelBadge(): Int // a red count on that header button (Matty Plugin: people live)

    fun onAttach(repo: ChatRepository)
    fun onDetach()
    fun onEvent(name: String, event: WsEvent)   // your custom_<id>_* socket events
    fun onForeground(foreground: Boolean)

    @Composable fun person(userId: String): PersonFacts?        // what you know about a person, for
                                                               // everywhere they are shown (below)
    @Composable fun notice(): String?                          // a standing line on the Plugins screen
                                                               // (Matty Plugin: the server's is too old)
    @Composable fun checks(): List<PluginCheck>                // what you found when you checked, one line
                                                               // each, on the Plugins screen and in Settings
                                                               // (Matty Plugin: a signed release; this app
                                                               // verified by the server)
    @Composable fun ProfileExtras(userId: String, host: PluginHost)
    @Composable fun ProfileOver(userId: String, host: PluginHost) // kept while a profile is open, outside
                                                               // its sheet (Matty Plugin: the crop screen)
    fun checkOutgoing(text: String): String?                    // block a message, with a reason
    fun panelArgsForLink(path: String): Map<String, String>?   // handle {site}/plug/<id>/...
    @Composable fun Overlay(host: PluginHost)                   // float UI over the app (a popout)
    @Composable fun hasMiniBar(): Boolean                      // something to control from anywhere?
    @Composable fun MiniBar(host: PluginHost, modifier: Modifier) // that control: one 56dp pill, which
                                                               // Matty floats above the bottom pills
                                                               // (Matty Plugin: the stream, panel closed)
}
```

Everything has a default, so implement only what your plugin needs.

`person()` is asked for every avatar and every member row, so make it one lookup that only changes
when that person's facts do (Matty Plugin's module keeps a map by user id and subscribes each caller to its one
entry). What comes back is read through `personOf(userId)`:

```kotlin
data class PersonFacts(
    val live: Boolean,             // on the air: a LIVE ring on their avatar everywhere
    val voice: VoiceState?,        // in voice: a mic in their presence badge (Open / Muted)
    val activity: UserActivity?,   // "Playing ELDEN RING" under their name in members and DMs
    val holdsStatus: Boolean,      // their custom status is your plugin's badge, not their words
)
```

### Talking to your plugin's server

`repo.client` is an authenticated `MattermostClient`. Its `http` (OkHttp) and `token` let you
call `"${repo.client.baseUrl}/plugins/<id>/..."` with `Authorization: Bearer <token>` and
`X-Requested-With: XMLHttpRequest`, exactly like the webapp does with its session cookie.
Mattermost injects `Mattermost-User-Id` for you.

Images from your plugin's routes load through Matty's shared image loader, which attaches the
session token only for the signed-in server's host.

`X-Requested-With` is for the calls your module itself decides to make. A server (or a plugin)
may answer a costly read only with that header, because in a browser a picture in a post can't
carry it. So never put it on anything you load as a picture, a video or a download: those are
addresses somebody else may have chosen. Use `TransportPolicy.mediaHeaders(url, origin, token)`
for them (the token only, and only to the signed-in server); a test in `TransportPolicyTest` lists
the only places that may set the header.

### Registering

Add your module to `PluginRegistry.builtIn`:

```kotlin
val builtIn: List<MattyPlugin> = listOf(
    VillaNetPlugin(c),
    PollsPlugin(c),   // ← yours
)
```

### A minimal example

```kotlin
class PollsPlugin(private val c: AppContainer) : MattyPlugin {
    override val id = "com.example.polls"
    override val name = "Polls"
    override val description = "Vote right from the message."
    override val postTypes = setOf("custom_poll")

    @Composable
    override fun PostContent(post: Post, host: PluginHost) {
        val question = post.prop("question").orEmpty()
        MattyCard {
            Text(question, style = MaterialTheme.typography.titleSmall)
            // …options, votes, a button that POSTs to /plugins/com.example.polls/api/vote
        }
    }
}
```

### Design guidelines

- Use Matty's components (`MattyCard`, `PillButton`, `ChipPill`, `FloatingTopBar`,
  `EmptyState`) and `Matty.colors` so modules match the rest of the app in every theme.
- Treat post props as untrusted hints; fetch authoritative state from your plugin.
- Poll only while your UI is visible; prefer your plugin's WebSocket events.
- Never store secrets the user types; send them once and forget them.

## Matty Plugin's module

`plugins/villanet/` is the reference module, for [Matty Plugin](https://github.com/vvilla-01/matty-plugin)
(id `ceo.villa.matty`), Matty's companion on the server, for live streams. Mattermost lists it as
"Matty". A server gives it a name of its own (System Console › Plugins › Matty › Name and artwork),
which the module shows wherever a person reads it (the Plugins screen, the panel's header button
and title, "Live on Acme TV — Watch", "Acme TV is locked." from a server that named it Acme TV),
from `name` in `GET /api/v1/access`, and "Matty" until the server says. Words about the software
itself (that it is too old, which release it is) say "Matty Plugin" whatever the server calls it.
This version works with Matty Plugin 4.0.0 or newer (contract revision 4). It covers:

- The client contract it shares with the plugin (the plugin's `contract/README.md`): the app is
  written for one revision of it, says so when the server's plugin is older ("Matty Plugin needs an
  update", with nothing asked of the old plugin) or newer, and its tests decode the plugin's own
  fixtures (`app/src/test/resources/villanet`, copied by `tools/sync-villanet-contract.sh`;
  `VillaNetContractTest` fails when the copies and the plugin's differ, when the app assumes
  something the server didn't send, or when the server sends a field the app neither reads nor
  lists as not read)
- `GET /api/v1/access` (who the reader is, which plugin, its name and its release, and, for this
  session, the app check's verdict), then `GET /api/v1/streams` and
  `GET /api/v1/accounts`, each sent with the tag of the copy in hand so that "nothing changed" is
  a few bytes; kept fresh by the `streams` socket event (whose `payload` is a JSON string), one
  round of reads at a time (never two within two seconds), and a 30 s conditional safety poll only
  while one of its screens is open
- Who is live, in voice and wearing the plugin's badge straight from the snapshot's `people` index:
  the app never works those out from the cards or the voice roster
- The bearer-token unlock (`POST /api/v1/me/link`), with the plugin's own refusal wording
- Push notifications, from plugin 4.1.0 and only while `push.state` in `/access` is `on`
  (`push/PushCenter.kt`, not this module: it works for every saved server, not only the one on
  screen, through a one-off client for that server). At every app start for each saved server,
  and for the server on screen whenever Settings comes back to it: `GET /api/v1/access` for the
  key, then `GET /api/v1/me/push`. `PUT /api/v1/me/push` (`{endpoint, p256dh, auth, key}`) with
  each new UnifiedPush address, when the server answers `none` or `refresh`, once when it reports
  `refused` or `failing` (not again by itself until it answers `confirmed` or `refresh`, or a new
  address goes: the same address given again by the UnifiedPush app, or the server's `none` once it
  let that registration go, is the same try), and for Settings' **Try again**; an address the server refuses with
  `400` is not sent again until the UnifiedPush app gives another, push is set up again, or
  **Try again** is pressed. `POST /api/v1/me/push/confirm` with the `hello`
  push's nonce; with no `hello` 45 s after `pending`, `GET /api/v1/me/push` again, and the same
  address goes again (twice more at most) only while the server has not confirmed it. `POST
  /api/v1/me/push/test` for Settings' **Send a test**. `DELETE /api/v1/me/push` when push is
  switched off, when the UnifiedPush app stops delivering or is removed, and just before a
  sign-out ends the session (that one is tried once: the ended session stops the pushes anyway).
  Any other DELETE stays owed until the plugin answers it (a `404`, which is Mattermost's while
  the plugin is not running, does not settle it); a PUT or confirm the plugin does not take (no
  network, a limit, a `404`, `app_unverified`) is owed too. What is owed is sent at the next app
  start (a DELETE also while push is off), when the network comes back after an app start, and
  once the app check passes; an owed PUT or confirm, or a server that could not be asked, also
  when Matty comes back to the front (at most once in 30 s a server); then a saved server that
  offers push (or has not said yet), with no registration and not stopped by the UnifiedPush app
  (it stopped delivering, or was removed), is registered as well, while the UnifiedPush app takes
  registrations. An owed PUT or confirm is
  also sent again by itself while Matty runs (not for `app_unverified`): after the server's
  `retry_after_ms` when it names one, else after 30 s, then twice more, the wait doubling, ten
  minutes at most. An owed DELETE goes before that server is registered again, and while it cannot
  be paid the server counts as not reached. The pushes themselves (`message`, `clear`,
  `hello`, `test`, `ended`) are decrypted and read on the phone without connecting to the server.
  An `ended` push takes that server's notifications off at once, then asks `GET /api/v4/users/me`,
  and after a `401` asks once more a second later: only Mattermost's own `401` (its error, with an
  id) both times signs that server out; an answer for the person signed in gives the registration
  back (`GET /api/v1/me/push` then says `none`, and the address is PUT again); anything else (no
  answer, a `401` page from something in front of the server, another status) leaves the server
  to be asked again when Matty comes back to the front or the network comes back, and at the next
  start. What Settings shows is under [Push notifications](#push-notifications)
- WHEP playback: receive-only audio+video transceivers, no ICE servers, an ICE-restart offer
  with stereo Opus, POSTed to `/api/v1/whep` with `X-Stream-Handle`. Always both, even for a
  stream that is only being listened to: with Matty minimised the picture is switched off on the
  app's side (`VideoTrack.setShouldReceive`, so nothing is decoded; `StreamWatch` decides when),
  never by offering the sound alone
- In voice, Up next (join plans), boards, 24/7 channels, watcher faces
- Clips: grid, playback in ExoPlayer (skipping `leadMs`), a big thumbs-up button under the
  video, sharing to a channel
- `custom_villanet_clip` / `custom_villanet_plan` cards, batched `/clips/cards` lookups; a clip
  card pops the clip out over the chat (`Overlay`) instead of leaving for the Clips tab; links
  under the plugin's earlier id, `ceo.villa.villanet`, open as the new ones do
- LIVE rings on streamers' avatars and a "Live on <name> — Watch" row on their profiles
- The composer guard that refuses a bare Broadcast Box token
- The three checks below, through `checks()`

### Checking each other

Three checks, each said in one line in Settings → Verification. The second and third are also
said on the Plugins screen under the plugin and in the panel's menu; the first is said only in
Settings. None of them stops anything working, unless the server's admins
choose to refuse a Matty that fails the third. The certificates they rest on are Matty's own two,
RSA 4096 and ML-DSA-87, whose digests are under [Verifying Matty](BUILDING.md#verifying-matty)
(`verify/MattyCertificates.kt`; a test holds them to those digests).

**The app, of itself** (`verify/SelfCheck.kt`). Once at start, the app reads Android's record of
how it is signed: the official build is signed now with the ML-DSA certificate, with the RSA one
before it in its history, and nothing else. Settings → Verification says "This copy of Matty is
the official build: Android says it is signed with Matty's RSA and ML-DSA keys." or "This copy of
Matty is not the official build."; a debug build says "This is a development build of Matty,
signed with a development key." It is the app's own word about itself, so it catches a copy that
was repackaged carelessly and nothing more. It is the one place the post-quantum signer is looked
at from the app's side.

**The plugin's release** (`VillaNetRelease.kt`, `verify/ReleaseSignatures.kt`). A release of Matty
Plugin is signed with the same two keys: its bundle carries a manifest of its files (each one's
SHA-256 and size) and an RSA and an ML-DSA-87 signature over the manifest. When `GET /api/v1/access`
shows a version the app has not checked on this server, it fetches `GET /api/v1/release` and
verifies both signatures with the phone's own providers against the two certificates it holds
itself (never one that travels), then reads the manifest strictly: product `ceo.villa.matty`, kind
`plugin`, and the version the server reports. The line is "Matty Plugin 4.0.0 · signed release
(RSA + ML-DSA)", "Matty Plugin 4.0.0 · not a signed release" (a development build, or somebody's
own), or "Matty Plugin 4.0.0 · release not verified: " and why. It is never signed on one
signature alone, and a phone that cannot check ML-DSA-87 says so. What this shows is which release
the server presents, not what code it runs: a server program that was altered can present a
genuine release's manifest. An older genuine release still reads signed. Unsigned and unverified
plugins work all the same.

**The app, to the server** (`VillaNetAppCheck.kt`, `verify/KeyAttestation.kt`). The server's
setting Check the Matty app (System Console › Plugins › Matty › App check) is *Check, and show the
result* by default; *Off*, or *Check, and refuse a
Matty that fails*. While it is on, the app proves itself once per session and app version, in the
background: it asks for a challenge (`POST /api/v1/app/challenge`), has the phone's secure hardware
(StrongBox where the phone has it, else its trusted environment) make a new key for a challenge
derived from that one, the address the app reaches the server at and the person's user id, reads
the key's certificate chain, deletes the key, and sends the chain (`POST /api/v1/app/attest`). The
chain is a statement signed in the phone's secure hardware, back to Google's attestation roots,
naming the app that asked (its package and the certificate it is signed with) and how the phone
booted. The server holds it to those roots, to a copy of Google's revocation list shipped with its
release, and to Matty's RSA certificate (or one its admins add); the phone must have a locked
bootloader and a verified system, or GrapheneOS, or a boot key its admins list. The line is "This
phone's Matty is verified by this server", "Not verified by this server." and why not, "This
phone's Matty is being checked by this server", "Not checked by this server yet." and why this
phone could not make the proof, the server's own reason when it holds nothing (its rules for the
check have changed, or it could not read its records), or "This phone's Matty has not been checked by this server yet"; with the check off there is none. A
server that refuses answers Matty `403` (`app_unverified`) everywhere but the lock screen, these
checks and taking its push registration back (`DELETE /api/v1/me/push`, from 4.1.0), and the panel says "Matty is for the official Matty app" (the server's name in the first
place) with the server's reason and **Try again**, which proves the app once more; so does
Refresh. When the server's admins change the check's setting or what it accepts (or the Site
URL), the server tells every open Matty at once, and a verdict given under the old rules no
longer counts: the app proves itself again at once, or half a minute after its last proof if that
was more recent, and Refresh proves it at once (a server that keeps dropping its verdicts is
proved to five times, half a minute apart, then not again for ten minutes); a refusal on any request is heard at
once, and the panel then shows it on every tab instead of what the server refuses, until a
request is answered again. The web panel is never refused.

What it cannot do: Android attests an app's first certificate, the RSA one, never the ML-DSA one,
so the post-quantum signature protects installs and updates on the phone but cannot be proved to a
server. A program that hides what it is cannot be told from the web panel, so refusing stops honest
copies that were altered or rebuilt, not a determined impostor. And it holds only while the phone
itself is intact: its secure hardware unbroken and its attestation key not leaked. Nothing is sent
anywhere but to that server, and the server contacts nobody to check it
([PRIVACY.md](PRIVACY.md) says what leaves the phone for it).

### Push notifications

Settings → Notifications has the switch **Push notifications** ("Through a UnifiedPush app on this
phone, so Matty need not stay connected."), **Change UnifiedPush app** once one is chosen (always
the list of those installed, never the phone's default), and one line for the server on screen,
which while push is on asks that server again each time Settings comes back (`push/PushLine.kt`;
`<app>` is the UnifiedPush app's name on the phone):

- "No UnifiedPush app is installed on this phone", with **What is UnifiedPush?** (unifiedpush.org);
  or, with push off while the chosen app is installed but takes no registration, "`<app>` isn't set
  up for UnifiedPush right now: open it first"
- "Your UnifiedPush app was removed", with **Set up again** when another one is installed, else
  **What is UnifiedPush?**. Only a chosen app that is uninstalled, or that is now signed by somebody
  else, counts as removed
- "`<app>` isn't set up for UnifiedPush right now: open it, then Set up again", with **Set up
  again**: it is installed, signed as when it was chosen, but takes no registration (a UnifiedPush
  app may switch that off while it needs setting up). **Set up again** opens that app when no other
  takes registrations. Matty keeps its registrations meanwhile and deletes nothing at the server;
  once the app takes registrations again, Matty registers them again, with the same tokens, at the
  next start, when Settings looks, or when Matty comes back to the front
- "`<app>` no longer has Matty's registration: Set up again", with **Set up again**: the app was
  taken as removed, and is installed and taking registrations again
- "Notifications are off for Matty"
- "This server doesn't offer push notifications: it needs Matty Plugin 4.1.0", or, while the
  server has push switched off, its own sentence ("Push notifications are switched off on this
  server." when it gives none)
- "`<app>` stopped delivering for Matty", with **Set up again**
- "This server can't be reached right now: Matty asks again when you come back here or the network
  changes"
- "Asking `<app>`…", "`<app>` is temporarily unavailable" (until a push or a new address comes),
  "Open `<app>` and finish setting it up", "`<app>` can't reach its push server: trying again when the
  network is back", "`<app>` did not register Matty"
- The server's own sentence when it will not take the address (a push server not on its list, a
  private address, one too long), or "This server doesn't accept the address your UnifiedPush app
  gave." when it gives none, with **Try again**, which sends it once more (say, once the server's
  admins have listed that push server); otherwise that address is not sent again until the
  UnifiedPush app gives another or push is set up again. When the server's app check refuses this
  Matty, its sentence shows the same way, with no button, and the address goes once the check
  passes
- "Your push server (`<host>`) refused this server's messages", with ". If it is ntfy, it may need
  to allow anonymous publishing to up* topics" when it looks like ntfy, and **Try again**. The
  server itself tries one message at most once an hour, and one that goes through makes it work
  again
- "This server can't reach your push server (`<host>`)", with why when the server says (": the
  server's address rules don't allow it", ": its certificate did not verify"), or, when the push
  server answers but not with a yes, "Your push server (`<host>`) doesn't take this server's
  messages: it answers, but not with a yes"; both with **Try again**. The server itself tries one
  message at most every ten minutes, and one that goes through makes it work again. For this line
  and the one above, Matty registers again by itself once: one `PUT`, the next time it asks the
  server how the registration stands (at a start, when Settings looks, or once a server that could
  not be reached answers again), for a registration the server says is refused or failing. A
  registration whose check message the server already found refused or failing when Matty asked
  about it (45 s after the `PUT`) has had that try. No other goes until the server answers that
  the address is confirmed or to be sent again, or a new address goes (the line and its **Try
  again** stay when the server lets that same address go)
- "Couldn't register with this server yet: trying again", with **Try again**, while the address has
  not reached the server: it goes again by itself while Matty runs (after the server's wait or 30 s,
  then twice more, the wait doubling, ten minutes at most), and at the next start, when the network
  comes back and when Matty comes back to the front
- "`<app>` has not delivered the check message: open it and make sure it is connected", with
  **Try again**: three `PUT`s, 45 s apart, and the server still waits for the `hello`. A `hello`
  that arrives later while the server still waits for it is confirmed as ever. With Matty Plugin
  4.1.1 a push server that answers 507 is sent the check message again for ten minutes, and one
  that took it keeps it for the phone, so opening `<app>` is usually enough; **Try again**, for
  after that, sends the address once more, and the line is
  "Waiting for the first push" again while its check message is waited for as the first was
- "Waiting for the first push", until the server says the address is confirmed. With Matty Plugin
  4.1.1, a push server that answers 507 because the phone's UnifiedPush app has not connected to
  it yet (as ntfy does when it limits by subscriber) leaves this line, then the one above, and
  push works by itself once that app connects within ten minutes
- "Working through `<app>` (`<push server's host>`)", with ", for 1 of your 2 servers" when there are
  several, and **Send a test**; then "Sent: the test notification should arrive in a moment."
  until the test arrives (as the notification "Push notifications are working") or for ten seconds.
  When the ten seconds pass without it, "The test notification has not arrived: open `<app>` and
  make sure it is connected" ("your UnifiedPush app" when it has no name) shows instead, until a
  test arrives, **Send a test** is pressed again, the line is no longer "Working through", or
  Settings is left; Matty then also asks the server once how the registration stands, without
  sending the address again by itself, so that one it has found refused or failing meanwhile shows
  its own line and **Try again**

While push works for the server on screen and **Stay connected in the background** is on, a line
under it says "Push notifications work for this server, so staying connected is no longer needed
for its notifications." with **Switch it off**; nothing is switched for the person. **Notify for
every DM** then says "Pushes follow your notification preferences on this server; this switch is
for the live connection".

Notifications go in the channel "Messages" ("Messages your server notifies you about"). With
more than one server saved, each says under it whose it is: the server's name, or "@username ·
name" when another saved server has the same name ("@username · host", its address's host, when
that server's account has the same username too). A notification Reply was sent from goes once
the reply is posted, unless a message came to that conversation while the reply was being sent:
then it stays, with the reply after its lines. Mark as read takes a notification away once the
server has it, and leaves it when a message came meanwhile. One whose action could not be sent
stays, with Matty's line "Couldn't send" under its messages. Mark as read on a thread's
notification reads the thread up to the newest message it showed. The notice "Signed out of
`<server>`" (with "(@username)" after it when another saved server has the same name) goes when
you sign in to that server again, and a second sign-out from it replaces the first notice.
