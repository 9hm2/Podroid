#!/bin/sh
# openSUSE Tumbleweed rootfs configuration. systemd is PID 1.
set -eu

ROOTFS=/work/rootfs

ROOT_HASH=$(openssl passwd -6 "${ROOTFS_PASSWORD:-podroid}")
sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"
echo "podroid" > "$ROOTFS/etc/hostname"
cat > "$ROOTFS/etc/hosts" <<'EOF'
127.0.0.1 localhost podroid
::1 localhost ip6-localhost
EOF

# Common systemd config. openSUSE's SSH unit is sshd.service.
FILES_DIR=/work/files \
ROOTFS=/work/rootfs \
SSH_UNIT_NAME=sshd.service \
. /work/systemd-init/configure.sh

cat > "$ROOTFS/etc/issue" <<EOF
Welcome to Podroid — openSUSE ${PODROID_DISTRO_VERSION:-tumbleweed}
Kernel \r on \m

  Default login:  root / ${ROOTFS_PASSWORD:-podroid}
  Install pkgs:   zypper install <pkg>

EOF

echo "openSUSE ${PODROID_DISTRO_VERSION:-tumbleweed} rootfs configured."
