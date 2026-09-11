#!/usr/bin/env bash
# Install pc-agent as a user service and open its ports on the tailnet only.
#
# Deliberately idempotent: re-running it after a `git pull` is the upgrade path.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
HTTP_PORT="${PC_AGENT_HTTP_PORT:-8778}"
WS_PORT="${PC_AGENT_WS_PORT:-8779}"

say() { printf '\033[36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m!!\033[0m %s\n' "$*" >&2; }

# ── prerequisites ────────────────────────────────────────────────────────────
say "checking prerequisites"
python3 - <<'PY'
import sys
try:
    import websockets
except ImportError:
    sys.exit("missing python-websockets — install it with: sudo pacman -S python-websockets")
PY
python3 -c "import psutil" 2>/dev/null \
  || warn "python-psutil not found; CPU/memory readouts will be omitted"

# uinput is what makes the mouse and keyboard tabs work. Everything else
# (files, shell) runs fine without it, so this is a warning, not a failure.
if ! python3 -c "import os; os.close(os.open('/dev/uinput', os.O_WRONLY))" 2>/dev/null; then
  warn "cannot open /dev/uinput — mouse/keys will error."
  warn "  fix: add yourself to the 'input' group, and ensure a uaccess rule exists:"
  warn "  echo 'KERNEL==\"uinput\", TAG+=\"uaccess\"' | sudo tee /etc/udev/rules.d/50-uinput.rules"
fi

# ── service ──────────────────────────────────────────────────────────────────
say "installing user service to $UNIT_DIR"
mkdir -p "$UNIT_DIR"
sed "s|%h/Projects/pc-remote|$REPO|g" \
    "$REPO/agent/systemd/pc-agent.service" > "$UNIT_DIR/pc-agent.service"

systemctl --user daemon-reload
systemctl --user enable --now pc-agent.service
sleep 1

# ── firewall ─────────────────────────────────────────────────────────────────
# House rule: bind wherever, restrict with a ufw rule on tailscale0. The agent
# hands a phone a shell, so this rule is the actual security boundary -- the
# bearer token only stops an accidental connection from another tailnet peer.
if command -v ufw >/dev/null 2>&1; then
  say "allowing $HTTP_PORT,$WS_PORT/tcp on tailscale0 only"
  sudo ufw allow in on tailscale0 to any port "$HTTP_PORT" proto tcp \
      comment 'pc-agent HTTP' >/dev/null
  sudo ufw allow in on tailscale0 to any port "$WS_PORT" proto tcp \
      comment 'pc-agent WS' >/dev/null
else
  warn "ufw not found — ports $HTTP_PORT/$WS_PORT are NOT firewalled"
fi

# ── report ───────────────────────────────────────────────────────────────────
if ! systemctl --user is-active --quiet pc-agent.service; then
  warn "service failed to start:"
  systemctl --user status pc-agent.service --no-pager -n 20 || true
  exit 1
fi

ADDR="$(tailscale ip -4 2>/dev/null | head -1 || hostname)"
say "pc-agent is up"
echo
echo "  Pair the phone by opening this on the phone itself:"
echo "      http://${ADDR}:${HTTP_PORT}/pair"
echo
echo "  Token: $(cat "${XDG_CONFIG_HOME:-$HOME/.config}/pc-remote/token")"
echo "  Logs:  journalctl --user -u pc-agent -f"
