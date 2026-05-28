# build-rootfs/debian/Dockerfile
#
# Builds a Debian aarch64 rootfs squashfs for Podroid. systemd is PID 1; the
# small in-image core toolset comes from debootstrap --include, everything
# else the user `apt install`s onto the persistent ext4 overlay.
#
# Multi-version build: DEBIAN_SUITE picks the codename (bookworm, trixie,
# sid…). The CI workflow builds the current stable by default; older
# versions can be requested via the workflow input.
#
# Build context is the build-rootfs/ root; this file lives at
# build-rootfs/debian/Dockerfile.

# ── Stage 1: cross-compile the shared C agents (musl-static aarch64) ─────────
FROM --platform=linux/arm64 alpine:3.23 AS shared-builder
RUN apk add --no-cache musl-dev gcc make linux-headers
WORKDIR /work
COPY _shared/vsock-agent /work/vsock-agent
RUN cd /work/vsock-agent && make CC=gcc
COPY _shared/host-bridge /work/host-bridge
RUN cd /work/host-bridge && make CC=gcc

# ── Stage 2: debootstrap the Debian rootfs ──────────────────────────────────
FROM debian:bookworm AS builder

ARG DEBIAN_SUITE=trixie
ARG DEBIAN_MIRROR=http://deb.debian.org/debian
ARG ROOTFS_PASSWORD=podroid

RUN apt-get update && apt-get install -y --no-install-recommends \
        debootstrap qemu-user-static binfmt-support \
        ca-certificates wget gnupg openssl squashfs-tools \
 && rm -rf /var/lib/apt/lists/*

# Debian bookworm's debootstrap 1.0.128 doesn't know about Debian releases
# newer than itself (trixie/13, forky/14). All current Debian releases share
# the same minimal debootstrap script as 'sid', so plant the symlinks. Saves
# pinning a newer debootstrap from backports just for the suite name lookup.
RUN cd /usr/share/debootstrap/scripts \
 && for suite in trixie forky; do \
        [ -e "$suite" ] || ln -sf sid "$suite"; \
    done

WORKDIR /work

# Two-stage debootstrap (foreign + second-stage with qemu-user) is required
# because docker build is unprivileged — we can't bind-mount /proc/sys for
# a single-pass chroot. binfmt registration relies on the host (see
# docker/setup-qemu-action in CI). Retry loop guards against transient
# mirror 504s; up to 3 attempts with escalating wait.
RUN set -e; \
    for attempt in 1 2 3; do \
        echo "=== debootstrap attempt $attempt ==="; \
        rm -rf /work/rootfs; \
        if debootstrap --arch=arm64 --foreign --variant=minbase \
                --components=main,contrib,non-free,non-free-firmware \
                --include=systemd-sysv,udev,dbus,libpam-systemd,debian-archive-keyring,ca-certificates,openssh-server,sudo,iproute2,iputils-ping,net-tools,isc-dhcp-client,iw,wireless-tools,rfkill,wpasupplicant,usbutils,pciutils,kmod,bash-completion,less,nano,tmux,screen,tigervnc-standalone-server,tigervnc-common,pulseaudio,pulseaudio-utils \
                ${DEBIAN_SUITE} /work/rootfs ${DEBIAN_MIRROR}; then \
            echo "=== debootstrap succeeded on attempt $attempt ==="; \
            break; \
        fi; \
        echo "=== attempt $attempt failed; sleeping $((attempt * 10))s ==="; \
        sleep $((attempt * 10)); \
        [ "$attempt" = "3" ] && { echo "FATAL: debootstrap failed 3×"; exit 1; }; \
    done
RUN cp /usr/bin/qemu-aarch64-static /work/rootfs/usr/bin/ \
 && chroot /work/rootfs /debootstrap/debootstrap --second-stage

# Bring in the shared agents built in stage 1.
COPY --from=shared-builder /work/vsock-agent/podroid-vsock-agent /work/rootfs/usr/local/bin/podroid-vsock-agent
COPY --from=shared-builder /work/host-bridge/podroid-hostd       /work/rootfs/usr/local/bin/podroid-hostd

ENV PODROID_DISTRO=debian
ENV PODROID_DISTRO_VERSION=${DEBIAN_SUITE}
COPY _shared/files        /work/files
COPY _shared/init/systemd /work/systemd-init
COPY debian/build.sh      /work/build.sh
RUN chmod +x /work/build.sh && \
    ROOTFS_PASSWORD="${ROOTFS_PASSWORD}" /work/build.sh

RUN mksquashfs /work/rootfs /work/rootfs.squashfs \
        -comp zstd -Xcompression-level 19 -all-root -noappend

FROM scratch AS export
COPY --from=builder /work/rootfs.squashfs /rootfs.squashfs
