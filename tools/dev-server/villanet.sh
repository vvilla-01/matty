#!/usr/bin/env bash
# The Matty plugin on the local dev server, end to end, with nothing real behind it:
# builds the plugin from its own repository, installs it on the Mattermost that
# start.sh runs, and starts the plugin repo's stand-ins (build/fakebb) for
# everything the plugin talks to: Broadcast Box (real WebRTC playback of a looping
# test clip), Steam (the sign-in and the Web API) and a Mumble server. Then it
# points the plugin at them, links victor, alice, bob and carol, and connects
# each one's stand-in Steam account, so that streams, mic badges and "Playing"
# lines are all there to look at in the app. dave is left locked, to see what a
# locked reader is shown. Run it after start.sh and seed.py; run it again after
# changing the plugin.
#
#   VILLANET_REPO     the plugin's checkout   (default: "../Broadcast Box Plugin" beside Matty)
#   GO                the Go to build with    (default: go, else the newest toolchain in ~/go/pkg/mod)
#   MM_URL            the dev server          (default: http://localhost:8065)
#   VILLANET_BUNDLE   standin (default): the plugin built to ask the stand-in for Steam, for this
#                     machine only; release: the bundle that is released, which asks the real Steam
#                     and so shows no Steam here (everything else is the same)
#
# The stand-ins are steered while they run:
#   curl -X POST 'localhost:7788/fake/live?key=alice&on=false'           # off the air
#   curl -X POST 'localhost:7788/fake/live?key=alice&silent=true'        # listed, but sending nothing
#   curl -X POST 'localhost:7788/fake/codec?key=alice&codec=h264'        # its picture in H.264 (or vp8)
#   curl -X POST 'localhost:7788/fake/voice?name=alice'                  # joins voice (&muted=true, &on=false)
#   curl -X POST 'localhost:7788/fake/steam?who=alice&game=Factorio'     # in a game (game= for none, &state=away)
# A change in voice shows at once; one on Steam within a minute, which is how often the plugin asks.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="${VILLANET_REPO:-$HERE/../../../Broadcast Box Plugin}"
MM="${MM_URL:-http://localhost:8065}"
KIND="${VILLANET_BUNDLE:-standin}"
[ -f "$REPO/plugin.json" ] || { echo "No Matty plugin checkout at $REPO (set VILLANET_REPO)"; exit 1; }
[ "$KIND" = standin ] || [ "$KIND" = release ] || { echo "VILLANET_BUNDLE is standin or release"; exit 1; }

GO="${GO:-}"
if [ -z "$GO" ]; then
  if command -v go >/dev/null 2>&1; then GO=go
  else GO="$(ls -d "$HOME"/go/pkg/mod/golang.org/toolchain@*/bin/go 2>/dev/null | sort -V | tail -1)"; fi
fi
[ -n "$GO" ] || { echo "No Go toolchain found (set GO)"; exit 1; }
export GO GOTOOLCHAIN="${GOTOOLCHAIN:-local}"

PW="$(python3 -c "import ast,sys;t=ast.parse(open('$HERE/seed.py').read());print(next(n.value.value for n in t.body if isinstance(n,ast.Assign) and getattr(n.targets[0],'id','')=='PW'))")"
TOKEN="$(curl -sS -D - -o /dev/null -H 'Content-Type: application/json' -d "{\"login_id\":\"victor\",\"password\":\"$PW\"}" "$MM/api/v4/users/login" | awk 'tolower($1)=="token:"{print $2}' | tr -d '\r')"
[ -n "$TOKEN" ] || { echo "Couldn't sign in as victor on $MM (is start.sh running, and seed.py done?)"; exit 1; }
api() { curl -sS -H "Authorization: Bearer $TOKEN" -H 'X-Requested-With: XMLHttpRequest' "$@"; }

if [ "$KIND" = standin ]; then
  # The stand-in build takes a stand-in's word for Steam accounts, so it only starts on a
  # Mattermost whose Site URL is this machine. Better said here than found in the server's log.
  SITE="$(api "$MM/api/v4/config" | python3 -c 'import json,sys;print(json.load(sys.stdin)["ServiceSettings"]["SiteURL"])')"
  case "$SITE" in
    http://localhost|http://localhost:*|http://127.0.0.1|http://127.0.0.1:*|https://localhost|https://localhost:*) ;;
    *) echo "The dev server's Site URL is \"$SITE\". The stand-in build only starts where it is this machine:"
       echo "restart the dev server with start.sh's default (SITE_URL=http://localhost:8065), or use VILLANET_BUNDLE=release."; exit 1 ;;
  esac
fi

echo "Building the Matty plugin ($KIND)…"
if [ "$KIND" = standin ]; then
  ( cd "$REPO" && make -s standin-linux GO="$GO" >/dev/null )
  BUNDLE="$(ls "$REPO"/dist/standin/ceo.villa.matty-*-standin-linux-amd64.tar.gz | sort -V | tail -1)"
else
  ( cd "$REPO" && make -s dist-linux GO="$GO" >/dev/null )
  BUNDLE="$(ls "$REPO"/dist/ceo.villa.matty-*-linux-amd64.tar.gz | sort -V | tail -1)"
fi
( cd "$REPO" && make -s fakebb GO="$GO" )
# The test clips: make makes only those missing (a checkout from before the H.264 one gains just
# that one), and goes on to the others when one cannot be made. Without one, the stand-ins still
# run, and a stream in that codec has no picture (or, without the Ogg, no sound).
if ! ( cd "$REPO" && make -s -k fakebb-media ); then
  echo "Not every test clip could be made (ffmpeg with libvpx, libx264 and libopus makes them): streams in"
  echo "a codec whose clip is missing have no picture; victor's is H.264 (curl -X POST 'localhost:7788/fake/codec?key=victor&codec=vp8')."
fi

# Stand-ins left running from an older build are replaced (one from before Steam and voice knows
# nothing of either); the same build is left alone, with whatever it was steered to.
STARTED="${TMPDIR:-/tmp}/fakebb.sha256"
BUILT="$(sha256sum "$REPO/build/fakebb/fakebb" | cut -d' ' -f1)"
if curl -s -o /dev/null localhost:7788/api/status; then
  if [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:7788/fake/steam)" != 200 ] || { [ -f "$STARTED" ] && [ "$(cat "$STARTED")" != "$BUILT" ]; }; then
    echo "Stopping the stand-ins that were running (an older build)…"
    pkill -x fakebb || true
    sleep 1
  fi
fi
if ! curl -s -o /dev/null localhost:7788/api/status; then
  echo "Starting the stand-ins: Broadcast Box and Steam on :7788, Mumble on :64738…"
  # Detached from this script's own output: whatever reads that must not wait on the stand-ins.
  ( cd "$REPO" && exec nohup build/fakebb/fakebb -video build/fakebb/media/stream.ivf -h264 build/fakebb/media/stream.h264 -audio build/fakebb/media/stream.ogg ) \
    >"${TMPDIR:-/tmp}/fakebb.log" 2>&1 </dev/null &
  echo "$BUILT" >"$STARTED"
  sleep 1
fi

echo "Installing $(basename "$BUNDLE")…"
api -o /dev/null -X PUT -H 'Content-Type: application/json' -d '{"PluginSettings":{"EnableUploads":true}}' "$MM/api/v4/config/patch"
api -o /dev/null -F "plugin=@$BUNDLE" -F force=true "$MM/api/v4/plugins"
api -o /dev/null -X POST "$MM/api/v4/plugins/ceo.villa.matty/enable"

# Where everything is. The Steam key is made up: the stand-in takes any, and the release bundle,
# which would send it to the real Steam, is given none.
STEAM='"enablesteam":false'
[ "$KIND" = standin ] && STEAM='"enablesteam":true,"steamwebapikey":"standin-key-not-a-real-one"'
api -o /dev/null -X PUT -H 'Content-Type: application/json' -d '{"PluginSettings":{"Plugins":{"ceo.villa.matty":{
  "broadcastboxurl":"http://127.0.0.1:7788",
  "enablevoice":true,"mumbleserver":"127.0.0.1:64738","voicematchusernames":true,
  '"$STEAM"'}}}}' "$MM/api/v4/config/patch"

# The people: each linked with a token that is their own name (the stand-in Broadcast Box takes
# anything), and each connected to the stand-in Steam account of the same name, the way the app
# connects one: app-connect, the sign-in, app-finish. Nobody already linked or connected is
# asked again, so running this often never meets the plugin's limits.
MM="$MM" PW="$PW" KIND="$KIND" python3 - <<'PY'
import json, os, urllib.error, urllib.parse, urllib.request

MM, PW, KIND = os.environ["MM"], os.environ["PW"], os.environ["KIND"]
VN = MM + "/plugins/ceo.villa.matty/api/v1"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def call(method, url, token=None, body=None):
    """(status, headers, parsed body) of one request; a refusal is an answer, not an error."""
    req = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode())
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Requested-With", "XMLHttpRequest")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.build_opener(NoRedirect).open(req) as r:
            status, headers, raw = r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        status, headers, raw = e.code, e.headers, e.read()
    try:
        return status, headers, json.loads(raw) if raw else {}
    except ValueError:
        return status, headers, {}


def why(answer):
    return answer.get("error") or answer.get("message") or answer


for name in ["victor", "alice", "bob", "carol"]:
    status, headers, user = call("POST", MM + "/api/v4/users/login", body={"login_id": name, "password": PW})
    if status != 200:
        print(f"  {name}: couldn't sign in ({why(user)})")
        continue
    token = headers["Token"]

    _, _, access = call("GET", VN + "/access", token)
    if access.get("access") != "linked":
        status, _, answer = call("POST", VN + "/me/link", token, {"token": name})
        if status != 200:
            print(f"  {name}: not linked ({why(answer)})")
            continue

    if KIND != "standin":
        continue
    _, _, accounts = call("GET", VN + "/accounts", token)
    if (accounts.get("people", {}).get(user["id"]) or {}).get("steam"):
        continue

    status, _, start = call("POST", VN + "/steam/app-connect", token, {})
    if status != 200:
        print(f"  {name}: Steam not connected ({why(start)})")
        continue
    # The stand-in's sign-in vouches for the account named by as= and sends the browser back;
    # the address it sends it to is caught, as the app's sign-in window catches it, not loaded.
    _, back, _ = call("GET", start["url"] + "&as=" + urllib.parse.quote(name))
    query = urllib.parse.urlsplit(back.get("Location", "")).query
    status, _, answer = call("POST", VN + "/steam/app-finish", token, {"query": query})
    if status != 200:
        print(f"  {name}: Steam not connected ({why(answer)})")
PY

echo "The Matty plugin is installed and pointed at the stand-ins."
echo "  Live:   $(curl -s localhost:7788/api/status | python3 -c 'import json,sys;print(", ".join(s["streamKey"] for s in json.load(sys.stdin)))')"
echo "  Voice:  $(curl -s localhost:7788/fake/voice | python3 -c 'import json,sys;print(", ".join(p["name"]+(" (muted)" if p["muted"] or p["deafened"] else "") for p in json.load(sys.stdin)["people"]))')"
if [ "$KIND" = standin ]; then
  echo "  Steam:  $(curl -s localhost:7788/fake/steam | python3 -c 'import json,sys;print(", ".join(a["who"]+(" in "+a["game"] if a["game"] else "") for a in json.load(sys.stdin)["accounts"]))')"
fi
