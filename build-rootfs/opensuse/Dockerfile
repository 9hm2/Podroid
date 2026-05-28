# build-rootfs/opensuse/Dockerfile
#
# Builds an openSUSE Tumbleweed aarch64 rootfs squashfs for Podroid.
# Tumbleweed = rolling. systemd is PID 1.
#
# Build context is the build-rootfs/ root.

FROM --platform=$BUILDPLATFORM alpine:3.23 AS shared-builder
RUN apk add --no-cache musl-dev gcc make linux-headers
WORKDIR /work
COPY _shared/vsock-agent /work/vsock-agent
RUN cd /work/vsock-agent && make CC=gcc
COPY _shared/host-bridge /work/host-bridge
RUN cd /work/host-bridge && make CC=gcc

# Use the openSUSE Tumbleweed host image so we have zypper available.
FROM opensuse/tumbleweed AS builder

ARG OPENSUSE_FLAVOR=tumbleweed
ARG ROOTFS_PASSWORD=podroid

RUN zypper --non-interactive install \
        zypper rpm squashfs openssl wget \
 && zypper clean -a

WORKDIR /work

# zypper --installroot supports cross-arch installation; the aarch64
# RPMs land in /work/rootfs.
RUN mkdir -p /work/rootfs && \
    zypper --root /work/rootfs --non-interactive --gpg-auto-import-keys \
        addrepo \
        https://download.opensuse.org/ports/aarch64/tumbleweed/repo/oss/ \
        oss-aarch64 && \
    zypper --root /work/rootfs --non-interactive --gpg-auto-import-keys \
        --no-recommends \
        install --no-confirm \
            patterns-base-base \
            systemd systemd-sysvinit dbus-1 \
            openssh sudo shadow \
            iproute2 iputils net-tools wicked \
            iw wireless-tools rfkill wpa_supplicant \
            usbutils pciutils kmod bash-completion \
            less nano tmux screen \
            ca-certificates-mozilla glibc-locale-base && \
    zypper --root /work/rootfs clean -a

COPY --from=shared-builder /work/vsock-agent/podroid-vsock-agent /work/rootfs/usr/local/bin/podroid-vsock-agent
COPY --from=shared-builder /work/host-bridge/podroid-hostd       /work/rootfs/usr/local/bin/podroid-hostd

ENV PODROID_DISTRO=opensuse
ENV PODROID_DISTRO_VERSION=${OPENSUSE_FLAVOR}
COPY _shared/files        /work/files
COPY _shared/init/systemd /work/systemd-init
COPY opensuse/build.sh    /work/build.sh
RUN chmod +x /work/build.sh && \
    ROOTFS_PASSWORD="${ROOTFS_PASSWORD}" /work/build.sh

RUN mksquashfs /work/rootfs /work/rootfs.squashfs \
        -comp zstd -Xcompression-level 19 -all-root -noappend

FROM scratch AS export
COPY --from=builder /work/rootfs.squashfs /rootfs.squashfs
