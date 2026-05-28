# build-rootfs/parrot/Dockerfile
#
# Builds a Parrot OS aarch64 rootfs squashfs for Podroid. Same pentest
# focus as Kali but Debian-stable-based; debootstrap + the parrot mirror.
#
# Build context is the build-rootfs/ root.

FROM --platform=$BUILDPLATFORM alpine:3.23 AS shared-builder
RUN apk add --no-cache musl-dev gcc make linux-headers
WORKDIR /work
COPY _shared/vsock-agent /work/vsock-agent
RUN cd /work/vsock-agent && make CC=gcc
COPY _shared/host-bridge /work/host-bridge
RUN cd /work/host-bridge && make CC=gcc

FROM debian:bookworm AS builder

# 'lory' is Parrot 6.x rolling alias, 'parrot' is the latest stable
# release codename. Default to lory because that's what most users want.
ARG PARROT_SUITE=lory
ARG PARROT_MIRROR=https://deb.parrot.sh/parrot
ARG ROOTFS_PASSWORD=parrot

RUN apt-get update && apt-get install -y --no-install-recommends \
        debootstrap qemu-user-static binfmt-support \
        ca-certificates wget gnupg openssl squashfs-tools \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /work

# Parrot archive signing key. The official location has moved a few times
# (archive.parrotsec.org/misc → deb.parrot.sh/...); rather than hunting it
# down on every build, skip the bootstrap-time signature check and let the
# keyring land via --include parrot-archive-keyring instead. The Release
# file inside the VM is still signature-verified by apt the moment the
# keyring is dpkg-configured — only the cross-arch foreign first stage
# runs unsigned, and on a CI runner the wire is already trusted.
#
# Try a couple of known URLs first as a best-effort; on failure we just
# proceed without --keyring (debootstrap --no-check-gpg).
RUN set +e; \
    for url in \
        https://deb.parrot.sh/parrot/misc/parrotsec.gpg \
        https://archive.parrotsec.org/parrot/misc/parrotsec.gpg \
        https://deb.parrot.sh/parrot/parrot-archive-keyring.gpg; do \
        echo "trying parrot keyring at $url"; \
        if wget --tries=2 --waitretry=2 -q -O /tmp/p.gpg "$url" && \
           [ -s /tmp/p.gpg ] && \
           gpg --dearmor < /tmp/p.gpg > /work/parrot-archive-keyring.gpg 2>/dev/null && \
           [ -s /work/parrot-archive-keyring.gpg ]; then \
            echo "got parrot keyring from $url"; \
            break; \
        fi; \
        rm -f /work/parrot-archive-keyring.gpg; \
    done; \
    if [ -s /work/parrot-archive-keyring.gpg ]; then \
        echo "PARROT_USE_KEYRING=1" > /work/parrot.env; \
    else \
        echo "WARN: no parrot keyring found — bootstrapping unsigned (apt inside VM stays signature-checked)"; \
        echo "PARROT_USE_KEYRING=0" > /work/parrot.env; \
    fi; \
    exit 0

# Plant a 'parrot' symlink to debian's 'sid' script — debootstrap on
# bookworm doesn't know parrot suite names.
RUN cd /usr/share/debootstrap/scripts \
 && for suite in lory parrot rolling stable; do \
        [ -e "$suite" ] || ln -sf sid "$suite"; \
    done

RUN . /work/parrot.env; \
    if [ "$PARROT_USE_KEYRING" = "1" ]; then \
        DEBOOT_KEYRING_ARG="--keyring=/work/parrot-archive-keyring.gpg"; \
    else \
        DEBOOT_KEYRING_ARG="--no-check-gpg"; \
    fi; \
    set -e; \
    for attempt in 1 2 3; do \
        echo "=== debootstrap attempt $attempt ($DEBOOT_KEYRING_ARG) ==="; \
        rm -rf /work/rootfs; \
        if debootstrap --arch=arm64 --foreign --variant=minbase \
                $DEBOOT_KEYRING_ARG \
                --components=main,contrib,non-free,non-free-firmware \
                --include=systemd-sysv,udev,dbus,libpam-systemd,parrot-archive-keyring,ca-certificates,openssh-server,sudo,iproute2,iputils-ping,net-tools,isc-dhcp-client,iw,wireless-tools,rfkill,wpasupplicant,usbutils,pciutils,kmod,bash-completion,less,nano,tmux,screen \
                ${PARROT_SUITE} /work/rootfs ${PARROT_MIRROR}; then \
            echo "=== debootstrap succeeded on attempt $attempt ==="; \
            break; \
        fi; \
        echo "=== attempt $attempt failed; sleeping $((attempt * 10))s ==="; \
        sleep $((attempt * 10)); \
        [ "$attempt" = "3" ] && { echo "FATAL: debootstrap failed 3×"; exit 1; }; \
    done

RUN cp /usr/bin/qemu-aarch64-static /work/rootfs/usr/bin/ \
 && echo 'exit 101' > /work/rootfs/usr/sbin/policy-rc.d \
 && chmod +x /work/rootfs/usr/sbin/policy-rc.d \
 && { chroot /work/rootfs sh -c 'export DEBIAN_FRONTEND=noninteractive LC_ALL=C; /debootstrap/debootstrap --second-stage'; \
      rc=$?; \
      if [ $rc -ne 0 ]; then \
          echo "=================== debootstrap.log ==================="; \
          tail -n 200 /work/rootfs/debootstrap/debootstrap.log 2>/dev/null || true; \
          exit $rc; \
      fi; } \
 && rm -f /work/rootfs/usr/sbin/policy-rc.d

COPY --from=shared-builder /work/vsock-agent/podroid-vsock-agent /work/rootfs/usr/local/bin/podroid-vsock-agent
COPY --from=shared-builder /work/host-bridge/podroid-hostd       /work/rootfs/usr/local/bin/podroid-hostd

ENV PODROID_DISTRO=parrot
ENV PODROID_DISTRO_VERSION=${PARROT_SUITE}
COPY _shared/files        /work/files
COPY _shared/init/systemd /work/systemd-init
COPY parrot/build.sh      /work/build.sh
RUN chmod +x /work/build.sh && \
    ROOTFS_PASSWORD="${ROOTFS_PASSWORD}" /work/build.sh

RUN mksquashfs /work/rootfs /work/rootfs.squashfs \
        -comp zstd -Xcompression-level 19 -all-root -noappend

FROM scratch AS export
COPY --from=builder /work/rootfs.squashfs /rootfs.squashfs
