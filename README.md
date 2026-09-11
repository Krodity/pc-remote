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

**Files** — browse, open, edit and save text files, create, rename, and delete
anywhere on the filesystem. "Open on PC" hands a file to `xdg-open` on the
desktop.

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

---

## API

All routes need `Authorization: Bearer <token>` except `/pair`.

| Method | Route | |
|---|---|---|
| GET | `/api/ping` | latency probe |
| GET | `/api/sysinfo` | host, OS, kernel, uptime, load, CPU, memory, disk |
| GET | `/api/fs/list?path=` | directory listing, dirs first |
| GET | `/api/fs/read?path=` | text, capped at 2 MB |
| GET | `/api/fs/download?path=` | raw bytes |
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
