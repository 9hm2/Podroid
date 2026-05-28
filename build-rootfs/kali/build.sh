#!/bin/sh
# Kali Linux rootfs configuration. systemd is PID 1.
#
# Distro-specific portion (apt sources, root password, /etc/issue); the
# common systemd unit + script + getty setup is sourced from the shared
# configure.sh.
set -eu

ROOTFS=/work/rootfs

# ── APT: Kali repo + signing key in the proper places ───────────────────────
install -Dm644 /work/kali-archive-keyring.gpg \
    "$ROOTFS/usr/share/keyrings/kali-archive-keyring.gpg"
cat > "$ROOTFS/etc/apt/sources.list" <<'EOF'
deb [signed-by=/usr/share/keyrings/kali-archive-keyring.gpg] http://http.kali.org/kali kali-rolling main contrib non-free non-free-firmware
EOF
cat > "$ROOTFS/etc/apt/apt.conf.d/99podroid" <<'EOF'
APT::Install-Recommends "false";
APT::Install-Suggests "false";
Acquire::Languages "none";
EOF

# ── Account ─────────────────────────────────────────────────────────────────
# Kali NetHunter convention: root / kali (overridable via ROOTFS_PASSWORD).
ROOT_HASH=$(openssl passwd -6 "${ROOTFS_PASSWORD:-kali}")
sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"
echo "podroid" > "$ROOTFS/etc/hostname"
cat > "$ROOTFS/etc/hosts" <<'EOF'
127.0.0.1 localhost podroid
::1 localhost ip6-localhost
EOF

# ── Common systemd configuration ────────────────────────────────────────────
FILES_DIR=/work/files \
ROOTFS=/work/rootfs \
SSH_UNIT_NAME=ssh.service \
. /work/systemd-init/configure.sh

# ── Login banner ────────────────────────────────────────────────────────────
cat > "$ROOTFS/etc/issue" <<EOF
Welcome to Podroid — Kali Linux (rolling)
Kernel \r on \m

  Default login:  root / ${ROOTFS_PASSWORD:-kali}
  Install tools:  apt update && apt install <pkg>
  Full toolset:   apt install kali-linux-default

EOF

echo "Kali ${PODROID_DISTRO_VERSION:-rolling} rootfs configured."
