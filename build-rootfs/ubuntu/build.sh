#!/bin/sh
# Ubuntu rootfs configuration — runs inside the Dockerfile's builder stage
# against the debootstrapped tree at /work/rootfs. systemd is PID 1.
#
# Distro-specific portion (apt sources, root password, /etc/issue); the
# common systemd unit + script + getty setup is sourced from the shared
# configure.sh.
set -eu

ROOTFS=/work/rootfs

# ── APT sources ─────────────────────────────────────────────────────────────
# Ubuntu arm64 lives on ports.ubuntu.com, not archive.ubuntu.com.
SUITE="${PODROID_DISTRO_VERSION:-noble}"
cat > "$ROOTFS/etc/apt/sources.list" <<EOF
deb http://ports.ubuntu.com/ubuntu-ports ${SUITE} main universe multiverse restricted
deb http://ports.ubuntu.com/ubuntu-ports ${SUITE}-updates main universe multiverse restricted
deb http://ports.ubuntu.com/ubuntu-ports ${SUITE}-security main universe multiverse restricted
EOF
cat > "$ROOTFS/etc/apt/apt.conf.d/99podroid" <<'EOF'
APT::Install-Recommends "false";
APT::Install-Suggests "false";
Acquire::Languages "none";
EOF

# ── Account ─────────────────────────────────────────────────────────────────
ROOT_HASH=$(openssl passwd -6 "${ROOTFS_PASSWORD:-podroid}")
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
Welcome to Podroid — Ubuntu ${SUITE}
Kernel \r on \m

  Default login:  root / ${ROOTFS_PASSWORD:-podroid}
  Install pkgs:   apt update && apt install <pkg>

EOF

echo "Ubuntu ${SUITE} rootfs configured."
