#!/bin/sh
# Parrot OS rootfs configuration. systemd is PID 1.
set -eu

ROOTFS=/work/rootfs
SUITE="${PODROID_DISTRO_VERSION:-lory}"

install -Dm644 /work/parrot-archive-keyring.gpg \
    "$ROOTFS/usr/share/keyrings/parrot-archive-keyring.gpg"
cat > "$ROOTFS/etc/apt/sources.list" <<EOF
deb [signed-by=/usr/share/keyrings/parrot-archive-keyring.gpg] https://deb.parrot.sh/parrot ${SUITE} main contrib non-free non-free-firmware
deb [signed-by=/usr/share/keyrings/parrot-archive-keyring.gpg] https://deb.parrot.sh/parrot ${SUITE}-security main contrib non-free non-free-firmware
EOF
cat > "$ROOTFS/etc/apt/apt.conf.d/99podroid" <<'EOF'
APT::Install-Recommends "false";
APT::Install-Suggests "false";
Acquire::Languages "none";
EOF

ROOT_HASH=$(openssl passwd -6 "${ROOTFS_PASSWORD:-parrot}")
sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"
echo "podroid" > "$ROOTFS/etc/hostname"
cat > "$ROOTFS/etc/hosts" <<'EOF'
127.0.0.1 localhost podroid
::1 localhost ip6-localhost
EOF

FILES_DIR=/work/files \
ROOTFS=/work/rootfs \
SSH_UNIT_NAME=ssh.service \
. /work/systemd-init/configure.sh

cat > "$ROOTFS/etc/issue" <<EOF
Welcome to Podroid — Parrot OS ${SUITE}
Kernel \r on \m

  Default login:  root / ${ROOTFS_PASSWORD:-parrot}
  Install tools:  apt update && apt install <pkg>
  Pentest stack:  apt install parrot-tools-full

EOF

echo "Parrot ${SUITE} rootfs configured."
