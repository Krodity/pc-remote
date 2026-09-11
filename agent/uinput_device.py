#!/usr/bin/env python3
"""A virtual mouse + keyboard driven straight through /dev/uinput.

ydotool is installed on this box and would have worked, but every event would
cost a fork+exec -- far too slow for a trackpad streaming deltas at 60 Hz. The
kernel's uinput interface is the thing ydotoold itself sits on, so we open it
directly and skip the round trip.

The other reason to prefer uinput: it injects at the evdev layer, below the
compositor. That makes it session-agnostic -- it works the same on Hyprland, on
X11, or on a bare VT, and it keeps working across a compositor restart.

Access: /dev/uinput is root:input 0660 with a uaccess ACL granting the seat
owner rw (see /etc/udev/rules.d/50-uinput.rules). aepc is also in group `input`.

Keycodes are raw scancodes, so what a key *produces* depends on the
compositor's active layout. The character map below is US QWERTY, matching
`kb_layout = us` in ~/.config/hypr/input.conf.
"""
import fcntl
import os
import struct
import time

# ── ioctl plumbing ───────────────────────────────────────────────────────────
_IOC_WRITE = 1
_IOC_NRSHIFT, _IOC_TYPESHIFT, _IOC_SIZESHIFT, _IOC_DIRSHIFT = 0, 8, 16, 30


def _IOC(direction, typ, nr, size):
    return ((direction << _IOC_DIRSHIFT) | (ord(typ) << _IOC_TYPESHIFT)
            | (nr << _IOC_NRSHIFT) | (size << _IOC_SIZESHIFT))


def _IO(typ, nr):
    return _IOC(0, typ, nr, 0)


def _IOW(typ, nr, size):
    return _IOC(_IOC_WRITE, typ, nr, size)


UI_DEV_CREATE = _IO('U', 1)
UI_DEV_DESTROY = _IO('U', 2)
UI_DEV_SETUP = _IOW('U', 3, struct.calcsize('HHHH80sI'))
UI_SET_EVBIT = _IOW('U', 100, 4)
UI_SET_KEYBIT = _IOW('U', 101, 4)
UI_SET_RELBIT = _IOW('U', 102, 4)

# ── event types / codes (linux/input-event-codes.h) ──────────────────────────
EV_SYN, EV_KEY, EV_REL = 0x00, 0x01, 0x02
SYN_REPORT = 0
REL_X, REL_Y, REL_HWHEEL, REL_WHEEL = 0x00, 0x01, 0x06, 0x08
REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES = 0x0b, 0x0c

BTN_LEFT, BTN_RIGHT, BTN_MIDDLE = 0x110, 0x111, 0x112
BTN_SIDE, BTN_EXTRA = 0x113, 0x114

BUTTONS = {
    'left': BTN_LEFT, 'right': BTN_RIGHT, 'middle': BTN_MIDDLE,
    'back': BTN_SIDE, 'forward': BTN_EXTRA,
}

# struct input_event on 64-bit: struct timeval (2 x long) + u16 + u16 + s32
_EVENT_FMT = 'llHHi'
_EVENT_SIZE = struct.calcsize(_EVENT_FMT)

# ── keycode table ────────────────────────────────────────────────────────────
# Named keys, addressed by the lowercase names the Android app sends.
KEYS = {
    'esc': 1, 'escape': 1,
    '1': 2, '2': 3, '3': 4, '4': 5, '5': 6, '6': 7, '7': 8, '8': 9, '9': 10,
    '0': 11, 'minus': 12, 'equal': 13, 'backspace': 14, 'bksp': 14, 'tab': 15,
    'q': 16, 'w': 17, 'e': 18, 'r': 19, 't': 20, 'y': 21, 'u': 22, 'i': 23,
    'o': 24, 'p': 25, 'leftbrace': 26, 'rightbrace': 27, 'enter': 28,
    'ctrl': 29, 'leftctrl': 29,
    'a': 30, 's': 31, 'd': 32, 'f': 33, 'g': 34, 'h': 35, 'j': 36, 'k': 37,
    'l': 38, 'semicolon': 39, 'apostrophe': 40, 'grave': 41,
    'shift': 42, 'leftshift': 42, 'backslash': 43,
    'z': 44, 'x': 45, 'c': 46, 'v': 47, 'b': 48, 'n': 49, 'm': 50,
    'comma': 51, 'dot': 52, 'period': 52, 'slash': 53, 'rightshift': 54,
    'kpasterisk': 55, 'alt': 56, 'leftalt': 56, 'space': 57, 'capslock': 58,
    'caps': 58,
    'f1': 59, 'f2': 60, 'f3': 61, 'f4': 62, 'f5': 63, 'f6': 64, 'f7': 65,
    'f8': 66, 'f9': 67, 'f10': 68, 'numlock': 69, 'scrolllock': 70,
    'kp7': 71, 'kp8': 72, 'kp9': 73, 'kpminus': 74, 'kp4': 75, 'kp5': 76,
    'kp6': 77, 'kpplus': 78, 'kp1': 79, 'kp2': 80, 'kp3': 81, 'kp0': 82,
    'kpdot': 83,
    'f11': 87, 'f12': 88,
    'kpenter': 96, 'rightctrl': 97, 'kpslash': 98, 'sysrq': 99, 'printscreen': 99,
    'rightalt': 100, 'altgr': 100,
    'home': 102, 'up': 103, 'pageup': 104, 'pgup': 104,
    'left': 105, 'right': 106, 'end': 107, 'down': 108,
    'pagedown': 109, 'pgdn': 109, 'insert': 110, 'ins': 110,
    'delete': 111, 'del': 111,
    'mute': 113, 'volumedown': 114, 'volumeup': 115, 'power': 116,
    'pause': 119,
    'super': 125, 'win': 125, 'meta': 125, 'leftmeta': 125, 'rightmeta': 126,
    'compose': 127, 'menu': 127,
    'stop': 128, 'again': 129, 'undo': 131, 'copy': 133, 'open': 134,
    'paste': 135, 'find': 136, 'cut': 137, 'help': 138,
    'f13': 183, 'f14': 184, 'f15': 185, 'f16': 186, 'f17': 187, 'f18': 188,
    'f19': 189, 'f20': 190, 'f21': 191, 'f22': 192, 'f23': 193, 'f24': 194,
    'playpause': 164, 'stopcd': 166, 'previoussong': 165, 'nextsong': 163,
    'brightnessdown': 224, 'brightnessup': 225,
}

MODIFIERS = {'ctrl', 'leftctrl', 'rightctrl', 'shift', 'leftshift', 'rightshift',
             'alt', 'leftalt', 'rightalt', 'altgr', 'super', 'win', 'meta',
             'leftmeta', 'rightmeta'}

# US QWERTY: character -> (keycode, needs_shift)
_UNSHIFTED = {
    '`': 41, '1': 2, '2': 3, '3': 4, '4': 5, '5': 6, '6': 7, '7': 8, '8': 9,
    '9': 10, '0': 11, '-': 12, '=': 13,
    '[': 26, ']': 27, '\\': 43, ';': 39, "'": 40, ',': 51, '.': 52, '/': 53,
    ' ': 57, '\t': 15, '\n': 28, '\r': 28,
}
_SHIFTED = {
    '~': 41, '!': 2, '@': 3, '#': 4, '$': 5, '%': 6, '^': 7, '&': 8, '*': 9,
    '(': 10, ')': 11, '_': 12, '+': 13,
    '{': 26, '}': 27, '|': 43, ':': 39, '"': 40, '<': 51, '>': 52, '?': 53,
}

CHARMAP = {}
for _c, _k in _UNSHIFTED.items():
    CHARMAP[_c] = (_k, False)
for _c, _k in _SHIFTED.items():
    CHARMAP[_c] = (_k, True)
for _i, _c in enumerate('abcdefghijklmnopqrstuvwxyz'):
    CHARMAP[_c] = (KEYS[_c], False)
    CHARMAP[_c.upper()] = (KEYS[_c], True)


class UInputDevice:
    """One virtual device exposing both a relative pointer and a full keyboard.

    Presenting mouse and keyboard as a single device keeps the setup simple and
    matches what a USB combo receiver looks like to the kernel.
    """

    NAME = b'pc-remote virtual input'

    def __init__(self, path='/dev/uinput'):
        self.path = path
        self.fd = None
        self._held = set()

    # ── lifecycle ────────────────────────────────────────────────────────────
    def open(self):
        if self.fd is not None:
            return
        fd = os.open(self.path, os.O_WRONLY | os.O_NONBLOCK)
        try:
            for ev in (EV_KEY, EV_REL, EV_SYN):
                fcntl.ioctl(fd, UI_SET_EVBIT, ev)
            for rel in (REL_X, REL_Y, REL_WHEEL, REL_HWHEEL,
                        REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES):
                fcntl.ioctl(fd, UI_SET_RELBIT, rel)
            for btn in BUTTONS.values():
                fcntl.ioctl(fd, UI_SET_KEYBIT, btn)
            for code in set(KEYS.values()):
                fcntl.ioctl(fd, UI_SET_KEYBIT, code)

            setup = struct.pack(
                'HHHH80sI',
                0x03,        # BUS_USB -- some compositors ignore virtual buses
                0x1d6b,      # vendor: Linux Foundation
                0x0104,      # product
                1,           # version
                self.NAME.ljust(80, b'\0')[:80],
                0,           # ff_effects_max
            )
            fcntl.ioctl(fd, UI_DEV_SETUP, setup)
            fcntl.ioctl(fd, UI_DEV_CREATE)
        except Exception:
            os.close(fd)
            raise
        self.fd = fd
        # udev needs a beat to notice the new node; without this the first few
        # events after startup land before libinput has attached and vanish.
        time.sleep(0.25)

    def close(self):
        if self.fd is None:
            return
        try:
            self.release_all()
            fcntl.ioctl(self.fd, UI_DEV_DESTROY)
        except OSError:
            pass
        finally:
            os.close(self.fd)
            self.fd = None

    def __enter__(self):
        self.open()
        return self

    def __exit__(self, *exc):
        self.close()

    # ── raw writes ───────────────────────────────────────────────────────────
    def _emit(self, etype, code, value):
        if self.fd is None:
            raise RuntimeError('uinput device is not open')
        os.write(self.fd, struct.pack(_EVENT_FMT, 0, 0, etype, code, value))

    def _syn(self):
        self._emit(EV_SYN, SYN_REPORT, 0)

    # ── pointer ──────────────────────────────────────────────────────────────
    def move(self, dx, dy):
        """Relative pointer motion. Sub-pixel remainders are the caller's job."""
        dx, dy = int(dx), int(dy)
        if not dx and not dy:
            return
        if dx:
            self._emit(EV_REL, REL_X, dx)
        if dy:
            self._emit(EV_REL, REL_Y, dy)
        self._syn()

    def button(self, name, down):
        code = BUTTONS.get(name)
        if code is None:
            raise ValueError(f'unknown mouse button: {name}')
        self._emit(EV_KEY, code, 1 if down else 0)
        self._syn()

    def click(self, name='left', count=1, interval=0.04):
        for i in range(count):
            if i:
                time.sleep(interval)
            self.button(name, True)
            self.button(name, False)

    def scroll(self, dy=0, dx=0):
        """Wheel scroll. dy>0 scrolls up, matching the kernel's convention.

        Both the classic and hi-res axes are emitted: libinput prefers hi-res
        when a device advertises it, and sending only the legacy axis makes
        scrolling feel notchy in GTK apps.
        """
        dy, dx = int(dy), int(dx)
        if dy:
            self._emit(EV_REL, REL_WHEEL, dy)
            self._emit(EV_REL, REL_WHEEL_HI_RES, dy * 120)
        if dx:
            self._emit(EV_REL, REL_HWHEEL, dx)
            self._emit(EV_REL, REL_HWHEEL_HI_RES, dx * 120)
        if dy or dx:
            self._syn()

    # ── keyboard ─────────────────────────────────────────────────────────────
    @staticmethod
    def keycode(name):
        code = KEYS.get(str(name).strip().lower())
        if code is None:
            raise ValueError(f'unknown key: {name}')
        return code

    def key_down(self, name):
        code = self.keycode(name)
        self._emit(EV_KEY, code, 1)
        self._syn()
        self._held.add(code)

    def key_up(self, name):
        code = self.keycode(name)
        self._emit(EV_KEY, code, 0)
        self._syn()
        self._held.discard(code)

    def tap(self, name, delay=0.012):
        self.key_down(name)
        time.sleep(delay)
        self.key_up(name)

    def combo(self, spec, delay=0.012):
        """Press a chord such as "ctrl+shift+escape" and release it in reverse.

        Releasing in reverse order matters: a compositor that sees Super
        released before D on Super+D may still open its own launcher.
        """
        parts = [p for p in str(spec).replace(' ', '').split('+') if p]
        if not parts:
            return
        codes = [self.keycode(p) for p in parts]
        for code in codes:
            self._emit(EV_KEY, code, 1)
            self._syn()
            time.sleep(delay)
        for code in reversed(codes):
            self._emit(EV_KEY, code, 0)
            self._syn()

    def type_text(self, text, delay=0.006):
        """Type a string as US-layout keystrokes.

        Characters with no US mapping (accents, emoji) are skipped and returned
        so the caller can report them rather than silently losing input.
        """
        skipped = []
        shift_held = False
        try:
            for ch in text:
                entry = CHARMAP.get(ch)
                if entry is None:
                    skipped.append(ch)
                    continue
                code, needs_shift = entry
                if needs_shift and not shift_held:
                    self._emit(EV_KEY, KEYS['leftshift'], 1)
                    self._syn()
                    shift_held = True
                elif not needs_shift and shift_held:
                    self._emit(EV_KEY, KEYS['leftshift'], 0)
                    self._syn()
                    shift_held = False
                self._emit(EV_KEY, code, 1)
                self._syn()
                self._emit(EV_KEY, code, 0)
                self._syn()
                if delay:
                    time.sleep(delay)
        finally:
            if shift_held:
                self._emit(EV_KEY, KEYS['leftshift'], 0)
                self._syn()
        return skipped

    def release_all(self):
        """Drop every key and button we are still holding.

        Called when a client disconnects -- otherwise a phone that loses Wi-Fi
        mid-chord leaves Ctrl stuck down on the desktop.
        """
        for code in list(self._held):
            try:
                self._emit(EV_KEY, code, 0)
            except OSError:
                pass
        self._held.clear()
        for code in BUTTONS.values():
            try:
                self._emit(EV_KEY, code, 0)
            except OSError:
                pass
        try:
            self._syn()
        except OSError:
            pass
