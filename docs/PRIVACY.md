[← Back to Matty](../README.md)

# Security & privacy

**Encrypted, end to end of the session.** Release builds talk to Mattermost over HTTPS and
WSS only — sign-in, the session token, messages, files, images, video, and the WebRTC signalling
of live streams and voice channels (through Matty Plugin, on your server). There is no fallback to plain http: an
address typed as `http://` is refused, and a server that only answers on http can't be reached. This is enforced three times over: in the
app's transport policy (`data/net/Transport.kt`), in the HTTP client (TLS 1.2+ only, no
http↔https redirects, credentials refused on any unencrypted hop), and by the Android network
security config. Live streams and voice channels themselves are always DTLS-SRTP encrypted by WebRTC.

**Your session token goes to one place.** It's attached only to requests for the signed-in
server's exact origin (scheme, host and port), so a look-alike host such as
`chat.example.com.evil.net`, another port, or a link in a message can never receive it. API
calls never follow redirects, so a password can't be bounced to another host.

**Certificates.** Only the system's trusted certificate authorities are accepted (a CA installed
by a user, an MDM profile or malware can't intercept Matty), Certificate Transparency is
required, and certificate problems are reported as such — never as "check your connection".
Servers need a publicly trusted certificate (Let's Encrypt works).

**Sign-in.** Passwords and MFA codes live only in memory and are cleared once used; screenshots
and the recent-apps preview are blocked on the sign-in screens; fields are tagged so password
managers can fill and save them. GitLab single sign-on runs in a locked-down web view that only
navigates over HTTPS, refuses certificate errors and mixed content, takes the session cookie
only from your server's own origin, and wipes its cookie store immediately afterwards.

**What your server asks of the app.** Matty has no enterprise switches of its own, but it honours
two of Mattermost's own mobile settings (in the System Console; sent by licensed servers,
Mattermost Entry included). *Prevent screen capture*: while that server is on screen, screenshots,
screen recordings, casts and the app-switcher picture show black, dialogs and menus included.
*Biometric authentication*: Matty opens that server only after your fingerprint, face or the
phone's screen lock, at every start and after five minutes away from it (in the background or
with another server on screen), as Mattermost's own app does; nothing of the server shows behind the lock, and a phone with no screen
lock is asked to set one first. From the lock you can also switch to another signed-in server
(which then follows its own rules) or sign out of that one. What a server required is remembered with its session, so it
holds from the first frame of the next start, and only an answer from the server itself can lift
it. Settings shows a line for each one that applies. A server's Matty Plugin (4.4.0 or newer) may
also set the oldest Matty it takes (**Minimum Matty version**), which it reads from the version
Matty names in every request (its User-Agent, `Matty/0.1.32`, as always): this Matty, when it is
older than that, shows that server only as a page saying to update, with the same ways to another
server and to sign out, remembered in the same way, leaves any voice call there, and asks that
server's plugin nothing it would refuse. Matty 0.1.31 and older are only refused what they ask
the plugin for. That server's notifications still arrive, and Reply and Mark as read on
them still work: they are Mattermost's own, which the plugin does not refuse. Two-factor sign-in
codes work as always; a
server that requires two-factor authentication of an account that hasn't set it up gets a
sentence saying to turn it on in Mattermost on the web.

**Your session, on the device.** Tokens are sealed with an AES-256-GCM key in the Android
Keystore (in the StrongBox security chip where the phone has one); nothing is backed up or
transferred to another device. If the server revokes or expires the session, Matty notices,
wipes it, and asks you to sign in again. Signing out ends the session on the server (retrying
later if the server can't be reached) and removes cached pictures and files, web sign-in
cookies, drafts and message notifications from the phone. It also takes that server's push
registration away: from your UnifiedPush app at once, and from the server just before the session
is ended there.

**Push notifications (UnifiedPush).** Off until you switch them on (Settings → Notifications), and
only for servers whose Matty Plugin is 4.1.0 or newer with push switched on. When they are on, for
each signed-in server: the UnifiedPush app you chose on the phone (ntfy, say) is asked for an
address, and told the server's name, which it may show; that address, with a public key and a
secret made for that server alone, goes to your server, and to nobody else. The private half of
the key never leaves the phone and is sealed by the same Keystore key as your sign-in. Matty
remembers the UnifiedPush app you chose with its signing certificate: an app of that name signed
by somebody else is treated as removed, as one that is uninstalled is. When Mattermost decides to notify you, your server
encrypts the notification end to end (Web Push, RFC 8291) and sends it to that address; your
UnifiedPush app and its push server pass it on and see when and how big (every push is padded to
one of three sizes), never what it says, nor who it is from. ntfy also keeps each push it received,
still encrypted, on the phone for a month by default.
What a notification shows follows your server's own push-contents setting in Mattermost (full
message, sender and channel only, or nothing but "You've received a new message", in which case
Matty fetches the message from your server itself); the server's unlock and screen-capture rules
don't change notifications, as before. Showing one makes no connection to the server, except to
fetch the sender's picture (and, with that last setting, the message, its sender and its channel).
An app you chose that is still installed but not set up for UnifiedPush right now keeps Matty's
registrations meanwhile, and your server keeps the address until you switch push off or another
address replaces it. Switching push off takes each server's registration away from your UnifiedPush app at once
and from the server with one request; beyond that request, nothing of this is sent anywhere while
push is off. A server that can't be reached right then is told at Matty's next start, or when the
network comes back while Matty runs, and until then may go on sending encrypted pushes to the old
address.

**Notifications on the phone.** Whether or not push is on, so that a message shows only once,
Matty keeps the ids of the posts it showed in the last day (500 at most), never what they said;
with push on, also when each conversation was last read elsewhere (the last 256). With more than
one server saved, a message notification says under it which server it is from (the server's
name, and your username before it when two saved servers have the same name).

**A lost phone.** End its session in Mattermost (Profile › Security › View and Log Out of Active
Sessions). Your server reads a phone's session before every push, so from then on it sends that
phone nothing. Mattermost answers a session that has ended and one it could not read just then
alike, so the server reads it again about a minute after the first push it holds back for that
phone (within about a day if nothing is pushed to you meanwhile), and takes it as ended only if it
still cannot be read while other sessions it saw lately can be; a session that has expired is
taken as ended at once. While Mattermost cannot read sessions at all, nobody is taken as signed
out, and nothing is sent to a phone whose session cannot be read. Once the session is taken as
ended, the server sends the phone one last push saying so, which arrives if the phone is online
within the time its push server keeps a push (a day at most; ntfy keeps a push 12 hours by
default, and only for a phone whose ntfy was connected within the last day when its server limits
by subscriber). Matty then
takes that server's notifications off the phone at once and asks the server whether the session
is over, which needs the phone to reach the server. Only when Mattermost itself says so twice, a
second apart, does Matty sign out of that server and say so; what it kept of that server is wiped
at once, or, when Matty was woken only for that push, as Matty next starts. While another server
is in use, only what was that server's own goes at once (its notifications, its name colours, the
messages kept of it), and what all servers share (drafts, uploads, a pending share, cached pictures and files, web
sign-in cookies) is wiped as Matty next starts. When the server still answers for you (it had
only failed to read the session for a while), Matty stays signed in and registers again. With no
answer, Matty asks again when you come back to it or the network comes back, and at its next
start. Until that last push arrives, notifications already on the phone stay.

**Messages kept on the device.** The newest messages of each conversation are kept so it opens
without waiting (Settings → Chat switches this off). They are encrypted with AES-256-GCM under a
key that itself is sealed by the Android Keystore, live in the app's own cache directory, are
capped in size, and are wiped on sign-out, like the pictures. Those of a server whose session
ended go as Matty signs out of it, even while another server is in use.

**Requests the app didn't decide to make.** A picture, a video or a download is an address
somebody else chose. Those are loaded with the session token only (and only for your server's
exact origin), never with the header that marks a request as the app's own, so a post can't make
the app ask a server or one of its plugins for something on your behalf.

**Uploads.** Photos and videos are uploaded under a random name — 128 random bits as 32 hex
characters, the way MarkOut names its files — so a camera name like `PXL_20261110_221530.jpg`
never reveals when or on what a picture was taken.

**What a photo or video carries.** Before a picture or a video leaves the phone (attached from
the photo picker, the file picker or a share from another app, or made your profile picture or
profile banner), Matty removes what it says about you: where it was taken, when, on what
camera, the embedded thumbnail (which can show the uncropped original), XMP, IPTC and comments. What a file is comes
from its bytes, not its name. JPEG, PNG, WebP and GIF are cleaned without re-encoding, so the
picture is exactly the same; the only thing kept is which way up it is (and its colour profile).
An Ultra HDR photo goes as its ordinary picture, without its HDR gain map, and the hidden video
of a motion photo stays behind. HEIC, AVIF, DNG and any other picture the phone can read are
drawn again from their pixels as a JPEG (a PNG when they have see-through parts). MP4, MOV and
3GP videos are cleaned in place without re-encoding: their location, maker, XMP and dates are
blanked, in a recording made in fragments too. A picture or video that can't be cleaned, or that
the phone hasn't the free space to clean, isn't attached, and Matty says so. Other video
formats (WebM, MKV, AVI) and documents (a PDF, say) go as they are, with whatever they contain.

**The microphone (voice channels).** Matty asks for the microphone the first time you join a
voice channel unmuted or unmute in one, and never otherwise; refused, you stay in the channel
listening, shown as muted. It records only while you are in a voice channel and not muted or
deafened (nor held by a phone call, nor muted by the voice server): muting lets go of it, and
Android's microphone indicator goes out. While it records, what it hears goes, encrypted (DTLS-SRTP),
to Matty Plugin on your server, and only there; the plugin passes it on (with Matty Plugin 4.4.0,
only while the voice server holds you neither muted nor deafened), as it is, over TLS to the
voice (Mumble) server your server's admins set up, which plays it to the people in your channel.
The plugin never decodes it and keeps none of it; what the voice server does is up to its admins.
The voice server knows you as `<your username> (Matty)`, or `<your username>.matty` on a voice
server that refuses that name, in the channel you are in, and sees your server's address, never
your phone's. Muting, deafening and moving are told to your server. While
you are in voice a notification says so, with Mute and Leave, and swiping Matty away from the recent
apps leaves the channel.

**What a banner shows and what it sends.** You choose the part of a picture that shows as your
profile banner (a Matty Plugin feature). A still picture is cut to that part on the phone, so the
rest of it never leaves.
A GIF is sent whole, since cutting it would mean encoding it again, and the plugin keeps the part to
show beside it: every Matty draws only that part, but the whole GIF is on the server, where
anybody signed in to it could load it.

**Checking the app and the plugin.** How this copy of Matty is signed is read from Android on
the phone, and sent nowhere on its own. Whether your server's Matty Plugin is a signed release is
checked on the phone from what the server sends (`GET /api/v1/release`); nothing is sent for it.
When the server's admins have the app check on (it is on unless they switch it off), Matty proves
itself to that server, and to nobody else, once per session and app version: it asks the server
for a challenge, the phone's secure hardware makes a new key for it and a statement about it,
signed back to Google's attestation roots, and the key is deleted at once. What goes to the server
is the challenge, the address Matty reaches it at, and that statement (the key's certificate chain),
which says: that it answers this challenge for you at this server; which app asked
(`ceo.villa.matty`), its version, and the SHA-256 of the certificate it is signed with; whether the
key is in StrongBox or the trusted environment; whether the phone's bootloader is locked and its
system verified, with the digests of its boot key and of the system it booted; its Android version
and security patch levels; and, above it, the certificates that link it to Google's root. Beside it
goes what Matty reads of itself (its package, version, and its signing certificates' SHA-256s,
labelled as its own word). No serial number, IMEI, phone number or other identifier of the device
is asked for, and Android leaves them out unless an app asks. The server checks it without contacting anybody (its copies
of Google's revocation list and of GrapheneOS's keys ship with its release) and keeps, for that
session, for 30 days at most or until the session ends: whether it passed and why not, when, the app's
version, the security level and boot state, what the app said of itself, and, when it passed, the
certificate digest it passed with and, for a phone whose system is signed with a key of its own
(GrapheneOS's, say), that key's digest. Its admins can see that list; the chain itself is not kept.

**Installing a plugin update.** Only for a system admin of a server, and only when they tap
**Install** beside "Matty Plugin 4.5.0 is available" and say yes: Matty asks that server's Matty
Plugin for the release's bundle (which the plugin downloaded from GitHub and checked), keeps it in
its cache folder only until it is handed on, and uploads exactly those bytes to that same server's
plugin upload, with your session. Nothing goes anywhere else. The file is removed at the end
whatever happens, and one left by an app that was stopped in the middle is removed when the app
next starts.

**Everything else.** Matty connects to the servers you add, plus Giphy when you open the GIF
picker, GitHub in a browser tab when you tap **Get the update** or a line saying a newer Matty
Plugin is out, and to the sites that pictures in messages live on: a picture somebody posts or links to
from another site is loaded from that site, which shows it your address and when you looked
(never your sign-in). If your server's image proxy is on (System Console › Environment › Image
Proxy), those pictures come through your server instead, and the other sites see only it; we
recommend turning it on. With push notifications on, the UnifiedPush app you chose, and the push server it uses, carry them
as above. No analytics, no crash reporting, no third-party SDKs that phone home. Links in messages
open only if they're web or mail links. Notification actions are bound to the server they came
from.
