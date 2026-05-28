#!/bin/sh
# Debian rootfs configuration — runs inside the Dockerfile's builder stage
# against the debootstrapped tree at /work/rootfs. systemd is PID 1.
#
# Distro-specific portion (apt sources, root password, /etc/issue); the
# common systemd unit + script + getty setup is sourced from the shared
# configure.sh.
set -eu

ROOTFS=/work/rootfs

# ── APT sources ─────────────────────────────────────────────────────────────
# debootstrap copies its own sources.list to the rootfs but pinned to the
# bootstrap mirror; rewrite it as a clean deb822-or-classic stanza so apt
# update inside the VM picks up signed-by from debian-archive-keyring
# (which is pre-installed via debootstrap --include).
cat > "$ROOTFS/etc/apt/sources.list" <<EOF
deb http://deb.debian.org/debian ${PODROID_DISTRO_VERSION:-trixie} main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security ${PODROID_DISTRO_VERSION:-trixie}-security main contrib non-free non-free-firmware
deb http://deb.debian.org/debian ${PODROID_DISTRO_VERSION:-trixie}-updates main contrib non-free non-free-firmware
EOF
cat > "$ROOTFS/etc/apt/apt.conf.d/99podroid" <<'EOF'
APT::Install-Recommends "false";
APT::Install-Suggests "false";
Acquire::Languages "none";
EOF

# ── Account ─────────────────────────────────────────────────────────────────
# Set root password — chpasswd would need /proc inside chroot which we can't
# mount in unprivileged docker build, so write the SHA-512 hash directly.
ROOT_HASH=$(openssl passwd -6 "${ROOTFS_PASSWORD:-podroid}")
sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"
echo "podroid" > "$ROOTFS/etc/hostname"
cat > "$ROOTFS/etc/hosts" <<'EOF'
127.0.0.1 localhost podroid
::1 localhost ip6-localhost
EOF

# ── Common systemd configuration (services, getty, gpsd, profile.d, slim) ──
FILES_DIR=/work/files \
ROOTFS=/work/rootfs \
SSH_UNIT_NAME=ssh.service \
. /work/systemd-init/configure.sh

# ── Login banner ────────────────────────────────────────────────────────────
cat > "$ROOTFS/etc/issue" <<EOF
Welcome to Podroid — Debian ${PODROID_DISTRO_VERSION:-trixie}
Kernel \r on \m

  Default login:  root / ${ROOTFS_PASSWORD:-podroid}
  Install pkgs:   apt update && apt install <pkg>

EOF

echo "Debian ${PODROID_DISTRO_VERSION:-trixie} rootfs configured."
