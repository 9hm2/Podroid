#!/bin/sh
# Kali NetHunter rootfs configuration — runs in the Dockerfile.rootfs builder
# stage against the debootstrapped Kali tree at /work/rootfs. systemd is PID 1.
#
# Keeps the in-image package set deliberately small (the user picked
# "small core + runtime apt"); the rest of the Kali toolset is installed with
# `apt install` inside the VM onto the persistent overlay.
set -eu
ROOTFS=/work/rootfs

# aarch64 chroot helper — runs a command inside the rootfs via qemu-user.
in_rootfs() { chroot "$ROOTFS" /usr/bin/qemu-aarch64-static /bin/bash -c "$*"; }

# ── APT: Kali repo + signing key in the proper places ───────────────────────
install -Dm644 /work/kali-archive-keyring.gpg \
    "$ROOTFS/usr/share/keyrings/kali-archive-keyring.gpg"
cat > "$ROOTFS/etc/apt/sources.list" <<'EOF'
# Kali Linux rolling — signed by the keyring debootstrap/Podroid installed.
deb [signed-by=/usr/share/keyrings/kali-archive-keyring.gpg] http://http.kali.org/kali kali-rolling main contrib non-free non-free-firmware
EOF

# Don't let apt pull recommends/docs into the shipped image — keeps it small.
cat > "$ROOTFS/etc/apt/apt.conf.d/99podroid" <<'EOF'
APT::Install-Recommends "false";
APT::Install-Suggests "false";
Acquire::Languages "none";
EOF

# ── Core package set (small; everything else via runtime `apt`) ─────────────
# dpkg postinst scripts need a live /proc, /sys, /dev inside the chroot.
mount -t proc proc "$ROOTFS/proc"
mount --rbind /sys "$ROOTFS/sys"
mount --rbind /dev "$ROOTFS/dev"

# systemd + udev + dbus  : PID 1 and device/bus management
# kali-archive-keyring   : keeps the key fresh after the first `apt upgrade`
# openssh-server, sudo   : remote shell + privilege
# net stack + wireless   : the minimum for passed-through USB Wi-Fi auditing
# tigervnc + pulseaudio  : the in-app X11 viewer (desktop env via runtime apt)
in_rootfs "export DEBIAN_FRONTEND=noninteractive && apt-get update && apt-get install -y \
    systemd-sysv udev dbus libpam-systemd \
    kali-archive-keyring ca-certificates \
    openssh-server sudo \
    iproute2 iputils-ping net-tools isc-dhcp-client \
    iw wireless-tools rfkill wpasupplicant \
    aircrack-ng \
    usbutils pciutils kmod \
    bash-completion less nano \
    tigervnc-standalone-server tigervnc-common pulseaudio pulseaudio-utils \
    && apt-get clean"

# ── Accounts ────────────────────────────────────────────────────────────────
# Kali NetHunter convention: root / kali.
in_rootfs "echo 'root:kali' | chpasswd"
echo "podroid" > "$ROOTFS/etc/hostname"
cat > "$ROOTFS/etc/hosts" <<'EOF'
127.0.0.1 localhost podroid
::1 localhost ip6-localhost
EOF

# ── Podroid VM bringup — systemd units + scripts ────────────────────────────
# The bringup logic is ported verbatim from the OpenRC services; systemd just
# orders it. Each script is a plain shell program run by a oneshot unit.
mkdir -p "$ROOTFS/usr/local/sbin" "$ROOTFS/etc/systemd/system"

# --- podroid-bootstrap: cgroups, /dev mounts, ZRAM, sysctl, scheduler -------
cat > "$ROOTFS/usr/local/sbin/podroid-bootstrap" <<'BOOT'
#!/bin/sh
echo "Loading kernel modules..." > /dev/console
mount --make-rshared / 2>/dev/null
mkdir -p /dev/pts /dev/shm /dev/mqueue 2>/dev/null
mountpoint -q /dev/pts    || mount -t devpts devpts /dev/pts -o gid=5,mode=0620,ptmxmode=0666,noexec,nosuid
mountpoint -q /dev/shm    || mount -t tmpfs tmpfs /dev/shm -o noexec,nosuid,nodev,size=64m
mountpoint -q /dev/mqueue || mount -t mqueue mqueue /dev/mqueue -o noexec,nosuid,nodev
[ -r /etc/hostname ] && hostname -F /etc/hostname 2>/dev/null
depmod -a 2>/dev/null
for q in /sys/block/vda/queue/scheduler /sys/block/vdb/queue/scheduler; do
    [ -w "$q" ] && echo mq-deadline > "$q" 2>/dev/null
done
mkdir -p /dev/net 2>/dev/null
[ -c /dev/net/tun ] || mknod /dev/net/tun c 10 200 2>/dev/null
# ZRAM swap (1.5x RAM, lz4).
if [ -b /dev/zram0 ]; then
    _mem_kb=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
    echo lz4 > /sys/block/zram0/comp_algorithm 2>/dev/null
    echo $((_mem_kb * 1536)) > /sys/block/zram0/disksize 2>/dev/null
    mkswap /dev/zram0 >/dev/null 2>&1 && swapon -p 100 /dev/zram0 2>/dev/null
fi
[ -w /proc/sys/vm/overcommit_memory ] && echo 1 > /proc/sys/vm/overcommit_memory
sysctl -qw net.ipv4.ip_forward=1 net.ipv6.conf.all.forwarding=1 2>/dev/null
exit 0
BOOT

# --- podroid-network: SLIRP static 10.0.2.15, or DHCP on AVF ----------------
cat > "$ROOTFS/usr/local/sbin/podroid-network" <<'NET'
#!/bin/sh
echo "Configuring containers..." > /dev/console
ip link set lo up 2>/dev/null
NETIF=""
for _i in $(seq 1 20); do
    NETIF=$(ip -o link show 2>/dev/null | awk -F': ' '{print $2}' \
        | grep -vE '^(lo|dummy[0-9]*|veth|docker|br-|wlan)' | head -1)
    [ -n "$NETIF" ] && break
    sleep 0.1
done
[ -z "$NETIF" ] && { echo "no network interface" > /dev/console; exit 1; }
ip link set "$NETIF" up
if grep -q 'podroid\.backend=avf' /proc/cmdline 2>/dev/null; then
    dhclient -1 "$NETIF" 2>/dev/null || true
    [ -s /etc/resolv.conf ] || printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > /etc/resolv.conf
else
    ip addr add 10.0.2.15/24 dev "$NETIF" 2>/dev/null
    ip route add default via 10.0.2.2 dev "$NETIF" 2>/dev/null
    printf 'nameserver 10.0.2.3\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n' > /etc/resolv.conf
fi
echo "Network found" > /dev/console
exit 0
NET

# --- podroid-x11: Xvnc + pulseaudio for the in-app viewer -------------------
cat > "$ROOTFS/usr/local/sbin/podroid-x11" <<'X11'
#!/bin/sh
XVNC_DPI=$(sed -n 's/.*podroid\.x11\.dpi=\([0-9]*\).*/\1/p' /proc/cmdline 2>/dev/null)
[ -z "$XVNC_DPI" ] && XVNC_DPI=96
rm -f /tmp/.X0-lock /tmp/.X11-unix/X0
mkdir -p /run/podroid-pulse && chmod 755 /run/podroid-pulse
Xvnc :0 -geometry 1280x720 -depth 24 -SecurityTypes None -localhost no \
    -rfbport 5900 -AlwaysShared -dpi "$XVNC_DPI" -AcceptSetDesktopSize \
    >/var/log/xvnc.log 2>&1 &
XDG_RUNTIME_DIR=/run/podroid-pulse PULSE_RUNTIME_PATH=/run/podroid-pulse \
    pulseaudio --daemonize=no --disallow-exit --exit-idle-time=-1 \
    --load="module-null-sink sink_name=podroid_sink rate=44100 channels=2 format=s16le" \
    --load="module-simple-protocol-tcp source=podroid_sink.monitor record=true rate=44100 format=s16le channels=2 listen=0.0.0.0 port=4713" \
    --load="module-native-protocol-unix" \
    >/var/log/pulse.log 2>&1 &
exit 0
X11

# --- podroid-ready: emit the markers the Android boot detector consumes -----
cat > "$ROOTFS/usr/local/sbin/podroid-ready" <<'RDY'
#!/bin/sh
for _name in sshd Xvnc pulseaudio podroid-vsock-agent; do
    for _pid in $(pgrep -x "$_name" 2>/dev/null); do
        echo -1000 > "/proc/$_pid/oom_score_adj" 2>/dev/null
    done
done
echo "Starting SSH..." > /dev/console
echo "Almost ready..." > /dev/console
echo "Ready!" > /dev/console
exit 0
RDY

# --- podroid-resize: hvc1 RESIZE lines → stty hvc0 --------------------------
cp /work/files/usr/local/bin/podroid-resize "$ROOTFS/usr/local/sbin/podroid-resize"
cp /work/files/usr/local/bin/podroid-login  "$ROOTFS/usr/local/bin/podroid-login"
chmod +x "$ROOTFS/usr/local/sbin/podroid-"* "$ROOTFS/usr/local/bin/podroid-login"

# --- systemd units ----------------------------------------------------------
cat > "$ROOTFS/etc/systemd/system/podroid-bootstrap.service" <<'EOF'
[Unit]
Description=Podroid VM bootstrap (cgroups, /dev, ZRAM, sysctl)
DefaultDependencies=no
After=systemd-remount-fs.service
Before=sysinit.target network-pre.target
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/podroid-bootstrap
RemainAfterExit=yes
[Install]
WantedBy=sysinit.target
EOF

cat > "$ROOTFS/etc/systemd/system/podroid-network.service" <<'EOF'
[Unit]
Description=Podroid VM networking
After=podroid-bootstrap.service
Before=network.target
Wants=network.target
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/podroid-network
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
EOF

cat > "$ROOTFS/etc/systemd/system/podroid-x11.service" <<'EOF'
[Unit]
Description=Podroid X11 viewer server (Xvnc + PulseAudio)
After=podroid-network.service
[Service]
Type=forking
ExecStart=/usr/local/sbin/podroid-x11
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
EOF

cat > "$ROOTFS/etc/systemd/system/podroid-resize.service" <<'EOF'
[Unit]
Description=Podroid terminal resize daemon (hvc1 -> stty hvc0)
After=podroid-bootstrap.service
[Service]
ExecStart=/usr/local/sbin/podroid-resize
Restart=on-failure
[Install]
WantedBy=multi-user.target
EOF

# podroid-ready runs last — orders after the services whose readiness the
# "Ready!" marker actually means, then echoes the markers to /dev/console.
cat > "$ROOTFS/etc/systemd/system/podroid-ready.service" <<'EOF'
[Unit]
Description=Podroid ready marker (consumed by the Android boot detector)
After=podroid-network.service podroid-x11.service ssh.service
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/podroid-ready
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
EOF

# getty on the virtio-console the Android terminal bridge attaches to, with
# the winsize-restoring login wrapper. systemd templates serial-getty@.
mkdir -p "$ROOTFS/etc/systemd/system/serial-getty@hvc0.service.d"
cat > "$ROOTFS/etc/systemd/system/serial-getty@hvc0.service.d/podroid.conf" <<'EOF'
[Service]
ExecStart=
ExecStart=-/sbin/agetty -o '-p -- \\u' -n -l /usr/local/bin/podroid-login -L 115200 %I xterm-256color
EOF

# ── Enable units (build host is x86_64 — symlink directly, no systemctl) ────
WANTS="$ROOTFS/etc/systemd/system/multi-user.target.wants"
SYSINIT="$ROOTFS/etc/systemd/system/sysinit.target.wants"
mkdir -p "$WANTS" "$SYSINIT"
ln -sf /etc/systemd/system/podroid-bootstrap.service "$SYSINIT/podroid-bootstrap.service"
for u in podroid-network podroid-x11 podroid-resize podroid-ready; do
    ln -sf "/etc/systemd/system/$u.service" "$WANTS/$u.service"
done
ln -sf /lib/systemd/system/ssh.service               "$WANTS/ssh.service"
ln -sf /lib/systemd/system/serial-getty@.service     "$WANTS/serial-getty@hvc0.service"
# Don't block boot waiting on a real RTC / time sync, or on swap units.
in_rootfs "systemctl mask systemd-timesyncd.service systemd-random-seed.service 2>/dev/null" || true

# ── profile.d (truecolor + X11 env) ─────────────────────────────────────────
mkdir -p "$ROOTFS/etc/profile.d"
cp /work/files/etc/profile.d/podroid-color.sh "$ROOTFS/etc/profile.d/"
cp /work/files/etc/profile.d/podroid-x11.sh   "$ROOTFS/etc/profile.d/"

# ── Login banner ────────────────────────────────────────────────────────────
cat > "$ROOTFS/etc/issue" <<'EOF'
Welcome to Podroid NetHunter (Kali Linux)
Kernel \r on \m (\l)

  Default login:  root / kali
  Install tools:  apt update && apt install <pkg>
  Full toolset:   apt install kali-linux-default

EOF

# ── Slim the image ──────────────────────────────────────────────────────────
rm -rf "$ROOTFS/usr/share/man" "$ROOTFS/usr/share/doc" \
       "$ROOTFS/usr/share/locale" "$ROOTFS/usr/share/info" \
       "$ROOTFS/var/lib/apt/lists/"* "$ROOTFS/var/cache/apt/archives/"*.deb
# qemu-user shim was only needed for the cross-arch build steps.
rm -f "$ROOTFS/usr/bin/qemu-aarch64-static"

# Detach the chroot pseudo-filesystems before mksquashfs runs.
umount -lR "$ROOTFS/proc" "$ROOTFS/sys" "$ROOTFS/dev" 2>/dev/null || true

echo "Kali NetHunter rootfs built."
