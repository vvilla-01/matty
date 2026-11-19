#!/usr/bin/env python3
"""A short, natural conversation for the README's pictures, on the local dev server only.

Usage: python3 -I tools/dev-server/showcase.py [--remove] [BASE]   (BASE defaults to http://localhost:8065)

Makes (or remakes) the channel "Friday night" in the Game Night team of the seed (run seed.py first):
a dozen messages between the seed people about a game night, a picture, a few reactions, a mention of
victor, and alice saying she is live (she is, while tools/dev-server/villanet.sh's stand-ins run). It
posts as those accounts, with the password defined as PW in seed.py, and marks the channel read for
victor, so a phone signed in as victor opens it with nothing unread. --remove takes the channel away
again: its messages are deleted and it is archived (the dev server does not allow deleting channels for
good), and the next run brings the same channel back. The picture is drawn by this script, so nothing
is fetched from anywhere.
"""
import ast, json, math, os, struct, sys, time, urllib.error, urllib.request, uuid, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
REMOVE = "--remove" in sys.argv[1:]
BASE = ARGS[0] if ARGS else "http://localhost:8065"
TEAM, NAME, DISPLAY = "game-night", "friday-night", "Friday night"
PURPOSE = "At Carol's"


def seed_password():
    tree = ast.parse(open(os.path.join(HERE, "seed.py")).read())
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", "") == "PW" for t in node.targets):
            return ast.literal_eval(node.value)
    sys.exit("no PW in seed.py")


PW = seed_password()


def call(method, path, body=None, token=None, data=None, ctype="application/json"):
    req = urllib.request.Request(BASE + "/api/v4" + path, method=method)
    req.add_header("X-Requested-With", "XMLHttpRequest")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if body is not None:
        data = json.dumps(body).encode()
    if data is not None:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, data) as r:
            payload = r.read()
            return (json.loads(payload) if payload else {}), r.headers
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} -> {e.code}: {e.read().decode()[:300]}")


def login(user):
    data, headers = call("POST", "/users/login", {"login_id": user, "password": PW})
    return data["id"], headers["Token"]


people = {u: login(u) for u in ("victor", "alice", "bob", "carol", "dave")}
uid = {u: v[0] for u, v in people.items()}
tok = {u: v[1] for u, v in people.items()}
admin = tok["victor"]
team, _ = call("GET", f"/teams/name/{TEAM}", None, admin)


def find_channel():
    try:
        c, _ = call("GET", f"/teams/{team['id']}/channels/name/{NAME}?include_deleted=true", None, admin)
        return c
    except RuntimeError as e:
        if "404" in str(e):
            return None
        raise


def clear(channel):
    page = 0
    while True:
        got, _ = call("GET", f"/channels/{channel['id']}/posts?per_page=200&page={page}", None, admin)
        ids = got.get("order", [])
        if not ids:
            return
        for pid in ids:
            try:
                call("DELETE", f"/posts/{pid}", None, admin)
            except RuntimeError:
                pass


channel = find_channel()
if REMOVE:
    if channel and not channel.get("delete_at"):
        clear(channel)
        call("DELETE", f"/channels/{channel['id']}", None, admin)
    print("removed" if channel else "nothing to remove")
    sys.exit(0)

if channel is None:
    channel, _ = call("POST", "/channels", {"team_id": team["id"], "name": NAME, "display_name": DISPLAY,
                                            "purpose": PURPOSE, "type": "O"}, admin)
else:
    if channel.get("delete_at"):
        call("POST", f"/channels/{channel['id']}/restore", None, admin)
    clear(channel)
    call("PUT", f"/channels/{channel['id']}/patch", {"display_name": DISPLAY, "purpose": PURPOSE}, admin)
for u in uid:
    try:
        call("POST", f"/channels/{channel['id']}/members", {"user_id": uid[u]}, admin)
    except RuntimeError:
        pass


def sunset(w=1200, h=675):
    """An evening over hills, as a PNG: a sky gradient, a low sun with a glow, four ridges in haze."""
    def mix(a, b, t):
        return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))
    sky = [(0.0, (34, 42, 84)), (0.35, (104, 74, 128)), (0.62, (226, 132, 112)), (0.78, (247, 196, 128))]
    def sky_at(y):
        v = y / h
        for (p0, c0), (p1, c1) in zip(sky, sky[1:]):
            if v <= p1:
                return mix(c0, c1, max(0.0, (v - p0) / (p1 - p0)))
        return sky[-1][1]
    sun_x, sun_y, sun_r = 0.66 * w, 0.66 * h, 0.045 * w
    layers = [  # base height, waves (amplitude, frequency, phase), colour
        (0.66, [(18, 2.1, 0.4), (9, 5.3, 1.9), (4, 11.0, 0.3)], (176, 108, 130)),
        (0.72, [(26, 1.6, 2.2), (11, 4.1, 0.7), (5, 9.7, 2.5)], (122, 72, 108)),
        (0.80, [(30, 1.2, 4.0), (12, 3.3, 1.1), (6, 8.2, 0.9)], (70, 42, 78)),
        (0.90, [(34, 0.9, 1.3), (14, 2.7, 3.1), (5, 7.5, 1.7)], (30, 22, 44)),
    ]
    ridge = [[l[0] * h + sum(a * math.sin(f * x / w * math.tau + p) for a, f, p in l[1]) for x in range(w)]
             for l in layers]
    rows = []
    for y in range(h):
        base = sky_at(y)
        row = bytearray(b"\x00")
        for x in range(w):
            d = math.hypot(x - sun_x, y - sun_y)
            c = base
            if d < sun_r:
                c = (255, 236, 196)
            else:
                g = math.exp(-(d - sun_r) / (0.16 * w)) * 0.55
                c = mix(c, (255, 214, 160), g)
            for i, layer in enumerate(layers):
                if y >= ridge[i][x]:
                    depth = min(1.0, (y - ridge[i][x]) / (0.25 * h))
                    c = mix(layer[2], mix(layer[2], (20, 16, 32), 0.5), depth)
            row += bytes(int(max(0, min(255, v))) for v in c)
        rows.append(bytes(row))
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b""))


def upload(user, name, body):
    boundary = uuid.uuid4().hex
    data = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"channel_id\"\r\n\r\n{channel['id']}\r\n"
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; filename=\"{name}\"\r\n"
            f"Content-Type: image/png\r\n\r\n").encode() + body + f"\r\n--{boundary}--\r\n".encode()
    got, _ = call("POST", "/files", None, tok[user], data, f"multipart/form-data; boundary={boundary}")
    return got["file_infos"][0]["id"]


def post(user, message, files=None):
    body = {"channel_id": channel["id"], "message": message}
    if files:
        body["file_ids"] = files
    p, _ = call("POST", "/posts", body, tok[user])
    time.sleep(0.3)
    return p


def react(user, p, emoji):
    call("POST", "/reactions", {"user_id": uid[user], "post_id": p["id"], "emoji_name": emoji}, tok[user])


first = post("carol", "Game night at mine on Friday? Doors open at 8.")
post("bob", "In. I'll bring Wingspan :bird:")
post("dave", "Count me in. What should I bring?")
post("carol", "Just yourself, @dave. Maybe some ice.")
view = post("carol", "The view, if you come early", [upload("carol", "balcony.png", sunset())])
post("bob", "Okay, I'm coming early")
pizza = post("victor", "I'll make pizza. Any toppings to avoid?")
post("alice", "No olives, please :pray:")
olives = post("victor", "Noted. An olive-free evening.")
post("alice", "Live now, warming up for Friday. Come and say hi")
post("dave", "Watching :popcorn:")
post("carol", "@victor could you bring the folding chairs too?")
for who in ("bob", "alice", "dave"):
    react(who, first, "+1")
for who in ("alice", "bob"):
    react(who, view, "heart_eyes")
react("alice", olives, "joy")
react("carol", pizza, "pizza")
call("POST", f"/channels/members/{uid['victor']}/view", {"channel_id": channel["id"]}, admin)
print(f"made ~{NAME} in {TEAM}")
