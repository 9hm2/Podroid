# build-rootfs/fedora/Dockerfile
#
# Builds a Fedora aarch64 rootfs squashfs for Podroid. systemd is PID 1.
# Uses dnf-bootstrap from a Fedora host image (dnf --installroot=...).
#
# Build context is the build-rootfs/ root.

FROM --platform=$BUILDPLATFORM alpine:3.23 AS shared-builder
RUN apk add --no-cache musl-dev gcc make linux-headers
WORKDIR /work
COPY _shared/vsock-agent /work/vsock-agent
RUN cd /work/vsock-agent && make CC=gcc
COPY _shared/host-bridge /work/host-bridge
RUN cd /work/host-bridge && make CC=gcc

# Use the Fedora host image so we get a recent dnf with --forcearch support.
FROM fedora:42 AS builder

ARG FEDORA_RELEASE=42
ARG ROOTFS_PASSWORD=podroid

RUN dnf install -y --setopt=install_weak_deps=False \
        dnf-plugins-core squashfs-tools openssl \
 && dnf clean all

WORKDIR /work

# Bootstrap repo config explicitly into the installroot. dnf5 doesn't
# carry the host's /etc/yum.repos.d/ across to --installroot, and the
# default config has no enabled repos pointing at aarch64 mirrors. Write
# minimal fedora + updates repos pointed at the Everything tree so dnf
# can resolve the package set below. (printf rather than a heredoc — the
# previous `<<EOF && \` form broke the Dockerfile parser because the
# `dnf` invocation after EOF was treated as a new instruction.)
RUN mkdir -p /work/rootfs/etc/yum.repos.d && \
    printf '%s\n' \
        '[fedora]' \
        "name=Fedora ${FEDORA_RELEASE} - aarch64" \
        "baseurl=https://download.fedoraproject.org/pub/fedora/linux/releases/${FEDORA_RELEASE}/Everything/aarch64/os/" \
        'enabled=1' \
        'gpgcheck=0' \
        '' \
        '[fedora-updates]' \
        "name=Fedora ${FEDORA_RELEASE} - aarch64 - Updates" \
        "baseurl=https://download.fedoraproject.org/pub/fedora/linux/updates/${FEDORA_RELEASE}/Everything/aarch64/" \
        'enabled=1' \
        'gpgcheck=0' \
        > /work/rootfs/etc/yum.repos.d/fedora.repo && \
    dnf --installroot=/work/rootfs \
        --releasever=${FEDORA_RELEASE} \
        --forcearch=aarch64 \
        --setopt=install_weak_deps=False \
        --setopt=tsflags=nodocs \
        --nogpgcheck \
        install -y \
            fedora-release fedora-gpg-keys \
            systemd systemd-udev dbus-broker \
            openssh-server sudo passwd \
            iproute iputils net-tools dhcp-client \
            iw wireless-tools rfkill wpa_supplicant \
            usbutils pciutils kmod bash-completion \
            less nano tmux screen \
            ca-certificates glibc-langpack-en \
 && dnf --installroot=/work/rootfs clean all

COPY --from=shared-builder /work/vsock-agent/podroid-vsock-agent /work/rootfs/usr/local/bin/podroid-vsock-agent
COPY --from=shared-builder /work/host-bridge/podroid-hostd       /work/rootfs/usr/local/bin/podroid-hostd

ENV PODROID_DISTRO=fedora
ENV PODROID_DISTRO_VERSION=${FEDORA_RELEASE}
COPY _shared/files        /work/files
COPY _shared/init/systemd /work/systemd-init
COPY fedora/build.sh      /work/build.sh
RUN chmod +x /work/build.sh && \
    ROOTFS_PASSWORD="${ROOTFS_PASSWORD}" /work/build.sh

RUN mksquashfs /work/rootfs /work/rootfs.squashfs \
        -comp zstd -Xcompression-level 19 -all-root -noappend

FROM scratch AS export
COPY --from=builder /work/rootfs.squashfs /rootfs.squashfs
