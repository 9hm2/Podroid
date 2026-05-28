# build-rootfs/arch/Dockerfile
#
# Builds an Arch Linux ARM aarch64 rootfs squashfs for Podroid.
# Arch upstream doesn't officially ship arm64; we use Arch Linux ARM
# (archlinuxarm.org) which is the de-facto Arch flavour for ARM SBCs.
# systemd is PID 1.
#
# Build context is the build-rootfs/ root.

FROM --platform=$BUILDPLATFORM alpine:3.23 AS shared-builder
RUN apk add --no-cache musl-dev gcc make linux-headers
WORKDIR /work
COPY _shared/vsock-agent /work/vsock-agent
RUN cd /work/vsock-agent && make CC=gcc
COPY _shared/host-bridge /work/host-bridge
RUN cd /work/host-bridge && make CC=gcc

# Debian host gives us qemu-user-static + the standard tools for the
# extract+chroot pattern. Same approach as debootstrap, just with a
# pre-built tarball instead of an apt-based bootstrap.
FROM debian:bookworm AS builder

ARG ALARM_MIRROR=http://os.archlinuxarm.org/os
ARG ALARM_TARBALL=ArchLinuxARM-aarch64-latest.tar.gz
ARG ROOTFS_PASSWORD=podroid

RUN apt-get update && apt-get install -y --no-install-recommends \
        qemu-user-static binfmt-support ca-certificates wget \
        squashfs-tools openssl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /work

# Fetch + extract the Arch Linux ARM stock rootfs.
RUN wget --tries=5 --retry-connrefused --waitretry=10 -q \
        ${ALARM_MIRROR}/${ALARM_TARBALL} \
 && mkdir rootfs \
 && tar -xpzf ${ALARM_TARBALL} -C rootfs \
 && rm -f ${ALARM_TARBALL}

# Refresh pacman keyring inside the chroot via qemu-user. The ALARM
# tarball ships a keyring but we install a couple of extra packages
# (TigerVNC, PulseAudio) the base set doesn't include.
RUN cp /usr/bin/qemu-aarch64-static /work/rootfs/usr/bin/ \
 && cp /etc/resolv.conf /work/rootfs/etc/resolv.conf

RUN echo 'exit 101' > /work/rootfs/usr/sbin/policy-rc.d \
 && chmod +x /work/rootfs/usr/sbin/policy-rc.d \
 && chroot /work/rootfs sh -c ' \
        set -e; \
        # Bootstrap pacman keyring; on a fresh chroot it has to be initialised
        # before any package install will verify signatures.
        pacman-key --init && \
        pacman-key --populate archlinuxarm && \
        # Pacman 7+ wants a Landlock sandbox per download user; GitHub
        # runners ship a kernel without Landlock support and the alpm
        # user does not exist in the chroot either. Switch the sandbox
        # off globally for this build via pacman.conf so the flag does
        # not need repeating on every pacman invocation.
        sed -i "s/^#\\?NoProgressBar/NoProgressBar/; \
                s/^#\\?DisableSandbox/DisableSandbox/" /etc/pacman.conf; \
        grep -q "^DisableSandbox" /etc/pacman.conf || \
            echo "DisableSandbox" >> /etc/pacman.conf; \
        pacman -Syu --noconfirm --disable-sandbox && \
        pacman -S --noconfirm --needed --disable-sandbox \
            base systemd systemd-sysvcompat dbus \
            openssh sudo \
            iproute2 iputils inetutils \
            iw wireless_tools rfkill wpa_supplicant dhcpcd \
            usbutils pciutils kmod bash-completion \
            less nano tmux screen \
            tigervnc pulseaudio pulseaudio-zeroconf \
            ca-certificates; \
    ' \
 && rm -f /work/rootfs/usr/sbin/policy-rc.d

COPY --from=shared-builder /work/vsock-agent/podroid-vsock-agent /work/rootfs/usr/local/bin/podroid-vsock-agent
COPY --from=shared-builder /work/host-bridge/podroid-hostd       /work/rootfs/usr/local/bin/podroid-hostd

ENV PODROID_DISTRO=arch
ENV PODROID_DISTRO_VERSION=rolling
COPY _shared/files        /work/files
COPY _shared/init/systemd /work/systemd-init
COPY arch/build.sh        /work/build.sh
RUN chmod +x /work/build.sh && \
    ROOTFS_PASSWORD="${ROOTFS_PASSWORD}" /work/build.sh

RUN mksquashfs /work/rootfs /work/rootfs.squashfs \
        -comp zstd -Xcompression-level 19 -all-root -noappend

FROM scratch AS export
COPY --from=builder /work/rootfs.squashfs /rootfs.squashfs
