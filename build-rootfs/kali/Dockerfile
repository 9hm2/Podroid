# build-rootfs/kali/Dockerfile
#
# Builds the Kali Linux (rolling) aarch64 rootfs squashfs for Podroid.
# systemd is PID 1; only a small core toolset ships in the image,
# everything else (kali-linux-default, the full pentest stack) is
# `apt install`-ed at runtime onto the persistent ext4 overlay.
#
# Build context is the build-rootfs/ root.

# ── Stage 1: cross-compile the shared C agents (musl-static aarch64) ─────────
FROM --platform=$BUILDPLATFORM alpine:3.23 AS shared-builder
RUN apk add --no-cache musl-dev gcc make linux-headers
WORKDIR /work
COPY _shared/vsock-agent /work/vsock-agent
RUN cd /work/vsock-agent && make CC=gcc
COPY _shared/host-bridge /work/host-bridge
RUN cd /work/host-bridge && make CC=gcc

# ── Stage 2: debootstrap the Kali rootfs ────────────────────────────────────
FROM debian:bookworm AS builder

ARG KALI_SUITE=kali-rolling
# kali.download is the official CloudFront-backed CDN. Far more reliable
# under CI load than the round-robin http.kali.org pool, which has been
# flaking on GitHub runners with intermittent 504s on libc6 / libstdc++6.
ARG KALI_MIRROR=http://kali.download/kali
ARG ROOTFS_PASSWORD=kali

RUN apt-get update && apt-get install -y --no-install-recommends \
        debootstrap qemu-user-static binfmt-support \
        ca-certificates wget gnupg openssl squashfs-tools \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /work

# Kali archive signing key. debootstrap verifies the repo Release file with
# it here, and build.sh drops the same dearmored keyring into the rootfs at
# /usr/share/keyrings/ so the VM's apt trusts kali-rolling.
RUN wget -qO kali-key.asc https://archive.kali.org/archive-key.asc \
 && gpg --dearmor < kali-key.asc > /work/kali-archive-keyring.gpg

# Retry loop because the Kali mirror occasionally drops a single package
# fetch (HTTP 504 on transient packages like libatomic1 / gcc-base).
RUN set -e; \
    for attempt in 1 2 3; do \
        echo "=== debootstrap attempt $attempt ==="; \
        rm -rf /work/rootfs; \
        if debootstrap --arch=arm64 --foreign --variant=minbase \
                --keyring=/work/kali-archive-keyring.gpg \
                --components=main,contrib,non-free,non-free-firmware \
                --include=systemd-sysv,udev,dbus,libpam-systemd,kali-archive-keyring,ca-certificates,openssh-server,sudo,iproute2,iputils-ping,net-tools,isc-dhcp-client,iw,wireless-tools,rfkill,wpasupplicant,aircrack-ng,usbutils,pciutils,kmod,bash-completion,less,nano,tmux,screen,gpsd,tigervnc-standalone-server,tigervnc-common,pulseaudio,pulseaudio-utils \
                ${KALI_SUITE} /work/rootfs ${KALI_MIRROR}; then \
            echo "=== debootstrap succeeded on attempt $attempt ==="; \
            break; \
        fi; \
        echo "=== attempt $attempt failed; sleeping $((attempt * 10))s ==="; \
        sleep $((attempt * 10)); \
        [ "$attempt" = "3" ] && { echo "FATAL: debootstrap failed 3×"; exit 1; }; \
    done

# Same policy-rc.d + dump-log hardening pattern Ubuntu uses (Kali postinsts
# trip on the same chroot-no-PID-1 invoke-rc.d issue).
RUN cp /usr/bin/qemu-aarch64-static /work/rootfs/usr/bin/ \
 && echo 'exit 101' > /work/rootfs/usr/sbin/policy-rc.d \
 && chmod +x /work/rootfs/usr/sbin/policy-rc.d \
 && { chroot /work/rootfs sh -c 'export DEBIAN_FRONTEND=noninteractive LC_ALL=C; /debootstrap/debootstrap --second-stage'; \
      rc=$?; \
      if [ $rc -ne 0 ]; then \
          echo "=================== debootstrap.log ==================="; \
          tail -n 200 /work/rootfs/debootstrap/debootstrap.log 2>/dev/null || true; \
          echo "======================================================="; \
          exit $rc; \
      fi; } \
 && rm -f /work/rootfs/usr/sbin/policy-rc.d

COPY --from=shared-builder /work/vsock-agent/podroid-vsock-agent /work/rootfs/usr/local/bin/podroid-vsock-agent
COPY --from=shared-builder /work/host-bridge/podroid-hostd       /work/rootfs/usr/local/bin/podroid-hostd

ENV PODROID_DISTRO=kali
ENV PODROID_DISTRO_VERSION=${KALI_SUITE}
COPY _shared/files        /work/files
COPY _shared/init/systemd /work/systemd-init
COPY kali/build.sh        /work/build.sh
RUN chmod +x /work/build.sh && \
    ROOTFS_PASSWORD="${ROOTFS_PASSWORD}" /work/build.sh

RUN mksquashfs /work/rootfs /work/rootfs.squashfs \
        -comp zstd -Xcompression-level 19 -all-root -noappend

FROM scratch AS export
COPY --from=builder /work/rootfs.squashfs /rootfs.squashfs
