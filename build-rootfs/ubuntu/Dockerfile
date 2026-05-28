# build-rootfs/ubuntu/Dockerfile
#
# Builds an Ubuntu aarch64 rootfs squashfs for Podroid. systemd is PID 1.
# Same debootstrap-based pipeline as Debian, just pointed at Ubuntu's ports
# mirror (the main archive doesn't carry arm64 — it's all in ports.ubuntu.com).
#
# Multi-version build: UBUNTU_SUITE picks the release codename
# (noble = 24.04 LTS, oracular = 24.10, plucky = 25.04, jammy = 22.04 LTS).
#
# Build context is the build-rootfs/ root.

# ── Stage 1: cross-compile the shared C agents (musl-static aarch64) ─────────
FROM --platform=linux/arm64 alpine:3.23 AS shared-builder
RUN apk add --no-cache musl-dev gcc make linux-headers
WORKDIR /work
COPY _shared/vsock-agent /work/vsock-agent
RUN cd /work/vsock-agent && make CC=gcc
COPY _shared/host-bridge /work/host-bridge
RUN cd /work/host-bridge && make CC=gcc

# ── Stage 2: debootstrap the Ubuntu rootfs ──────────────────────────────────
# Use Debian as the bootstrap host so debootstrap has the qemu-user-static
# binfmt registration the same way as the Debian/Kali builds.
FROM debian:bookworm AS builder

ARG UBUNTU_SUITE=noble
ARG UBUNTU_MIRROR=http://ports.ubuntu.com/ubuntu-ports
ARG ROOTFS_PASSWORD=podroid

RUN apt-get update && apt-get install -y --no-install-recommends \
        debootstrap qemu-user-static binfmt-support \
        ca-certificates wget gnupg openssl squashfs-tools \
 && rm -rf /var/lib/apt/lists/*

# Debian bookworm's debootstrap 1.0.128 predates every Ubuntu release after
# jammy. All newer Ubuntu suites share the same minimal debootstrap script
# as 'gutsy' (the Ubuntu base script everyone else symlinks to anyway in
# upstream debootstrap), so plant the symlinks ourselves. Saves us from
# pinning a newer debootstrap from backports / sid. Covers noble (24.04 LTS),
# oracular (24.10), plucky (25.04), questing (25.10).
RUN cd /usr/share/debootstrap/scripts \
 && for suite in noble oracular plucky questing; do \
        [ -e "$suite" ] || ln -sf gutsy "$suite"; \
    done

WORKDIR /work

# Ubuntu archive signing key — required by debootstrap when using the
# foreign mirror at first-stage time. ubuntu-keyring is missing from the
# Debian package archive, so fetch the .gpg directly.
RUN wget -qO ubuntu-key.gpg \
    https://salsa.debian.org/debian/ubuntu-keyring/-/raw/master/keyrings/ubuntu-archive-keyring.gpg \
 && mv ubuntu-key.gpg /work/ubuntu-archive-keyring.gpg

RUN set -e; \
    for attempt in 1 2 3; do \
        echo "=== debootstrap attempt $attempt ==="; \
        rm -rf /work/rootfs; \
        if debootstrap --arch=arm64 --foreign --variant=minbase \
                --keyring=/work/ubuntu-archive-keyring.gpg \
                --components=main,universe,multiverse,restricted \
                --include=systemd-sysv,udev,dbus,libpam-systemd,ubuntu-keyring,ca-certificates,openssh-server,sudo,iproute2,iputils-ping,net-tools,isc-dhcp-client,iw,wireless-tools,rfkill,wpasupplicant,usbutils,pciutils,kmod,bash-completion,less,nano,tmux,screen,tigervnc-standalone-server,tigervnc-common,pulseaudio,pulseaudio-utils \
                ${UBUNTU_SUITE} /work/rootfs ${UBUNTU_MIRROR}; then \
            echo "=== debootstrap succeeded on attempt $attempt ==="; \
            break; \
        fi; \
        echo "=== attempt $attempt failed; sleeping $((attempt * 10))s ==="; \
        sleep $((attempt * 10)); \
        [ "$attempt" = "3" ] && { echo "FATAL: debootstrap failed 3×"; exit 1; }; \
    done

# Second-stage with the standard chroot-friendliness fixes — Ubuntu noble's
# package postinsts are stricter than Debian's and bail when invoke-rc.d
# tries to start systemctl-managed services inside an unprivileged chroot.
#   - policy-rc.d returns 101 → invoke-rc.d treats every service start as
#     denied without erroring (the chroot has no PID 1 to talk to).
#   - DEBIAN_FRONTEND=noninteractive + LC_ALL=C → debconf uses its
#     non-interactive frontend and locale doesn't matter.
RUN cp /usr/bin/qemu-aarch64-static /work/rootfs/usr/bin/ \
 && echo 'exit 101' > /work/rootfs/usr/sbin/policy-rc.d \
 && chmod +x /work/rootfs/usr/sbin/policy-rc.d \
 && chroot /work/rootfs sh -c 'export DEBIAN_FRONTEND=noninteractive LC_ALL=C; /debootstrap/debootstrap --second-stage' \
 && rm -f /work/rootfs/usr/sbin/policy-rc.d

COPY --from=shared-builder /work/vsock-agent/podroid-vsock-agent /work/rootfs/usr/local/bin/podroid-vsock-agent
COPY --from=shared-builder /work/host-bridge/podroid-hostd       /work/rootfs/usr/local/bin/podroid-hostd

ENV PODROID_DISTRO=ubuntu
ENV PODROID_DISTRO_VERSION=${UBUNTU_SUITE}
COPY _shared/files        /work/files
COPY _shared/init/systemd /work/systemd-init
COPY ubuntu/build.sh      /work/build.sh
RUN chmod +x /work/build.sh && \
    ROOTFS_PASSWORD="${ROOTFS_PASSWORD}" /work/build.sh

RUN mksquashfs /work/rootfs /work/rootfs.squashfs \
        -comp zstd -Xcompression-level 19 -all-root -noappend

FROM scratch AS export
COPY --from=builder /work/rootfs.squashfs /rootfs.squashfs
