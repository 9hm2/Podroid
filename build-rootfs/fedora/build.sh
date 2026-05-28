#!/bin/sh
# Fedora rootfs configuration. systemd is PID 1.
#
# Distro-specific portion (password, /etc/issue); the common systemd unit +
# script + getty setup is sourced from configure.sh. SSH unit on Fedora is
# 'sshd.service' (not 'ssh.service' like Debian/Ubuntu).
set -eu

ROOTFS=/work/rootfs

ROOT_HASH=$(openssl passwd -6 "${ROOTFS_PASSWORD:-podroid}")
sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"
echo "podroid" > "$ROOTFS/etc/hostname"
cat > "$ROOTFS/etc/hosts" <<'EOF'
127.0.0.1 localhost podroid
::1 localhost ip6-localhost
EOF

# Common systemd config. Fedora's SSH unit is sshd.service, not ssh.service.
FILES_DIR=/work/files \
ROOTFS=/work/rootfs \
SSH_UNIT_NAME=sshd.service \
. /work/systemd-init/configure.sh

# Login banner.
cat > "$ROOTFS/etc/issue" <<EOF
Welcome to Podroid — Fedora ${PODROID_DISTRO_VERSION:-42}
Kernel \r on \m

  Default login:  root / ${ROOTFS_PASSWORD:-podroid}
  Install pkgs:   dnf install <pkg>

EOF

echo "Fedora ${PODROID_DISTRO_VERSION:-42} rootfs configured."
