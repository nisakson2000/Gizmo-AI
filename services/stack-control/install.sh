#!/usr/bin/env bash
# Install gizmo-stack-control as a systemd user service.
#
#   bash services/stack-control/install.sh
#
# Enables linger for the current user so the service survives logout / reboot,
# copies the unit into ~/.config/systemd/user/, and enables it now.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"
SERVICE_NAME="gizmo-stack-control"
SERVICE_FILE="${SCRIPT_DIR}/${SERVICE_NAME}.service"
USER_UNIT_DIR="${HOME}/.config/systemd/user"
PORT="${PORT:-9101}"

for cmd in systemctl loginctl install sudo; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "ERROR: required tool '$cmd' not found in PATH" >&2
        exit 1
    fi
done

if [[ ! -f "$SERVICE_FILE" ]]; then
    echo "ERROR: unit file not found at $SERVICE_FILE" >&2
    exit 1
fi

if ! command -v podman >/dev/null 2>&1; then
    echo "WARNING: podman not found in PATH — the service will install but won't work until podman is available" >&2
fi

echo "==> Enabling linger for '$USER' (required so the service runs without an active session)"
if loginctl show-user "$USER" --property=Linger --value 2>/dev/null | grep -qx yes; then
    echo "    linger already enabled"
else
    sudo loginctl enable-linger "$USER"
    echo "    linger enabled"
fi

echo "==> Installing unit to $USER_UNIT_DIR/${SERVICE_NAME}.service"
install -d "$USER_UNIT_DIR"
install -m 0644 "$SERVICE_FILE" "$USER_UNIT_DIR/${SERVICE_NAME}.service"

echo "==> systemctl --user daemon-reload"
systemctl --user daemon-reload

echo "==> systemctl --user enable --now $SERVICE_NAME"
systemctl --user enable --now "$SERVICE_NAME"

cat <<EOF

Installed. gizmo-stack-control is now running on port ${PORT}.

Verify:
    systemctl --user status ${SERVICE_NAME}
    journalctl --user -u ${SERVICE_NAME} -n 30 --no-pager
    curl http://localhost:${PORT}/health
    curl http://localhost:${PORT}/api/system/status

Remote-control the stack:
    curl -X POST http://localhost:${PORT}/api/system/start
    curl -X POST http://localhost:${PORT}/api/system/stop

Security: port ${PORT} has NO authentication. Expose it only over Tailscale;
never forward it publicly on your router.
EOF
