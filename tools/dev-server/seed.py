#!/usr/bin/env python3
"""Seed a local Mattermost dev server with users, teams, channels and chatter for Matty testing."""
import json, sys, time, urllib.request, urllib.error

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8065"
PW = "Matty-dev-pass-1"


def call(method, path, body=None, token=None, raw=False):
    req = urllib.request.Request(BASE + "/api/v4" + path, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Requested-With", "XMLHttpRequest")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data) as r:
            payload = r.read()
            if raw:
                return r
            return (json.loads(payload) if payload else {}), r.headers
    except urllib.error.HTTPError as e:
        msg = e.read().decode()
        raise RuntimeError(f"{method} {path} -> {e.code}: {msg}")


def login(user):
    data, headers = call("POST", "/users/login", {"login_id": user, "password": PW})
    return data, headers["Token"]


def ensure_user(username, first, last, position, nickname=""):
    try:
        call("POST", "/users", {"username": username, "email": f"{username}@example.test", "password": PW,
                                "first_name": first, "last_name": last, "position": position, "nickname": nickname})
    except RuntimeError as e:
        if "already" not in str(e) and "exists" not in str(e):
            raise
    return login(username)


me, admin = ensure_user("victor", "Vic", "Admin", "Runs the place", "")
users = {"victor": (me, admin)}
for u, f, l, p in [("alice", "Alice", "Nguyen", "Speedrunner"), ("bob", "Bob", "Okafor", "Clips curator"),
                   ("carol", "Carol", "Diaz", "Stream mod"), ("dave", "Dave", "Kim", "Lurker")]:
    users[u] = ensure_user(u, f, l, p)

tok = {u: t for u, (d, t) in users.items()}
uid = {u: d["id"] for u, (d, t) in users.items()}


def ensure_team(name, display):
    try:
        t, _ = call("POST", "/teams", {"name": name, "display_name": display, "type": "O"}, admin)
    except RuntimeError:
        t, _ = call("GET", f"/teams/name/{name}", None, admin)
    for u in uid:
        try:
            call("POST", f"/teams/{t['id']}/members", {"team_id": t["id"], "user_id": uid[u]}, admin)
        except RuntimeError:
            pass
    return t


crew = ensure_team("villanet", "VillaNET Crew")
games = ensure_team("game-night", "Game Night")


def ensure_channel(team, name, display, purpose="", header="", ctype="O"):
    try:
        c, _ = call("POST", "/channels", {"team_id": team["id"], "name": name, "display_name": display,
                                          "purpose": purpose, "header": header, "type": ctype}, admin)
    except RuntimeError:
        c, _ = call("GET", f"/teams/{team['id']}/channels/name/{name}", None, admin)
    for u in uid:
        try:
            call("POST", f"/channels/{c['id']}/members", {"user_id": uid[u]}, admin)
        except RuntimeError:
            pass
    return c


town = ensure_channel(crew, "town-square", "Town Square")
offtopic = ensure_channel(crew, "off-topic", "Off-Topic")
clips = ensure_channel(crew, "clips", "clips", "Best moments from the streams", "Post your clips here :clapper:")
dev = ensure_channel(crew, "matty-dev", "matty-dev", "Building Matty", "**Matty** — a social-first Mattermost client")
mods = ensure_channel(crew, "mods", "mods", "Private mod chat", "", "P")
gtown = ensure_channel(games, "town-square", "Town Square")
lfg = ensure_channel(games, "lfg", "looking-for-group", "Find a squad")


def post(user, channel, message, root_id="", props=None):
    body = {"channel_id": channel["id"], "message": message, "root_id": root_id}
    if props:
        body["props"] = props
    p, _ = call("POST", "/posts", body, tok[user])
    time.sleep(0.05)
    return p


def react(user, p, emoji):
    call("POST", "/reactions", {"user_id": uid[user], "post_id": p["id"], "emoji_name": emoji}, tok[user])


existing, _ = call("GET", f"/channels/{town['id']}/posts?per_page=60", None, admin)
if any("gm everyone" in p.get("message", "") for p in existing.get("posts", {}).values()):
    print("already seeded")
    sys.exit(0)

p1 = post("alice", town, "gm everyone :wave: who's streaming tonight?")
react("bob", p1, "wave"); react("carol", p1, "wave"); react("victor", p1, "heart")
p2 = post("bob", town, "I'm going live around **9pm** with the new map. Bring snacks :popcorn:")
react("alice", p2, "fire"); react("dave", p2, "fire"); react("victor", p2, "+1")
post("carol", town, "count me in! @bob save me a spot")
post("carol", town, "also — reminder that ~mods meeting is tomorrow")
p3 = post("victor", town, "Matty build is coming along. Here's the plan:\n\n1. Discord-style layout\n2. GIFs that actually work on mobile\n3. Native VillaNET panel\n\n> social first, enterprise never")
react("alice", p3, "rocket"); react("bob", p3, "rocket"); react("carol", p3, "100"); react("dave", p3, "eyes")
r1 = post("alice", town, "the GIF thing is huge honestly", root_id=p3["id"])
post("bob", town, "finally :tada:", root_id=p3["id"])
post("victor", town, "@alice yep, it uses the same Giphy setup as the desktop app", root_id=p3["id"])
post("dave", town, "![party parrot](https://media.giphy.com/media/l0MYt5jPR6QX5pnqM/giphy.gif)")
post("alice", town, "lol")
post("bob", town, "```kotlin\nfun main() = println(\"hello from Matty\")\n```")
post("carol", town, "Check this out https://mattermost.com")

post("bob", clips, "Clip from last night: the triple kill :exploding_head:")
post("alice", clips, "insane. how did you even see him", )
post("victor", dev, "Use this channel for Matty feedback. Bugs → `/matty bug`, ideas → reply in thread.")
post("victor", dev, "Interactive message test", props={"attachments": [{
    "fallback": "Poll", "color": "#5865F2", "pretext": "Quick poll", "title": "What should Matty add next?",
    "text": "Pick one — results are anonymous.",
    "fields": [{"title": "Voting ends", "value": "Friday", "short": True}, {"title": "Votes", "value": "12", "short": True}],
    "footer": "Polls plugin"}]})
post("carol", mods, "keep an eye on the clips channel tonight")
post("alice", lfg, "anyone up for ranked? need 2 more")
post("dave", lfg, "I'm in :video_game:")
post("bob", gtown, "Game night is Friday :partying_face:")

# DMs
dm, _ = call("POST", "/channels/direct", [uid["victor"], uid["alice"]], tok["victor"])
p = post("alice", dm, "hey! did you see my clip? :eyes:")
post("victor", dm, "yes! that was wild")
post("alice", dm, "Want to co-stream Saturday?")
gm, _ = call("POST", "/channels/group", [uid["victor"], uid["bob"], uid["carol"]], tok["victor"])
post("bob", gm, "group chat for the stream schedule")
post("carol", gm, "I can do Thu/Sat")

# statuses and custom statuses
call("PUT", f"/users/{uid['alice']}/status", {"user_id": uid["alice"], "status": "online"}, tok["alice"])
call("PUT", f"/users/{uid['bob']}/status", {"user_id": uid["bob"], "status": "dnd"}, tok["bob"])
call("PUT", f"/users/{uid['carol']}/status", {"user_id": uid["carol"], "status": "away"}, tok["carol"])
call("PUT", "/users/me/status/custom", {"emoji": "video_game", "text": "Grinding ranked"}, tok["alice"])
call("PUT", "/users/me/status/custom", {"emoji": "movie_camera", "text": "Live at 9pm"}, tok["bob"])
print("seeded", json.dumps(uid))
