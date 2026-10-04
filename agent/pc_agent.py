#!/usr/bin/env python3
"""pc-agent -- the PC half of PC Remote.

    Android app  --HTTP :8778-->  pc-agent  --> filesystem, sysinfo
                 --WS   :8779-->            --> /dev/uinput, PTY

Two ports rather than one because the jobs are genuinely different. File
transfer wants plain HTTP (ranges, streaming, a Content-Length the client can
show a progress bar against); the trackpad and the terminal want a socket that
stays open and pushes. Splitting them also means a stalled 2 GB download cannot
make the mouse stutter.

Security model, matching every other service on this box: the tailnet is the
boundary. The agent binds the wildcard address and UFW restricts it to
`tailscale0` (see agent/install.sh). The bearer token in
~/.config/pc-remote/token is a pairing secret so a stray tailnet peer cannot
drive the desktop by accident -- it is not a substitute for the firewall rule.

This grants a phone full control of the machine: a real login shell, arbitrary
keystrokes, and read/write on the whole filesystem. That is the point of the
tool, and it is exactly as much access as the person holding the keyboard has.
"""
import hashlib
import json
import logging
import mimetypes
import os
import pathlib
import pwd
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import sys
import termios
import threading
import time
import urllib.parse
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uinput_device import UInputDevice, MODIFIERS  # noqa: E402

HTTP_PORT = int(os.environ.get('PC_AGENT_HTTP_PORT', '8778'))
WS_PORT = int(os.environ.get('PC_AGENT_WS_PORT', '8779'))
CONF = pathlib.Path(os.environ.get(
    'PC_AGENT_CONF', str(pathlib.Path.home() / '.config/pc-remote')))
TOKEN_FILE = CONF / 'token'

HOME = pathlib.Path.home()
SHELL = os.environ.get('SHELL') or pwd.getpwuid(os.getuid()).pw_shell or '/bin/bash'

# A text preview is for glancing at a config file, not for opening a core dump.
MAX_TEXT_BYTES = 2 * 1024 * 1024
# Scrollback the agent replays to a reconnecting phone.
PTY_SCROLLBACK = 64 * 1024

log = logging.getLogger('pc-agent')


# ═════════════════════════════════════════════════════════════════════════════
# shared state
# ═════════════════════════════════════════════════════════════════════════════
def load_token() -> str:
    """Read the pairing token, minting one on first run."""
    CONF.mkdir(parents=True, exist_ok=True)
    if TOKEN_FILE.exists():
        tok = TOKEN_FILE.read_text().strip()
        if tok:
            return tok
    tok = secrets.token_urlsafe(24)
    TOKEN_FILE.write_text(tok + '\n')
    TOKEN_FILE.chmod(0o600)
    log.info('generated a new pairing token at %s', TOKEN_FILE)
    return tok


TOKEN = load_token()

# One virtual device for the whole process. Opened lazily so the agent still
# starts (and still serves files) on a machine where uinput is unavailable.
_dev = UInputDevice()
_dev_lock = threading.Lock()
_dev_error = None


def device():
    global _dev_error
    with _dev_lock:
        if _dev.fd is None:
            try:
                _dev.open()
                _dev_error = None
            except Exception as exc:  # pragma: no cover - depends on host perms
                _dev_error = str(exc)
                raise
        return _dev


def tailscale_ips():
    """Best-effort list of this host's Tailscale addresses, for the pair page."""
    try:
        out = subprocess.run(['tailscale', 'ip'], capture_output=True,
                             text=True, timeout=5)
        return [ln.strip() for ln in out.stdout.splitlines() if ln.strip()]
    except Exception:
        return []


# ═════════════════════════════════════════════════════════════════════════════
# desktop session environment
# ═════════════════════════════════════════════════════════════════════════════
# Cached because a burst of opens should not cost a /proc walk each time, but
# short enough that logging out and back in (a new WAYLAND_DISPLAY) is picked
# up on its own -- the agent outlives the session it talks to.
SESSION_ENV_TTL = 10.0
_SESSION_ENV = {'at': 0.0, 'env': None}

# Compositors, best first. Anything else owned by this user that carries a
# display variable will do; these just win the tie.
_COMPOSITORS = ('Hyprland', 'deniald', 'quickshell', 'sway', 'gnome-shell')


def _env_from_session_process():
    """Pull the graphical session's environment out of /proc.

    Exact by construction -- NUL-separated, so there is no shell quoting to
    undo, unlike `systemctl --user show-environment`, which emits values like
    QT_QPA_PLATFORM=$'wayland;xcb' that a naive parser mangles into something
    Qt then refuses to start on.
    """
    uid, best, best_score = os.getuid(), None, 0
    for entry in os.listdir('/proc'):
        if not entry.isdigit():
            continue
        proc = pathlib.Path('/proc', entry)
        try:
            if proc.stat().st_uid != uid:
                continue
            raw = (proc / 'environ').read_bytes()
            comm = (proc / 'comm').read_text().strip()
        except OSError:      # the process exited mid-walk, or is not ours
            continue
        env = {}
        for item in raw.split(b'\0'):
            key, sep, val = item.partition(b'=')
            if sep:
                env[key.decode('utf-8', 'replace')] = val.decode('utf-8',
                                                                 'replace')
        if not (env.get('WAYLAND_DISPLAY') or env.get('DISPLAY')):
            continue
        score = 2 if comm in _COMPOSITORS else 1
        if score > best_score:
            best, best_score = env, score
        if best_score == 2:
            break
    return best


def session_env():
    """This process's environment, overlaid with the live session's.

    pc-agent is started by `default.target` at boot, *before* the session runs
    `dbus-update-activation-environment`, so its own environment has no
    WAYLAND_DISPLAY / DISPLAY / XDG_CURRENT_DESKTOP. Anything GUI it launches
    therefore lands somewhere headless: `xdg-open` takes its no-display branch,
    skips the .desktop lookup entirely, falls through to a list of text-mode
    browsers and exits 3 -- which is why "Open on PC" silently did nothing.
    Ordering the unit after graphical-session.target would not be enough; the
    agent has to survive the session restarting under it.
    """
    now = time.monotonic()
    cached = _SESSION_ENV['env']
    if cached is not None and now - _SESSION_ENV['at'] < SESSION_ENV_TTL:
        return cached
    env = dict(os.environ)
    try:
        found = _env_from_session_process()
    except Exception as exc:                                  # never fatal
        log.warning('could not read the session environment: %s', exc)
        found = None
    if found:
        env.update(found)
    else:
        log.warning('no graphical session found; GUI launches may not appear')
    # Keep our own identity: these describe the agent, not the session.
    env.update(PC_REMOTE='1', HOME=str(HOME))
    _SESSION_ENV.update(at=now, env=env)
    return env


# ═════════════════════════════════════════════════════════════════════════════
# filesystem helpers
# ═════════════════════════════════════════════════════════════════════════════
def resolve(path: str) -> pathlib.Path:
    """Expand ~ and make a path absolute without resolving its final symlink.

    Keeping the last component unresolved means renaming or deleting a symlink
    acts on the link itself, which is what a file manager should do.
    """
    if not path:
        return HOME
    p = pathlib.Path(os.path.expanduser(str(path)))
    if not p.is_absolute():
        p = HOME / p
    return pathlib.Path(os.path.normpath(str(p)))


def stat_entry(parent: pathlib.Path, name: str):
    full = parent / name
    try:
        st = full.lstat()
    except OSError as exc:
        return {'name': name, 'dir': False, 'size': 0, 'mtime': 0,
                'mode': '', 'link': False, 'error': exc.strerror}
    link = os.path.islink(full)
    try:
        is_dir = full.is_dir()  # follows the link, as a file manager does
    except OSError:
        is_dir = False
    return {
        'name': name,
        'dir': is_dir,
        'size': 0 if is_dir else st.st_size,
        'mtime': int(st.st_mtime),
        'mode': oct(st.st_mode & 0o777)[2:],
        'link': link,
        'readable': os.access(full, os.R_OK),
    }


def list_dir(path: str, show_hidden=True):
    d = resolve(path)
    if not d.is_dir():
        raise NotADirectoryError(f'{d} is not a directory')
    names = []
    with os.scandir(d) as it:
        for e in it:
            if not show_hidden and e.name.startswith('.'):
                continue
            names.append(e.name)
    entries = [stat_entry(d, n) for n in names]
    # Directories first, then case-insensitive by name -- the ordering the
    # mock's file list assumes.
    entries.sort(key=lambda e: (not e['dir'], e['name'].lower()))
    parent = str(d.parent) if str(d) != '/' else None
    return {'path': str(d), 'parent': parent, 'entries': entries}


def trash(p: pathlib.Path):
    """Move to the freedesktop trash so a mis-tap on a phone is recoverable.

    Falls back to reporting failure rather than silently hard-deleting: the
    caller can retry with permanent=true if that is really what was meant.
    """
    base = pathlib.Path(os.environ.get(
        'XDG_DATA_HOME', str(HOME / '.local/share'))) / 'Trash'
    files, info = base / 'files', base / 'info'
    files.mkdir(parents=True, exist_ok=True)
    info.mkdir(parents=True, exist_ok=True)

    name = p.name
    target = files / name
    n = 1
    while target.exists() or (info / f'{target.name}.trashinfo').exists():
        target = files / f'{p.stem}.{n}{p.suffix}'
        n += 1

    (info / f'{target.name}.trashinfo').write_text(
        '[Trash Info]\n'
        f'Path={urllib.parse.quote(str(p))}\n'
        f'DeletionDate={datetime.now().strftime("%Y-%m-%dT%H:%M:%S")}\n')
    shutil.move(str(p), str(target))
    return str(target)


def delete(path: str, permanent=False):
    p = resolve(path)
    if str(p) in ('/', str(HOME)):
        raise PermissionError('refusing to delete the filesystem root or $HOME')
    if not permanent:
        return {'trashed': trash(p)}
    if p.is_dir() and not p.is_symlink():
        shutil.rmtree(p)
    else:
        p.unlink()
    return {'deleted': str(p)}


def places():
    """Shortcut list for the file browser's Home row."""
    candidates = [
        ('Home', HOME), ('Downloads', HOME / 'Downloads'),
        ('Documents', HOME / 'Documents'), ('Pictures', HOME / 'Pictures'),
        ('Videos', HOME / 'Videos'), ('Music', HOME / 'Music'),
        ('Projects', HOME / 'Projects'), ('bin', HOME / 'bin'),
        ('Memory palace', HOME / 'mempalace'), ('Config', HOME / '.config'),
        ('Games', pathlib.Path('/mnt/games')), ('Root', pathlib.Path('/')),
        ('etc', pathlib.Path('/etc')), ('Logs', pathlib.Path('/var/log')),
    ]
    return [{'label': lbl, 'path': str(p)} for lbl, p in candidates if p.is_dir()]


THUMB_CACHE = pathlib.Path(os.environ.get(
    'XDG_CACHE_HOME', str(HOME / '.cache'))) / 'pc-remote/thumbs'

IMAGE_EXT = {'png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'tiff', 'tif', 'avif',
             'heic', 'heif', 'ico', 'svg'}
VIDEO_EXT = {'mp4', 'm4v', 'mkv', 'webm', 'avi', 'mov', 'wmv', 'flv', 'mpg',
             'mpeg', 'ts', 'm2ts', '3gp', 'ogv'}


def thumbnail(p: pathlib.Path, size: int = 256) -> bytes:
    """Render a JPEG thumbnail for an image, video or PDF.

    Cached under ~/.cache/pc-remote/thumbs keyed by path+mtime+size, because a
    grid view asks for dozens at once and re-deriving a video frame per scroll
    would pin a CPU core.
    """
    ext = p.name.rsplit('.', 1)[-1].lower() if '.' in p.name else ''
    st = p.stat()
    key = hashlib.sha1(
        f'{p}\0{int(st.st_mtime)}\0{st.st_size}\0{size}'.encode()).hexdigest()
    THUMB_CACHE.mkdir(parents=True, exist_ok=True)
    cached = THUMB_CACHE / f'{key}.jpg'
    if cached.exists():
        return cached.read_bytes()

    if ext in IMAGE_EXT:
        try:
            from PIL import Image, ImageOps
            with Image.open(p) as im:
                # EXIF orientation must be applied or phone photos come out
                # sideways.
                im = ImageOps.exif_transpose(im)
                im.thumbnail((size, size))
                im = im.convert('RGB')
                im.save(cached, 'JPEG', quality=82)
        except Exception:
            # Pillow refuses very large images (its decompression-bomb guard
            # trips at ~179 MP) and has no format for some -- .avif and .heic
            # need plugins that may not be installed. That guard is aimed at
            # hostile uploads; these are the user's own files on their own
            # disk, and upscayl output here really is 235 MP. ImageMagick
            # streams through a disk-backed pixel cache, so it handles them
            # without the memory blow-up that simply raising the limit would
            # cause. "[0]" takes the first frame of a multi-page image.
            r = subprocess.run(
                ['magick', f'{p}[0]', '-auto-orient',
                 '-thumbnail', f'{size}x{size}', '-quality', '82', str(cached)],
                capture_output=True, timeout=60)
            if r.returncode != 0 or not cached.exists():
                raise ValueError(
                    (r.stderr.decode(errors='replace').strip() or
                     'could not decode the image')[:200])
    elif ext in VIDEO_EXT:
        # ffmpegthumbnailer picks a representative frame rather than whatever
        # happens to be at a fixed offset -- many films open on black.
        r = subprocess.run(
            ['ffmpegthumbnailer', '-i', str(p), '-o', str(cached),
             '-s', str(size), '-q', '8'],
            capture_output=True, timeout=30)
        if r.returncode != 0 or not cached.exists():
            raise ValueError('could not decode a frame')
    elif ext == 'pdf':
        r = subprocess.run(
            ['pdftoppm', '-jpeg', '-f', '1', '-l', '1', '-scale-to', str(size),
             str(p), str(cached.with_suffix(''))],
            capture_output=True, timeout=30)
        # pdftoppm appends a page number; normalise the name back.
        produced = next(THUMB_CACHE.glob(f'{key}-*.jpg'), None)
        if produced:
            produced.rename(cached)
        if r.returncode != 0 or not cached.exists():
            raise ValueError('could not render the PDF')
    else:
        raise ValueError(f'no thumbnail for .{ext}')

    return cached.read_bytes()


def sysinfo():
    info = {'host': socket.gethostname(), 'user': pwd.getpwuid(os.getuid()).pw_name,
            'shell': SHELL, 'home': str(HOME), 'time': int(time.time())}
    try:
        info['os'] = next(
            ln.split('=', 1)[1].strip().strip('"')
            for ln in pathlib.Path('/etc/os-release').read_text().splitlines()
            if ln.startswith('PRETTY_NAME='))
    except Exception:
        info['os'] = sys.platform
    try:
        info['kernel'] = os.uname().release
    except Exception:
        pass
    try:
        with open('/proc/uptime') as f:
            info['uptime'] = int(float(f.read().split()[0]))
    except Exception:
        pass
    try:
        info['load'] = [round(x, 2) for x in os.getloadavg()]
    except Exception:
        pass
    try:
        import psutil
        info['cpu'] = psutil.cpu_percent(interval=0.15)
        vm = psutil.virtual_memory()
        info['mem'] = {'used': vm.used, 'total': vm.total, 'percent': vm.percent}
        du = psutil.disk_usage(str(HOME))
        info['disk'] = {'used': du.used, 'total': du.total, 'percent': du.percent}
    except Exception:
        pass
    info['uinput'] = 'error: ' + _dev_error if _dev_error else 'ok'
    return info


# ═════════════════════════════════════════════════════════════════════════════
# HTTP API
# ═════════════════════════════════════════════════════════════════════════════
PAIR_PAGE = """<!doctype html>
<meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Pair PC Remote</title>
<style>
 :root{color-scheme:dark}
 body{font:16px/1.6 Inter,system-ui,sans-serif;margin:0;min-height:100vh;display:grid;
      place-items:center;padding:24px;background:#07101C;color:#C8DDEF}
 .card{max-width:22rem;text-align:center}
 h1{font-size:20px;margin:0 0 6px}
 p{color:#5C7FA3;margin:0 0 22px}
 a.btn{display:block;padding:14px;border-radius:10px;background:#22D3EE;color:#00131a;
       font-weight:700;text-decoration:none}
 code{display:block;margin-top:20px;font-size:12px;color:#5C7FA3;word-break:break-all}
</style>
<div class=card>
  <h1>Pair PC Remote</h1>
  <p>Tap below on the phone that has the app installed.</p>
  <a class=btn href="pcremote://pair?host=__HOST__&amp;token=__TOKEN__">Pair this phone</a>
  <code>If nothing happens, install the app first, then reload this page.<br><br>
  Host: __HOST__</code>
</div>
"""


class Handler(BaseHTTPRequestHandler):
    server_version = 'pc-agent'
    protocol_version = 'HTTP/1.1'

    # ── plumbing ─────────────────────────────────────────────────────────────
    def log_message(self, fmt, *args):
        log.debug('%s %s', self.address_string(), fmt % args)

    def _send(self, code, body=b'', ctype='application/json', extra=None):
        if isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj), 'application/json')

    def _err(self, code, msg):
        self._json({'error': msg}, code)

    def _authed(self):
        auth = self.headers.get('Authorization', '')
        tok = auth[7:].strip() if auth.startswith('Bearer ') else \
            urllib.parse.parse_qs(self._q).get('token', [''])[0]
        return secrets.compare_digest(tok, TOKEN)

    def _body(self):
        n = int(self.headers.get('Content-Length') or 0)
        return self.rfile.read(n) if n else b''

    def _json_body(self):
        raw = self._body()
        return json.loads(raw) if raw else {}

    # ── file streaming ───────────────────────────────────────────────────────
    def _send_file(self, p: pathlib.Path, attach=True):
        """Stream a file, honouring HTTP Range.

        Range support is what makes this usable for media: a player needs to
        seek and to fetch in chunks, and without a 206 it would have to pull a
        whole 4 GB film before showing a frame. The file is also streamed in
        blocks rather than read into memory, so serving a large video does not
        balloon the agent's RSS.
        """
        if p.is_dir():
            raise IsADirectoryError('cannot download a directory')
        size = p.stat().st_size
        ctype = mimetypes.guess_type(p.name)[0] or 'application/octet-stream'

        start, end = 0, size - 1
        partial = False
        rng = self.headers.get('Range')
        if rng and rng.startswith('bytes=') and size:
            spec = rng[6:].split(',')[0].strip()
            first, _, last = spec.partition('-')
            try:
                if first:
                    start = int(first)
                    end = int(last) if last else size - 1
                else:
                    # "bytes=-N" means the final N bytes.
                    start = max(0, size - int(last))
                    end = size - 1
            except ValueError:
                start, end = 0, size - 1
            else:
                partial = True

            if start >= size:
                self.send_response(416)
                self.send_header('Content-Range', f'bytes */{size}')
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            end = min(end, size - 1)

        length = end - start + 1
        self.send_response(206 if partial else 200)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(length))
        self.send_header('Accept-Ranges', 'bytes')
        if partial:
            self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        if attach:
            self.send_header('Content-Disposition',
                             f'attachment; filename="{urllib.parse.quote(p.name)}"')
        self.end_headers()
        if self.command == 'HEAD':
            return

        remaining = length
        with p.open('rb') as f:
            f.seek(start)
            while remaining > 0:
                chunk = f.read(min(256 * 1024, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    # A player seeking away mid-download is normal, not an error.
                    return
                remaining -= len(chunk)

    # ── routing ──────────────────────────────────────────────────────────────
    def do_GET(self):
        self._route('GET')

    def do_HEAD(self):
        self._route('GET')

    def do_POST(self):
        self._route('POST')

    def do_PUT(self):
        self._route('POST')

    def _route(self, method):
        parsed = urllib.parse.urlparse(self.path)
        path, self._q = parsed.path, parsed.query
        args = urllib.parse.parse_qs(self._q)

        def arg(name, default=''):
            return args.get(name, [default])[0]

        # The pair page is the one unauthenticated route -- it is how a phone
        # learns the token in the first place, and it is already behind the
        # tailnet.
        if path in ('/', '/pair'):
            host = self.headers.get('Host', socket.gethostname())
            page = PAIR_PAGE.replace('__HOST__', host).replace('__TOKEN__', TOKEN)
            return self._send(200, page, 'text/html; charset=utf-8')

        if not self._authed():
            return self._err(401, 'bad or missing token')

        try:
            return self._dispatch(method, path, arg)
        except FileNotFoundError as exc:
            return self._err(404, str(exc) or 'not found')
        except PermissionError as exc:
            return self._err(403, str(exc) or 'permission denied')
        except (NotADirectoryError, IsADirectoryError, ValueError) as exc:
            return self._err(400, str(exc))
        except Exception as exc:
            log.exception('%s %s failed', method, path)
            return self._err(500, f'{type(exc).__name__}: {exc}')

    def _dispatch(self, method, path, arg):
        if path == '/api/ping':
            return self._json({'ok': True, 'ts': time.time()})

        if path == '/api/sysinfo':
            return self._json(sysinfo())

        if path == '/api/fs/places':
            return self._json({'places': places()})

        if path == '/api/fs/list':
            return self._json(list_dir(arg('path', str(HOME)),
                                       arg('hidden', '1') != '0'))

        if path == '/api/fs/read':
            p = resolve(arg('path'))
            size = p.stat().st_size
            raw = p.open('rb').read(MAX_TEXT_BYTES)
            try:
                text, binary = raw.decode('utf-8'), False
            except UnicodeDecodeError:
                text, binary = raw.decode('utf-8', 'replace'), True
            return self._json({'path': str(p), 'text': text, 'size': size,
                               'binary': binary,
                               'truncated': size > MAX_TEXT_BYTES})

        if path == '/api/fs/download':
            return self._send_file(resolve(arg('path')),
                                   attach=arg('attach', '1') != '0')

        if path == '/api/fs/thumb':
            # The ceiling is a full-screen preview, not a grid tile: the phone's
            # image viewer asks for a screen-sized render of anything it cannot
            # decode itself (SVG, TIFF, or a 235 MP upscayl PNG), and 512 px
            # looked like a thumbnail blown up, because it was one.
            size = max(48, min(4096, int(arg('size', '256') or 256)))
            data = thumbnail(resolve(arg('path')), size)
            # Immutable: the cache key already includes mtime and size, so a
            # changed file is a different URL.
            return self._send(200, data, 'image/jpeg',
                              {'Cache-Control': 'max-age=86400'})

        if method != 'POST':
            return self._err(404, f'no such endpoint: {path}')

        if path == '/api/fs/upload':
            p = resolve(arg('path'))
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(self._body())
            return self._json({'ok': True, 'path': str(p)})

        body = self._json_body()

        if path == '/api/fs/write':
            p = resolve(body.get('path'))
            p.write_text(body.get('text', ''))
            return self._json({'ok': True, 'path': str(p)})

        if path == '/api/fs/mkdir':
            p = resolve(body.get('path'))
            p.mkdir(parents=True, exist_ok=False)
            return self._json({'ok': True, 'path': str(p)})

        if path == '/api/fs/create':
            p = resolve(body.get('path'))
            if p.exists():
                raise ValueError(f'{p.name} already exists')
            p.touch()
            return self._json({'ok': True, 'path': str(p)})

        if path == '/api/fs/rename':
            p = resolve(body.get('path'))
            name = str(body.get('name', '')).strip()
            if not name or '/' in name:
                raise ValueError('rename needs a bare filename')
            dest = p.parent / name
            if dest.exists():
                raise ValueError(f'{name} already exists')
            p.rename(dest)
            return self._json({'ok': True, 'path': str(dest)})

        if path == '/api/fs/copy':
            src, dst = resolve(body.get('path')), resolve(body.get('to'))
            if dst.is_dir():
                dst = dst / src.name
            if src.is_dir():
                shutil.copytree(src, dst)
            else:
                shutil.copy2(src, dst)
            return self._json({'ok': True, 'path': str(dst)})

        if path == '/api/fs/delete':
            return self._json(delete(body.get('path'),
                                     bool(body.get('permanent'))))

        if path == '/api/exec':
            cmd = body.get('cmd', '')
            timeout = float(body.get('timeout', 30))
            cwd = body.get('cwd') or str(HOME)
            r = subprocess.run(cmd, shell=True, capture_output=True, text=True,
                               timeout=timeout, cwd=cwd,
                               env=session_env(),
                               executable=SHELL)
            return self._json({'code': r.returncode, 'out': r.stdout,
                               'err': r.stderr})

        if path == '/api/open':
            # Hand a path to the desktop's default application, in the
            # *session's* environment -- see session_env(); without it
            # xdg-open finds no display and opens nothing.
            target = str(resolve(body.get('path')))
            proc = subprocess.Popen(['xdg-open', target],
                                    env=session_env(),
                                    stdout=subprocess.DEVNULL,
                                    stderr=subprocess.PIPE,
                                    start_new_session=True)
            # xdg-open exits as soon as it has handed the file off, so a
            # non-zero exit inside this window is a real failure and the phone
            # should say so rather than show the old unconditional ok. Still
            # running after it = handed off to a player that blocks.
            try:
                rc = proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                return self._json({'ok': True, 'opened': target})
            if rc != 0:
                err = (proc.stderr.read() or b'').decode('utf-8', 'replace')
                detail = ' / '.join(ln.strip() for ln in err.splitlines()
                                    if ln.strip())[-300:]
                log.warning('xdg-open %s failed (exit %s): %s',
                            target, rc, detail or '(no output)')
                return self._err(500, f'xdg-open exited {rc} on the PC'
                                      + (f': {detail}' if detail else ''))
            return self._json({'ok': True, 'opened': target})

        return self._err(404, f'no such endpoint: {path}')


class HTTPServerV6(ThreadingHTTPServer):
    """Dual-stack listener.

    Binding :: with IPV6_V6ONLY off serves both families from one socket, which
    sidesteps the trap that bit nginx here before: MagicDNS publishes A *and*
    AAAA for the host, so an IPv4-only listener looks randomly down to clients
    that prefer IPv6.
    """
    address_family = socket.AF_INET6
    daemon_threads = True
    allow_reuse_address = True

    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        super().server_bind()


def serve_http():
    srv = HTTPServerV6(('::', HTTP_PORT), Handler)
    log.info('HTTP API on [::]:%d (v4-mapped too)', HTTP_PORT)
    srv.serve_forever()


# ═════════════════════════════════════════════════════════════════════════════
# WebSocket: input injection + PTY
# ═════════════════════════════════════════════════════════════════════════════
import asyncio  # noqa: E402
import pty  # noqa: E402

import websockets  # noqa: E402
from websockets.asyncio.server import serve as ws_serve  # noqa: E402


class PtySession:
    """A real login shell behind a pseudo-terminal.

    Output is pumped to the socket by an add_reader callback rather than a
    thread, so a chatty command (think `yes` or a kernel build) cannot outrun
    the event loop and wedge the mouse channel.
    """

    def __init__(self, loop, send, cols=80, rows=24, cwd=None):
        self.loop, self.send = loop, send
        self.pid = self.fd = None
        self.scrollback = bytearray()
        self._open(cols, rows, cwd or str(HOME))

    def _open(self, cols, rows, cwd):
        pid, fd = pty.fork()
        if pid == 0:  # child
            try:
                os.chdir(cwd)
            except OSError:
                os.chdir(str(HOME))
            # session_env() rather than os.environ so a GUI app launched from
            # the phone's shell appears on the desktop like one started locally.
            env = dict(session_env())
            env.update(TERM='xterm-256color', COLUMNS=str(cols), LINES=str(rows),
                       PC_REMOTE='1')
            # -i so the shell sources the interactive rc files; the phone gets
            # the same aliases and prompt as a local terminal.
            os.execvpe(SHELL, [os.path.basename(SHELL), '-i'], env)
            os._exit(127)
        self.pid, self.fd = pid, fd
        os.set_blocking(fd, False)
        self.resize(cols, rows)
        self.loop.add_reader(fd, self._on_readable)

    def _on_readable(self):
        try:
            data = os.read(self.fd, 65536)
        except BlockingIOError:
            return
        except OSError:
            data = b''
        if not data:  # EOF -- the shell exited
            self.close()
            asyncio.ensure_future(self.send({'t': 'pty.exit'}))
            return
        self.scrollback += data
        if len(self.scrollback) > PTY_SCROLLBACK:
            del self.scrollback[:-PTY_SCROLLBACK]
        asyncio.ensure_future(self.send({
            't': 'pty.out',
            'd': data.decode('utf-8', 'replace')}))

    def write(self, text: str):
        if self.fd is not None:
            os.write(self.fd, text.encode())

    def resize(self, cols, rows):
        if self.fd is None:
            return
        try:
            import fcntl as _fcntl
            _fcntl.ioctl(self.fd, termios.TIOCSWINSZ,
                         struct.pack('HHHH', int(rows), int(cols), 0, 0))
        except OSError:
            pass

    def close(self):
        if self.fd is not None:
            try:
                self.loop.remove_reader(self.fd)
            except Exception:
                pass
            try:
                os.close(self.fd)
            except OSError:
                pass
            self.fd = None
        if self.pid:
            try:
                os.kill(self.pid, signal.SIGHUP)
                os.waitpid(self.pid, os.WNOHANG)
            except OSError:
                pass
            self.pid = None


async def ws_handler(ws):
    query = urllib.parse.urlparse(ws.request.path).query
    tok = urllib.parse.parse_qs(query).get('token', [''])[0]
    if not secrets.compare_digest(tok, TOKEN):
        await ws.close(4401, 'bad token')
        return

    loop = asyncio.get_running_loop()
    peer = ws.remote_address[0] if ws.remote_address else '?'
    log.info('client connected from %s', peer)

    send_lock = asyncio.Lock()

    async def send(obj):
        async with send_lock:
            try:
                await ws.send(json.dumps(obj))
            except Exception:
                pass

    session = None
    # Fractional deltas accumulate instead of being truncated away -- without
    # this, slow precise drags at low sensitivity move the cursor not at all.
    carry = [0.0, 0.0]

    await send({'t': 'hello', 'host': socket.gethostname(),
                'user': pwd.getpwuid(os.getuid()).pw_name,
                'home': str(HOME), 'shell': SHELL})

    try:
        async for raw in ws:
            try:
                m = json.loads(raw)
            except ValueError:
                continue
            t = m.get('t')

            try:
                if t == 'mouse':
                    dx = carry[0] + float(m.get('dx', 0))
                    dy = carry[1] + float(m.get('dy', 0))
                    ix, iy = int(dx), int(dy)
                    carry[0], carry[1] = dx - ix, dy - iy
                    if ix or iy:
                        device().move(ix, iy)

                elif t == 'btn':
                    device().button(m.get('b', 'left'), bool(m.get('down')))

                elif t == 'click':
                    n = max(1, min(3, int(m.get('n', 1))))
                    await loop.run_in_executor(
                        None, device().click, m.get('b', 'left'), n)

                elif t == 'scroll':
                    device().scroll(m.get('dy', 0), m.get('dx', 0))

                elif t == 'key':
                    device().tap(m['k'])

                elif t == 'combo':
                    await loop.run_in_executor(None, device().combo, m['k'])

                elif t == 'mod':
                    # Sticky modifiers: the phone's Ctrl button stays latched
                    # until tapped again, so chords are two taps not a gesture.
                    k = str(m.get('k', '')).lower()
                    if k not in MODIFIERS:
                        raise ValueError(f'{k} is not a modifier')
                    (device().key_down if m.get('down') else device().key_up)(k)

                elif t == 'text':
                    skipped = await loop.run_in_executor(
                        None, device().type_text, m.get('s', ''))
                    if skipped:
                        await send({'t': 'warn',
                                    'm': 'skipped unmappable: '
                                         + ''.join(sorted(set(skipped)))})

                elif t == 'release':
                    device().release_all()

                elif t == 'pty.open':
                    if session:
                        session.close()
                    session = PtySession(loop, send,
                                         int(m.get('cols', 80)),
                                         int(m.get('rows', 24)),
                                         m.get('cwd'))
                    await send({'t': 'pty.ready', 'shell': SHELL})

                elif t == 'pty.in':
                    if session:
                        session.write(m.get('d', ''))

                elif t == 'pty.size':
                    if session:
                        session.resize(m.get('cols', 80), m.get('rows', 24))

                elif t == 'pty.signal':
                    if session and session.pid:
                        os.killpg(os.getpgid(session.pid),
                                  getattr(signal, m.get('sig', 'SIGINT')))

                elif t == 'pty.close':
                    if session:
                        session.close()
                        session = None

                elif t == 'ping':
                    await send({'t': 'pong', 'id': m.get('id')})

            except Exception as exc:
                await send({'t': 'err', 'm': f'{type(exc).__name__}: {exc}'})

    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        log.info('client %s disconnected', peer)
        if session:
            session.close()
        # A phone that drops off Wi-Fi mid-chord must not leave Ctrl held down.
        try:
            device().release_all()
        except Exception:
            pass


async def serve_ws():
    # Both wildcards, explicitly. asyncio's create_server forces IPV6_V6ONLY on
    # an AF_INET6 socket, so binding only "::" would leave 127.0.0.1 and every
    # IPv4 tailnet peer refused -- the same class of bug that made nginx look
    # randomly down here. The HTTP listener solves it by clearing V6ONLY
    # instead; asyncio gives no hook for that, so it gets two sockets.
    log.info('WebSocket on 0.0.0.0:%d and [::]:%d', WS_PORT, WS_PORT)
    async with ws_serve(ws_handler, ['0.0.0.0', '::'], WS_PORT,
                        ping_interval=20, ping_timeout=20,
                        max_size=8 * 1024 * 1024):
        await asyncio.Future()


# ═════════════════════════════════════════════════════════════════════════════
def main():
    logging.basicConfig(
        level=os.environ.get('PC_AGENT_LOG', 'INFO').upper(),
        format='%(asctime)s %(levelname)-7s %(name)s: %(message)s')

    try:
        device()
        log.info('uinput device ready')
    except Exception as exc:
        log.warning('uinput unavailable (%s) -- mouse and keys will error, '
                    'files and shell still work', exc)

    threading.Thread(target=serve_http, daemon=True).start()
    log.info('pc-agent up as %s@%s; pair at http://%s:%d/pair',
             pwd.getpwuid(os.getuid()).pw_name, socket.gethostname(),
             (tailscale_ips() or [socket.gethostname()])[0], HTTP_PORT)
    try:
        asyncio.run(serve_ws())
    except KeyboardInterrupt:
        pass
    finally:
        _dev.close()


if __name__ == '__main__':
    main()
