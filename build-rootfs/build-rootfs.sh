#!/bin/sh
# Kali NetHunter rootfs configuration — runs in the Dockerfile.rootfs builder
# stage against the debootstrapped Kali tree at /work/rootfs. systemd is PID 1.
#
# The core package set is installed by `debootstrap --include` (see
# Dockerfile.rootfs); this script is therefore pure file manipulation — no
# mount, no chroot — so it runs inside an unprivileged `docker build` RUN.
# The rest of the Kali toolset is `apt install`-ed inside the VM at runtime.
set -eu
ROOTFS=/work/rootfs

# ── APT: Kali repo + signing key in the proper places ───────────────────────
install -Dm644 /work/kali-archive-keyring.gpg \
    "$ROOTFS/usr/share/keyrings/kali-archive-keyring.gpg"
cat > "$ROOTFS/etc/apt/sources.list" <<'EOF'
# Kali Linux rolling — verified against the keyring Podroid installed.
deb [signed-by=/usr/share/keyrings/kali-archive-keyring.gpg] http://http.kali.org/kali kali-rolling main contrib non-free non-free-firmware
EOF
cat > "$ROOTFS/etc/apt/apt.conf.d/99podroid" <<'EOF'
APT::Install-Recommends "false";
APT::Install-Suggests "false";
Acquire::Languages "none";
EOF

# ── Accounts ────────────────────────────────────────────────────────────────
# Kali NetHunter convention: root / kali. We can't run chpasswd in the
# aarch64 rootfs from an x86_64 build host, so write the SHA-512 hash directly
# (random salt → the stored hash differs per build, password stays "kali").
ROOT_HASH=$(openssl passwd -6 kali)
sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"
echo "podroid" > "$ROOTFS/etc/hostname"
cat > "$ROOTFS/etc/hosts" <<'EOF'
127.0.0.1 localhost podroid
::1 localhost ip6-localhost
EOF

# ── Podroid VM bringup — systemd units + scripts ────────────────────────────
# Bringup logic ported verbatim from the OpenRC services; systemd just orders
# it. Each script is a plain shell program run by a oneshot/forking unit.
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

# --- resize daemon + winsize-restoring login wrapper (reused as-is) ---------
cp /work/files/usr/local/bin/podroid-resize "$ROOTFS/usr/local/sbin/podroid-resize"
cp /work/files/usr/local/bin/podroid-login  "$ROOTFS/usr/local/bin/podroid-login"
# podroid-ai: VM-side curl wrapper that hits the Android llama-server over
# SLIRP loopback (10.0.2.2). Depends on curl + jq (in --include).
cp /work/files/usr/local/bin/podroid-ai     "$ROOTFS/usr/local/bin/podroid-ai"
# podroid-install-ai: idempotent installer for Aider + shell-gpt; the user
# runs it once with `sudo podroid-install-ai` after the engine is up. Not
# auto-run at first boot — that would slow boot + need network at first
# login; an explicit `sudo` keeps the failure mode obvious.
cp /work/files/usr/local/bin/podroid-install-ai "$ROOTFS/usr/local/bin/podroid-install-ai"
# podroid-agent: minimal Python-based agent (Crush-style UX, ~500-token
# system prompt) bundled directly so it works on every fresh VM without
# downloading anything.
cp /work/files/usr/local/bin/podroid-agent      "$ROOTFS/usr/local/bin/podroid-agent"
chmod +x "$ROOTFS/usr/local/sbin/podroid-"* \
         "$ROOTFS/usr/local/bin/podroid-login" \
         "$ROOTFS/usr/local/bin/podroid-ai" \
         "$ROOTFS/usr/local/bin/podroid-install-ai" \
         "$ROOTFS/usr/local/bin/podroid-agent"

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

# getty on the virtio-consoles the Android terminal bridge attaches to, with
# the winsize-restoring login wrapper. The override is template-wide
# (serial-getty@.service.d/) so it applies to every enabled instance —
# hvc0 (primary tab), hvc2 (tab 2), hvc3 (tab 3).
mkdir -p "$ROOTFS/etc/systemd/system/serial-getty@.service.d"
cat > "$ROOTFS/etc/systemd/system/serial-getty@.service.d/podroid.conf" <<'EOF'
[Service]
# Autologin as root on every Podroid tty — the VM is a single-user dev
# environment that boots into a privileged shell instantly. agetty -a root
# adds "-f root" to the login command line so /bin/login skips the
# password prompt. podroid-login stays in front to restore winsize
# (virtio-console resets it to 0x0 on every reopen).
ExecStart=
ExecStart=-/sbin/agetty -a root -l /usr/local/bin/podroid-login -L 115200 %I xterm-256color
EOF

# ── Enable units (build host is x86_64 — symlink directly, no systemctl) ────
WANTS="$ROOTFS/etc/systemd/system/multi-user.target.wants"
SYSINIT="$ROOTFS/etc/systemd/system/sysinit.target.wants"
GETTY="$ROOTFS/etc/systemd/system/getty.target.wants"
mkdir -p "$WANTS" "$SYSINIT" "$GETTY"
ln -sf /etc/systemd/system/podroid-bootstrap.service "$SYSINIT/podroid-bootstrap.service"
for u in podroid-network podroid-x11 podroid-resize podroid-ready; do
    ln -sf "/etc/systemd/system/$u.service" "$WANTS/$u.service"
done
ln -sf /lib/systemd/system/ssh.service           "$WANTS/ssh.service"

# gpsd: bound to the virtio-console GPS channel exposed by the Android GPS
# bridge (QemuEngine.gpsSockPath → /dev/hvc4 in the guest). gpsd parses the
# NMEA the bridge writes and serves clients (kismet, gpsmon, wifite) on
# TCP 2947. Listen on all addresses so peers on the host LAN can attach too.
mkdir -p "$ROOTFS/etc/default"
cat > "$ROOTFS/etc/default/gpsd" <<'EOF'
START_DAEMON="true"
USBAUTO="false"
DEVICES="/dev/hvc4"
GPSD_OPTIONS="-n -G"
EOF
ln -sf /lib/systemd/system/gpsd.service          "$WANTS/gpsd.service"
ln -sf /lib/systemd/system/gpsd.socket           "$WANTS/gpsd.socket"
# Three serial gettys for the planned 3-tab in-app terminal. The bridge on
# the Android side connects each tab to terminal.sock / term1.sock /
# term2.sock; QEMU exposes those as hvc0 / hvc2 / hvc3 in the VM (hvc1 is
# the resize control channel — no getty).
for _hvc in hvc0 hvc2 hvc3; do
    ln -sf /lib/systemd/system/serial-getty@.service \
        "$GETTY/serial-getty@${_hvc}.service"
done

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
  AI assistants:  sudo podroid-install-ai   (Aider, shell-gpt, podroid-ai)

EOF

# ── Slim the image ──────────────────────────────────────────────────────────
rm -rf "$ROOTFS/usr/share/man" "$ROOTFS/usr/share/doc" \
       "$ROOTFS/usr/share/locale" "$ROOTFS/usr/share/info" \
       "$ROOTFS/var/lib/apt/lists/"* "$ROOTFS/var/cache/apt/archives/"*.deb
# qemu-user shim was only needed for the cross-arch debootstrap second stage.
rm -f "$ROOTFS/usr/bin/qemu-aarch64-static"

echo "Kali NetHunter rootfs configured."
