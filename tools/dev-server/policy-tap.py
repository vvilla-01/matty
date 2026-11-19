# Usage: python3 -I policy-tap.py [RULES.json] [PORT]   (defaults: policy-tap.json beside this file, 8066 -> 127.0.0.1:8065)
#        then: adb -s emulator-5554 reverse tcp:8065 tcp:8066 ; afterwards: adb -s emulator-5554 reverse tcp:8065 tcp:8065
#
# A forwarder in front of the local Mattermost that changes only the answer to GET /api/v4/config/client:
# every "Key": "value" pair in RULES.json is set in it (e.g. {"MobilePreventScreenCapture": "true",
# "MobileEnableBiometrics": "true"}), as a licensed server (Mattermost Entry) would send them. The file is
# read again on every request, so it can be edited while the app runs. One extra rule for testing the
# two-factor sentence: "_mfa_required": true answers GET /api/v4/users/me/teams with the 403 a licensed
# server sends to an account without two-factor authentication. Everything else, WebSockets included,
# passes through untouched. Each request is logged to stdout with its status.
import asyncio, json, os, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
RULES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, 'policy-tap.json')
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8066
UP = ('127.0.0.1', 8065)

def rules():
    try:
        with open(RULES) as f: return json.load(f)
    except FileNotFoundError: return {}

def log(*a): print(time.strftime('%H:%M:%S'), *a, flush=True)

async def read_head(r):
    head = await r.readuntil(b'\r\n\r\n')
    lines = head.decode('latin-1').split('\r\n')
    hdrs = [tuple(x.split(':', 1)) for x in lines[1:] if ':' in x]
    return lines[0], [(k.strip(), v.strip()) for k, v in hdrs]

def get(h, name):
    for k, v in h:
        if k.lower() == name: return v
    return None

async def read_body(r, h, is_resp, status=200, method='GET'):
    if is_resp and (status in (101, 204, 304) or 100 <= status < 200 or method == 'HEAD'): return b''
    if (get(h, 'transfer-encoding') or '').lower() == 'chunked':
        out = b''
        while True:
            size = int((await r.readuntil(b'\r\n')).split(b';')[0], 16)
            if size == 0:
                while (await r.readuntil(b'\r\n')) != b'\r\n': pass
                return out
            out += await r.readexactly(size); await r.readexactly(2)
    n = get(h, 'content-length')
    if n is not None: return await r.readexactly(int(n))
    return await r.read() if is_resp else b''

def build(start, h, body, drop=()):
    keep = [(k, v) for k, v in h if k.lower() not in ('content-length', 'transfer-encoding') + tuple(drop)]
    if body or not start.startswith('GET'): keep.append(('Content-Length', str(len(body))))
    return (start + '\r\n' + ''.join(f'{k}: {v}\r\n' for k, v in keep) + '\r\n').encode('latin-1') + body

async def pipe(r, w):
    try:
        while (d := await r.read(65536)): w.write(d); await w.drain()
    except Exception: pass
    finally:
        try: w.close()
        except Exception: pass

async def handle(cr, cw):
    try: sr, sw = await asyncio.open_connection(*UP)
    except Exception: cw.close(); return
    try:
        while True:
            start, h = await read_head(cr)
            method, path = start.split(' ')[:2]
            body = await read_body(cr, h, False)
            rl = rules()
            if (get(h, 'upgrade') or '').lower() == 'websocket':
                sw.write(build(start, h, body)); await sw.drain()
                log('WS  ', path)
                await asyncio.gather(pipe(cr, sw), pipe(sr, cw)); return
            if rl.get('_mfa_required') and method == 'GET' and path.split('?')[0] == '/api/v4/users/me/teams':
                msg = json.dumps({"id": "api.context.mfa_required.app_error", "message": "Multi-factor authentication is required on this server.",
                                  "detailed_error": "", "request_id": "tap", "status_code": 403}).encode()
                cw.write(b'HTTP/1.1 403 Forbidden\r\nContent-Type: application/json\r\nContent-Length: ' + str(len(msg)).encode() + b'\r\n\r\n' + msg)
                await cw.drain(); log('403', method, path, '(faked: two-factor required)'); continue
            config = method == 'GET' and path.split('?')[0] == '/api/v4/config/client'
            drop = ('accept-encoding', 'if-none-match') if config else ()
            sw.write(build(start, h, body, drop) if config else build(start, h, body)); await sw.drain()
            rstart, rh = await read_head(sr)
            status = int(rstart.split(' ')[1])
            rbody = await read_body(sr, rh, True, status, method)
            if config and status == 200:
                obj = json.loads(rbody)
                changed = {k: v for k, v in rl.items() if not k.startswith('_')}
                obj.update(changed)
                rbody = json.dumps(obj).encode()
                log(status, method, path, 'rewritten:', json.dumps(changed))
            else:
                log(status, method, path)
            cw.write(build(rstart, rh, rbody, ('etag',) if config else ())); await cw.drain()
            if (get(rh, 'connection') or '').lower() == 'close' or (get(h, 'connection') or '').lower() == 'close': break
    except (asyncio.IncompleteReadError, ConnectionError): pass
    except Exception as e: log('error', repr(e))
    finally:
        for w in (cw, sw):
            try: w.close()
            except Exception: pass

async def main():
    srv = await asyncio.start_server(handle, '127.0.0.1', PORT)
    log(f'policy-tap on :{PORT} -> {UP[0]}:{UP[1]}, rules from {RULES}')
    async with srv: await srv.serve_forever()

asyncio.run(main())
