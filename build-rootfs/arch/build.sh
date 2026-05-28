#!/bin/sh
# Arch Linux ARM rootfs configuration. systemd is PID 1.
set -eu

ROOTFS=/work/rootfs

# The ALARM tarball ships /etc/pacman.d/mirrorlist pointing at the
# archlinuxarm.org pool; nothing to rewrite here. Just stamp the
# Podroid-specific bits.
ROOT_HASH=$(openssl passwd -6 "${ROOTFS_PASSWORD:-podroid}")
sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"
echo "podroid" > "$ROOTFS/etc/hostname"
cat > "$ROOTFS/etc/hosts" <<'EOF'
127.0.0.1 localhost podroid
::1 localhost ip6-localhost
EOF

# Disable the default 'alarm' user that comes with Arch Linux ARM; we
# only ship a single-user (root) VM and the extra account is friction.
sed -i '/^alarm:/d' "$ROOTFS/etc/passwd" || true
sed -i '/^alarm:/d' "$ROOTFS/etc/shadow" || true
rm -rf "$ROOTFS/home/alarm" 2>/dev/null || true

# Common systemd config. Arch's SSH unit is sshd.service.
FILES_DIR=/work/files \
ROOTFS=/work/rootfs \
SSH_UNIT_NAME=sshd.service \
. /work/systemd-init/configure.sh

cat > "$ROOTFS/etc/issue" <<EOF
Welcome to Podroid — Arch Linux ARM (rolling)
Kernel \r on \m

  Default login:  root / ${ROOTFS_PASSWORD:-podroid}
  Install pkgs:   pacman -S <pkg>

EOF

echo "Arch Linux ARM rootfs configured."
