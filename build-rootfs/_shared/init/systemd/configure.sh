#!/bin/sh
# Shared systemd-based Podroid VM bringup configuration.
#
# Sourced from each distro's build.sh AFTER it has:
#   - debootstrapped (or otherwise extracted) the rootfs at $ROOTFS
#   - configured its apt/yum/pacman sources and signing keys
#   - installed the core package set (systemd-sysv, dbus, openssh, etc.)
#   - set the root password and /etc/hostname
#
# This script then:
#   - drops the podroid-* shell scripts under /usr/local/sbin
#   - generates the systemd unit files
#   - enables them via .wants symlinks (no chroot/systemctl — build host is x86)
#   - configures gpsd and the serial-getty autologin
#   - copies profile.d truecolor + X11 envs
#   - prunes locale/man/doc to slim the squashfs
#
# Expects these env vars to be set by the caller:
#   ROOTFS         : path to the rootfs tree (e.g. /work/rootfs)
#   FILES_DIR      : path to per-distro extra files (e.g. /work/files)
#   SSH_UNIT_NAME  : systemd unit name for the SSH server (debian: ssh.service,
#                    ubuntu: ssh.service, kali: ssh.service, fedora: sshd.service,
#                    arch: sshd.service). Default: ssh.service.
set -eu

: "${ROOTFS:?ROOTFS env var must point at the rootfs tree}"
: "${FILES_DIR:?FILES_DIR env var must point at the per-distro files dir}"
: "${SSH_UNIT_NAME:=ssh.service}"

# ── Podroid VM bringup — systemd units + scripts ────────────────────────────
# Logic ported from the OpenRC services on Alpine; systemd just orders it.
mkdir -p "$ROOTFS/usr/local/sbin" "$ROOTFS/usr/local/bin" \
         "$ROOTFS/etc/systemd/system"

# --- podroid-bootstrap: cgroups, /dev mounts, ZRAM, sysctl, scheduler -------
cat > "$ROOTFS/usr/local/sbin/podroid-bootstrap" <<'BOOT'
#!/bin/sh
echo "Loading kernel modules..." > /dev/console
mount --make-rshared / 2>/dev/null
mkdir -p /dev/pts /dev/shm /dev/mqueue /dev/net 2>/dev/null
mountpoint -q /dev/pts    || mount -t devpts devpts /dev/pts -o gid=5,mode=0620,ptmxmode=0666,noexec,nosuid
mountpoint -q /dev/shm    || mount -t tmpfs tmpfs /dev/shm -o noexec,nosuid,nodev,size=64m
mountpoint -q /dev/mqueue || mount -t mqueue mqueue /dev/mqueue -o noexec,nosuid,nodev
[ -r /etc/hostname ] && hostname -F /etc/hostname 2>/dev/null
depmod -a 2>/dev/null
for q in /sys/block/vda/queue/scheduler /sys/block/vdb/queue/scheduler; do
    [ -w "$q" ] && echo mq-deadline > "$q" 2>/dev/null
done
[ -c /dev/net/tun ] || mknod /dev/net/tun c 10 200 2>/dev/null
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

# --- resize daemon + winsize-restoring login wrapper -----------------------
cp "$FILES_DIR/usr/local/bin/podroid-resize" "$ROOTFS/usr/local/sbin/podroid-resize"
cp "$FILES_DIR/usr/local/bin/podroid-login"  "$ROOTFS/usr/local/bin/podroid-login"
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

cat > "$ROOTFS/etc/systemd/system/podroid-ready.service" <<EOF
[Unit]
Description=Podroid ready marker (consumed by the Android boot detector)
After=podroid-network.service podroid-x11.service ${SSH_UNIT_NAME}
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/podroid-ready
RemainAfterExit=yes
[Install]
WantedBy=multi-user.target
EOF

# Autologin on every Podroid tty so the user lands in a root shell instantly.
mkdir -p "$ROOTFS/etc/systemd/system/serial-getty@.service.d"
cat > "$ROOTFS/etc/systemd/system/serial-getty@.service.d/podroid.conf" <<'EOF'
[Service]
ExecStart=
ExecStart=-/sbin/agetty -a root -l /usr/local/bin/podroid-login -L 115200 %I xterm-256color
EOF

# ── Enable units (no systemctl — build host is x86, manual symlinks instead) ─
WANTS="$ROOTFS/etc/systemd/system/multi-user.target.wants"
SYSINIT="$ROOTFS/etc/systemd/system/sysinit.target.wants"
GETTY="$ROOTFS/etc/systemd/system/getty.target.wants"
mkdir -p "$WANTS" "$SYSINIT" "$GETTY"
ln -sf /etc/systemd/system/podroid-bootstrap.service "$SYSINIT/podroid-bootstrap.service"
for u in podroid-network podroid-x11 podroid-resize podroid-ready; do
    ln -sf "/etc/systemd/system/$u.service" "$WANTS/$u.service"
done
# SSH is in the distro-managed unit dir (/lib/systemd/system or /usr/lib/...).
# Try both — symlink whichever exists.
for ssh_src in "/lib/systemd/system/${SSH_UNIT_NAME}" \
               "/usr/lib/systemd/system/${SSH_UNIT_NAME}"; do
    if [ -f "$ROOTFS$ssh_src" ]; then
        ln -sf "$ssh_src" "$WANTS/${SSH_UNIT_NAME}"
        break
    fi
done

# gpsd: bound to the virtio-console GPS channel exposed by the Android GPS
# bridge (QemuEngine.gpsSockPath → /dev/hvc4). Only enable if the package
# was installed in this rootfs (gpsd is optional — Ubuntu minimal doesn't
# pull it in by default, debootstrap --include in the Dockerfile decides).
mkdir -p "$ROOTFS/etc/default"
cat > "$ROOTFS/etc/default/gpsd" <<'EOF'
START_DAEMON="true"
USBAUTO="false"
DEVICES="/dev/hvc4"
GPSD_OPTIONS="-n -G"
EOF
for gpsd_src in "/lib/systemd/system/gpsd.service" \
                "/usr/lib/systemd/system/gpsd.service"; do
    if [ -f "$ROOTFS$gpsd_src" ]; then
        ln -sf "$gpsd_src" "$WANTS/gpsd.service"
        # gpsd.socket gates the on-demand startup; pair them.
        socket_src="${gpsd_src%.service}.socket"
        [ -f "$ROOTFS$socket_src" ] && ln -sf "$socket_src" "$WANTS/gpsd.socket"
        break
    fi
done

# Three serial gettys for the in-app multi-tab terminal (hvc0/hvc2/hvc3).
# hvc1 is the resize control channel; hvc4 carries GPS NMEA — no getty there.
for _hvc in hvc0 hvc2 hvc3; do
    ln -sf /lib/systemd/system/serial-getty@.service \
        "$GETTY/serial-getty@${_hvc}.service"
done

# ── profile.d (truecolor + X11 env) ─────────────────────────────────────────
mkdir -p "$ROOTFS/etc/profile.d"
cp "$FILES_DIR/etc/profile.d/podroid-color.sh" "$ROOTFS/etc/profile.d/" 2>/dev/null || true
cp "$FILES_DIR/etc/profile.d/podroid-x11.sh"   "$ROOTFS/etc/profile.d/" 2>/dev/null || true

# ── Slim the image ──────────────────────────────────────────────────────────
rm -rf "$ROOTFS/usr/share/man" "$ROOTFS/usr/share/doc" \
       "$ROOTFS/usr/share/locale" "$ROOTFS/usr/share/info" \
       "$ROOTFS/var/lib/apt/lists/"* "$ROOTFS/var/cache/apt/archives/"*.deb \
       2>/dev/null || true
# qemu-user shim was only needed for the cross-arch debootstrap second stage.
rm -f "$ROOTFS/usr/bin/qemu-aarch64-static"
