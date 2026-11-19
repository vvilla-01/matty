# Local Mattermost for Matty development

```bash
tools/dev-server/start.sh            # first run downloads ~500 MB into ~/.cache/matty-dev
python3 tools/dev-server/seed.py     # users, two teams, channels, threads, reactions, DMs, a GIF
```

The dev server speaks plain http, so use a **debug build** of Matty (release builds are
HTTPS-only). On the emulator, forward the port and sign in to `localhost:8065`:

```bash
adb reverse tcp:8065 tcp:8065
```

(Some emulator images can't reach the host's `10.0.2.2` from apps; `adb reverse` always works, and
`http://localhost:8065` is the server's Site URL.) The server listens on this machine only
(`127.0.0.1:8065`); `LISTEN_ADDRESS=:8065 tools/dev-server/start.sh` opens it to the network, for
a phone on the same Wi-Fi, and then anybody there can sign in with the seed's accounts. The seed script's test
accounts (`victor`, `alice`, `bob`, `carol`, `dave`) share the password defined as `PW` at the
top of `seed.py`; `victor` is the system admin. These are local test accounts only — never reuse
them on a real server.

Linux x86_64 only (the PostgreSQL binaries come from the zonky embedded-postgres builds).

## Matty Plugin, with nothing real behind it

With the plugin's repository (Matty Plugin, id `ceo.villa.matty`) checked out beside Matty's (`../Broadcast Box Plugin`, or
`VILLANET_REPO`):

```bash
tools/dev-server/villanet.sh
```

builds the plugin, installs it on the dev server and starts the plugin repository's stand-ins for
everything the plugin talks to: Broadcast Box on port 7788 (real WebRTC playback of a looping test
clip, so the emulator can play streams), Steam on the same port, and a Mumble server on 64738. It
points the plugin at them, links `victor`, `alice`, `bob` and `carol` (each with their own name as
the token) and connects each one's stand-in Steam account, the way the app would. So without
touching the app's source there are streams, people in voice (mic badges) and people in games
("Playing" lines) to look at. `dave` is left locked, for seeing what a locked reader is shown. Run
it again after changing the plugin.

The stand-ins are steered while they run:

```bash
curl -X POST 'localhost:7788/fake/live?key=alice&on=false'           # off the air
curl -X POST 'localhost:7788/fake/live?key=alice&silent=true'        # listed, but sending nothing (a dead broadcast)
curl -X POST 'localhost:7788/fake/live?key=alice&paused=true'        # paused: "Back soon"
curl -X POST 'localhost:7788/fake/codec?key=alice&codec=h264'        # its picture in H.264; codec=vp8 back

curl -X POST 'localhost:7788/fake/voice?name=alice'                  # joins voice
curl -X POST 'localhost:7788/fake/voice?name=alice&muted=true'       # mutes (deafened=true: deafened)
curl -X POST 'localhost:7788/fake/voice?name=alice&on=false'         # leaves

curl -X POST 'localhost:7788/fake/steam?who=alice&game=Factorio'     # in a game; game= for none
curl -X POST 'localhost:7788/fake/steam?who=alice&state=away'        # online | busy | away | snooze | offline
curl -X POST 'localhost:7788/fake/steam?who=alice&private=true'      # a profile that is not public
curl -X POST 'localhost:7788/fake/steam?api=429'                     # Steam's Web API answers badly, until api=200
curl 'localhost:7788/fake/voice'; curl 'localhost:7788/fake/steam'   # what each holds
```

Each stream's picture is in one codec, as on a real Broadcast Box: `victor`'s is H.264 and every
other stream's VP8, which the app's playback checks have always used (a real Broadcast Box carries
no VP8; it is here because an emulator always plays it). H.264 is there for clips: the plugin's clip
recorder records H.264 only (what OBS sends by WHIP), so clips can be cut live from `victor`'s
stream and from any stream switched to it. A viewer who did not offer the stream's codec gets its
sound and no picture (what Broadcast Box's source was read to do, not tried against it), and the
stand-in's log (`$TMPDIR/fakebb.log`) says so: `no picture for viewer … the stream is in h264 and
the offer had VP8`. The codec changes while viewers stay connected; the H.264 clip is made beside
the VP8 one the first time the script runs.

A change in voice shows at once. A change on Steam shows within a minute, which is how often
the plugin asks Steam. A Mumble name that is somebody's username is that person; any other is a guest.

Steam's addresses are fixed in the plugin, so the plugin the script installs by default is a
**stand-in build** (`make standin-linux` there: `4.0.0+standin` in the plugin list, from
`dist/standin/ceo.villa.matty-<version>-standin-linux-amd64.tar.gz`), which asks this
machine instead of Steam and refuses to start anywhere the Site URL is not this machine. That is why
`start.sh` serves the dev server as `http://localhost:8065`. It is never the plugin to hand to a real
server. To run the bundle that is released instead (everything but Steam works the same):

```bash
VILLANET_BUNDLE=release tools/dev-server/villanet.sh
```

which builds `dist/ceo.villa.matty-<version>-linux-amd64.tar.gz`.

The plugin starts with its default name, Matty; give it another under System Console › Plugins ›
Matty › Name and artwork to see the app follow (the Plugins screen, the panel's title, the
sentences that name it; lines about the software itself still say "Matty Plugin"). Both bundles are built here and not signed, so the app's line for the plugin reads
"Matty Plugin 4.0.0 · not a signed release". The plugin's app check is on by default (*Check, and
show the result*), and a debug build on the emulator does not pass it: the line says why. To see
it pass, put the debug build's signing certificate (`apksigner verify --print-certs` on
`app-debug.apk` prints its SHA-256) under the plugin's **More signing certificates** on the
stand-in build, which alone also takes what an emulator and a debug build (`ceo.villa.matty.debug`)
produce, and then says "(development build)" beside verified.

After a change to what the plugin answers, bring its contract fixtures into the app's tests and
make them pass:

```bash
tools/sync-villanet-contract.sh
./gradlew :app:testDebugUnitTest --tests '*VillaNetContractTest*'
```

## Push notifications, with a stand-in distributor

UnifiedPush needs a distributor on the phone. `tools/dev-server/distributor` is a stand-in for one,
for the emulator: a development-only app (platform APIs only, package `dev.standin.distributor`)
that speaks the distributor's half of the UnifiedPush protocol (AND_3.1). It hands out endpoints at
the plugin repository's stand-in push server, which `villanet.sh` runs on port 7788
(`http://localhost:7788/push/<id>`), tells that server which VAPID key each registration was made
with, long-polls it for each endpoint and hands what comes to the app. For each message it binds to
the app's raise-to-foreground service (with `BIND_AUTO_CREATE` only), sends the message once that
service has connected, and unbinds five seconds after it bound, as ntfy does, however late the
connection came; it drops the message when no connection comes within those five seconds or the
binding is null; `STATE` counts what was dropped. It talks plain http to `localhost` and nothing
else.

It is part of the Gradle build only when asked for; no task the docs give builds it:

```bash
./gradlew --offline -PdevDistributor=true :devdistributor:assembleDebug
./gradlew --offline -PdevDistributor=true :devdistributor:testFirstDebugUnitTest   # its own tests
```

That makes two copies that install side by side, so choosing between distributors and moving from
one to another can be tried: `tools/dev-server/distributor/build/outputs/apk/first/debug/devdistributor-first-debug.apk`
("Stand-in distributor", `dev.standin.distributor`) and `…/second/debug/devdistributor-second-debug.apk`
("Stand-in distributor 2", `dev.standin.distributor.two`).

```bash
adb -s emulator-5554 install -r tools/dev-server/distributor/build/outputs/apk/first/debug/devdistributor-first-debug.apk
adb -s emulator-5554 reverse tcp:7788 tcp:7788      # the stand-in push server, as localhost:7788 on the phone
adb -s emulator-5554 shell dumpsys deviceidle whitelist +dev.standin.distributor
```

Open "Stand-in distributor" once: that starts its polling service (a foreground service with a
silent notification) and shows what it holds. Android lets it start that service from the
background only while it is exempt from battery optimisation, which the `deviceidle` line does; it
logs a line under the tag `StandInDistributor` when the service could not start. Its log never
holds a token, an endpoint or a message.

On the server, switch the plugin's push notifications on, list `localhost:7788` among its push
servers on your own network, and switch Mattermost's own push notifications on, as the plugin's
manual says. Then, in a debug build of Matty, switch on Settings › Notifications › Push
notifications.

It is steered with broadcasts from the shell (`android.permission.DUMP`, which the shell holds,
is needed to reach it). Each one answers with a line, which `am` prints as its `data`:

```bash
S='-n dev.standin.distributor/.SteerReceiver -a dev.standin.distributor'
adb shell am broadcast $S.STATE                                     # what it holds (endpoint ids, never a token)
adb shell am broadcast $S.FAIL --es reason NETWORK --ei times 2     # the next two REGISTERs fail (INTERNAL_ERROR without reason; reason none: no reason given)
adb shell am broadcast $S.UNREGISTER_ALL                            # every registration dropped: a plain UNREGISTERED
adb shell am broadcast $S.UNREGISTER_ALL --es to dev.standin.distributor.two   # the same, naming the other copy to move to (as newDistributor only, as real distributors send it)
adb shell am broadcast $S.NEW_ENDPOINTS                             # every registration moves to a new endpoint
adb shell am broadcast $S.UNAVAILABLE                               # TEMP_UNAVAILABLE (--es fallback <package> names one); nothing is delivered
adb shell am broadcast $S.AVAILABLE                                 # back: NEW_ENDPOINT to every app, and what was held is delivered
adb shell am broadcast $S.LINK --ez cancel true                     # its link screen answers as if cancelled (false: as itself)
adb shell am broadcast $S.SETUP --ez on false                       # not set up: see below
adb shell am broadcast $S.SETUP --ez on true                        # set up again: its receiver and link activity are back on
```

Not set up, it behaves as ntfy does with its UnifiedPush switch off: the next REGISTER is answered
`REGISTRATION_FAILED` with `ACTION_REQUIRED`, and then its REGISTER receiver and its link activity
are switched off, so apps no longer find it among the installed distributors. What it already
delivers to keeps coming. Once the receiver is off, Matty keeps its registrations and tokens,
deletes nothing at the server and holds back each REGISTER it would send; Settings says "Stand-in
distributor isn't set up for UnifiedPush right now: open it, then Set up again" (with push off,
"Stand-in distributor isn't set up for UnifiedPush right now: open it first"), and its **Set up
again** opens the stand-in when no other distributor takes REGISTER. After `SETUP --ez on true`, Matty REGISTERs what it kept again, with
the same tokens, at its next start, when Settings looks, or when Matty comes back to the front. A
copy that is uninstalled is taken as removed instead.

For the second copy the component is `dev.standin.distributor.two/dev.standin.distributor.SteerReceiver`
(the action names are the same). A registration whose token another app holds is refused with
`INTERNAL_ERROR`, and one with an extra the protocol does not allow is ignored, as real
distributors do; an app that did not share its identity is still served, and `STATE` says how it
was known (`shared`, `pi` or `application`).

The stand-in push server is steered as its own source says (`build/fakebb/standin/push.go` in the
plugin's repository), for one endpoint (`id=`, from `STATE`) or for all:

```bash
curl -X POST 'localhost:7788/fake/push?status=410'                  # the next message is answered 410
curl -X POST 'localhost:7788/fake/push?status=429&times=3&retry=30' # three answered 429, Retry-After: 30
curl -X POST 'localhost:7788/fake/push?stall=60'                    # a tarpit; stall=0 to stop
curl -X POST 'localhost:7788/fake/push?mode=ntfy'                   # as ntfy; mode=rfc is the usual
curl 'localhost:7788/fake/push/log'                                 # what was sent, never what it said
```

Remove it with `adb uninstall dev.standin.distributor` (and `dev.standin.distributor.two`), which
is also how a removed distributor is tried.

## What a licensed server requires

The dev server has no licence, so it never sends the settings Mattermost keeps for licensed
servers (Entry and Enterprise), `MobilePreventScreenCapture` and `MobileEnableBiometrics`, even
when they are switched on in its config. To see Matty obey them, put the forwarder in front of it
(it changes only the answer to `GET /api/v4/config/client`; its first lines say the rest):

```bash
echo '{"MobilePreventScreenCapture": "true", "MobileEnableBiometrics": "true"}' > /tmp/policy-tap.json
python3 -I tools/dev-server/policy-tap.py /tmp/policy-tap.json
adb -s emulator-5554 reverse tcp:8065 tcp:8066     # the app now reaches the server through it
adb -s emulator-5554 reverse tcp:8065 tcp:8065     # afterwards: straight to the server again
```

The unlock needs a screen lock on the emulator: `adb shell locksettings set-pin 1234`, and
`adb shell locksettings clear --old 1234` when done.

## The README's pictures

The pictures in `docs/screenshots/` were taken around one channel, "Friday night" in the Game Night team: a short conversation
between the seed accounts, with a picture, a few reactions and a mention. `showcase.py` makes it (or
makes it again) as those accounts, so the pictures can be taken again after a change. With `villanet.sh`'s
stand-ins running, alice and victor are live.

```bash
python3 -I tools/dev-server/showcase.py            # makes the channel
python3 -I tools/dev-server/showcase.py --remove   # takes it away again (deleted messages, archived)
```
