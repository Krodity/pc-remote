# PC Remote

Control this Arch box from an Android phone over Tailscale: a real trackpad, a
real keyboard, a real file manager, and a real shell.

```
Android app  ──HTTP  :8778──▶  pc-agent  ──▶  filesystem, sysinfo
             ──WS    :8779──▶            ──▶  /dev/uinput, PTY
```

Two components:

| | |
|---|---|
| `agent/` | Python daemon on the PC. No third-party framework — stdlib HTTP + `websockets`. |
| `android/` | Jetpack Compose app, `uk.krodity.pcremote`. |

---

## What each tab does

**Mouse** — a relative trackpad. One finger drags the cursor, a tap left-clicks,
two fingers scroll, a two-finger tap right-clicks, and tap-then-drag holds the
button down for select-and-drag. Sensitivity is adjustable 0.5×–6×.

**Keys** — latching modifiers (tap Ctrl, then tap C), special keys, a D-pad,
F1–F12, media keys, and a free-text field that types a whole string. The Quick
Combos are read off *this machine's* live Hyprland config — `Super+Q` closes a
window, `Super+Space` is Ulauncher, `Super+Shift+S` is the screen snip.

**Files** — browse, create, rename, and delete anywhere on the filesystem, in a
detail list or a thumbnail grid.

- **Open on PC / Open in app** — a pill in the breadcrumb row decides where a
  tapped file goes. The default is the PC (`xdg-open` on the desktop), because
  the phone can only handle text and media while the desktop handles
  everything. Switched to *app*, text opens in the built-in editor and media
  streams to a phone player. Both destinations stay reachable from the ⋮ menu
  whichever way the pill is set, and the choice persists.
- **Thumbnails** — the agent renders previews for images, video and PDF
  (Pillow, `ffmpegthumbnailer`, `pdftoppm`), cached on both ends. Video tiles
  carry a play badge so a still and a film are distinguishable at a glance.
- **Streaming** — tapping a video or audio file hands it to whatever player the
  user picks. See *Media streaming* below.

**Shell** — a genuine PTY running your login shell with `-i`, so your aliases,
your prompt and interactive programs (`top`, `vim`, `sudo`'s password prompt)
all work. The control strip supplies the keys a soft keyboard lacks: `^C`, `^D`,
`^Z`, `^L`, Tab, Esc and the arrows.

---

## Install

```bash
./agent/install.sh
```

That installs a **user** service (not system — it injects input into your
session and opens a shell as you), enables it, and adds UFW rules restricting
both ports to `tailscale0`.

Then pair. On the phone, open:

```
http://100.x.y.z:8778/pair
```

and tap **Pair this phone** — the page fires a `pcremote://pair` deep link that
fills in both the host and the token. If the browser is on another device, type
them in by hand; the token lives in `~/.config/pc-remote/token`.

The APK is at `android/app/build/outputs/apk/debug/pc-remote-1.0.0-debug.apk`.
Rebuild with:

```bash
cd android && ./gradlew assembleDebug
```

---

## Media streaming

Media **streams**; it is never downloaded whole. The app runs a **loopback HTTP
server** and gives the player a `http://127.0.0.1:<port>/…` URL; that server
pulls 1 MiB blocks from the agent on demand, a few ahead of the playhead, and
keeps them in a bounded window.

- **The token never leaves the app.** A third-party player cannot send an
  `Authorization` header, and putting the bearer token in a URL would hand a
  credential for this whole machine to an app we do not control. The loopback
  server holds the token and is itself the auth boundary.
- **The window is fixed at 96 MiB per file**, whatever its size. Slots are
  recycled least-recently-used, so playing a 4 GB film to the end costs 96 MiB
  on the phone, not 4 GB. Measured: ~27 MiB resident while streaming an 825 MB
  file.
- **The first and last block are pinned.** Container indexes live at one end or
  the other (Matroska cues, an MP4's moov), and players re-read them on every
  seek; letting them age out makes each scrub cost two extra round trips.
- **Read-ahead is 4 blocks**, on a single thread with one job in flight, so it
  cannot outrun the live read or evict what the player is about to want.

This needs HTTP Range on the agent side, which `/api/fs/download` implements
(`206`, `Content-Range`, suffix ranges, `416`), streaming in 256 KiB chunks so
serving a 4 GB film does not balloon the agent's memory.

**A foreground service runs while streaming.** The loopback server lives in the
app's process, so the moment Android decides the app is a backgrounded nobody
the socket stops being serviced and playback stalls with no useful error. This
phone is unusually eager about that — Moto's `moto_freezer` froze the process 35
seconds in during testing. The service is typed `dataSync`, not `mediaPlayback`:
it transfers file data and does not own a media session, and claiming a type you
do not implement is what gets a foreground service killed. It stops itself once
nothing has read for a minute.

**Picking a default player.** The play intent is a bare `ACTION_VIEW`, not
`createChooser`. A forced chooser cannot be dismissed with *Always*, so it makes
setting a default impossible; a plain intent gets Android's own "Open with"
dialog with *Just once* / *Always*, and the choice sticks.

---

## Security model

**The tailnet is the boundary.** The agent binds the wildcard address and UFW
restricts it to `tailscale0`, exactly as Sunshine, Samba, n8n and Jellyfin do on
this box. The bearer token stops an accidental connection from another tailnet
peer; it is not a second wall.

Be clear about what this grants: a phone that is paired has **a real shell as
your user**, arbitrary keystrokes, and read/write over the whole filesystem. If
your user has passwordless sudo, that is effectively root. This is the point of the
tool — it is exactly the access of the person sitting at the keyboard — but it
means the token deserves the same care as an SSH key.

Traffic is plain HTTP/WS. Tailscale (WireGuard) already provides encryption and
peer authentication; terminating TLS again inside the tunnel would only add a
self-signed certificate to click past.

Two deliberate safety rails:

- **Delete moves to the freedesktop trash**, not `unlink`. A mis-tap on a phone
  is far easier than a mis-click on a desktop. `~/.local/share/Trash` makes it
  recoverable; the API takes `permanent: true` to really remove something.
- **The agent refuses to delete `/` or `$HOME`** regardless of that flag.

---

## Why uinput rather than ydotool

`ydotool` is installed here and would have worked, but every event would cost a
fork+exec — far too slow for a trackpad streaming deltas at 60 Hz. `ydotoold`
itself sits on the kernel's uinput interface, so the agent opens `/dev/uinput`
directly and skips the round trip.

The bigger win is that uinput injects at the evdev layer, *below* the
compositor: it behaves identically on Hyprland, on X11, or on a bare VT, and it
survives a compositor restart.

Access comes from `/etc/udev/rules.d/50-uinput.rules` (`TAG+="uaccess"`) plus
membership of group `input`.

Keycodes are raw scancodes, so what a key *produces* depends on the active
layout. The character map is **US QWERTY**, matching `kb_layout = us` in
`~/.config/hypr/input.conf`. Change the layout and typed text will need a new
map; individual keys and combos are unaffected.

---

## Gotchas worth knowing

- **Both wildcards are bound, on purpose.** asyncio's `create_server` forces
  `IPV6_V6ONLY` on an `AF_INET6` socket, so binding only `::` leaves `127.0.0.1`
  and every IPv4 tailnet peer refused. The WS listener takes two sockets; the
  HTTP listener clears `V6ONLY` instead. MagicDNS publishes A *and* AAAA for
  `aepc`, so getting this wrong makes the agent look randomly down — the same
  class of bug that bit nginx here before.
- **Never `pkill -f pc_agent.py`** — the pattern matches the invoking shell's
  own command line and kills it (exit 144). Use
  `systemctl --user restart pc-agent`, or `pgrep -f 'pc_agent[.]py'` and kill by
  PID.
- A client that drops off Wi-Fi mid-chord would otherwise leave Ctrl held down
  on the desktop; the agent calls `release_all()` on disconnect.
- `Alt+F4` does nothing on this desktop. `Super+Q` closes a window.
- **OkHttp's `newBuilder()` shares the Dispatcher and ConnectionPool** with the
  parent client. The default Dispatcher caps one host at 5 concurrent requests,
  so media block fetches queued the ping call and the socket handshake behind
  them and the app went "offline" the instant a video started. The streaming
  client replaces both.
- Android reaps the WebSocket while the app sits in the background — which is
  exactly what happens when an external player is in the foreground. The ping
  loop notices (agent reachable, socket gone) and rebuilds it, and `onResume`
  triggers the same check immediately.
- **JetBrains Mono is bundled as the Nerd Font build.** With the platform
  monospace font, this box's powerline prompt renders as tofu boxes. The ANSI
  interpreter also honours background colours for the same reason: powerline
  separator glyphs are drawn in the adjoining segment's background, so dropping
  backgrounds turns the prompt into white blobs.
- Pillow's decompression-bomb guard trips at ~179 MP and refuses upscayl output
  (235 MP here). Thumbnailing falls back to ImageMagick, which streams through a
  disk-backed pixel cache rather than blowing up memory.

---

## API

All routes need `Authorization: Bearer <token>` except `/pair`.

| Method | Route | |
|---|---|---|
| GET | `/api/ping` | latency probe |
| GET | `/api/sysinfo` | host, OS, kernel, uptime, load, CPU, memory, disk |
| GET | `/api/fs/list?path=` | directory listing, dirs first |
| GET | `/api/fs/read?path=` | text, capped at 2 MB |
| GET | `/api/fs/download?path=` | raw bytes; supports Range, `attach=0` to inline |
| GET | `/api/fs/thumb?path=&size=` | JPEG preview of an image, video or PDF |
| POST | `/api/fs/write` `{path,text}` | |
| POST | `/api/fs/upload?path=` | raw body |
| POST | `/api/fs/mkdir` · `/create` · `/rename` · `/copy` | |
| POST | `/api/fs/delete` `{path,permanent?}` | trash unless `permanent` |
| POST | `/api/exec` `{cmd,cwd?,timeout?}` | one-shot, non-interactive |
| POST | `/api/open` `{path}` | `xdg-open` on the desktop |

WebSocket messages (`ws://host:8779/?token=…`), phone → agent:

```
{"t":"mouse","dx":,"dy":}          {"t":"key","k":"f5"}
{"t":"btn","b":"left","down":}     {"t":"combo","k":"ctrl+shift+s"}
{"t":"click","b":"left","n":2}     {"t":"mod","k":"ctrl","down":true}
{"t":"scroll","dy":1}              {"t":"text","s":"hello"}
{"t":"pty.open","cols":,"rows":}   {"t":"pty.in","d":"ls\n"}
{"t":"pty.size","cols":,"rows":}   {"t":"pty.signal","sig":"SIGINT"}
```

agent → phone: `hello`, `pty.ready`, `pty.out`, `pty.exit`, `pong`, `warn`, `err`.
