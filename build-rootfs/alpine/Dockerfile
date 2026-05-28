# build-rootfs/alpine/Dockerfile
#
# Builds a Podroid-flavoured Alpine aarch64 rootfs. OpenRC is PID 1; the only
# distro-specific concerns here are the apk package set and the OpenRC service
# layout under files/.
#
# The build context is ../ (the build-rootfs/ root) so it can also see
# _shared/vsock-agent and _shared/host-bridge — those C sources are common to
# every distro and live outside per-distro directories. CI workflow runs:
#
#   docker buildx build \
#     -f build-rootfs/alpine/Dockerfile \
#     --build-arg ALPINE_RELEASE=3.23.4 \
#     --output type=local,dest=out \
#     build-rootfs/
#
# Output: /rootfs.squashfs (uniform across all distros; the workflow renames
# to podroid-rootfs-alpine-<version>-aarch64.squashfs on upload).

# ── Stage 1: cross-compile the shared C agents (musl-static aarch64) ─────────
# Uses qemu-user under the hood — no separate cross-toolchain needed.
FROM --platform=linux/arm64 alpine:3.23 AS shared-builder
RUN apk add --no-cache musl-dev gcc make linux-headers
WORKDIR /work
COPY _shared/vsock-agent /work/vsock-agent
RUN cd /work/vsock-agent && make CC=gcc
COPY _shared/host-bridge /work/host-bridge
RUN cd /work/host-bridge && make CC=gcc

# ── Stage 2: build the Alpine rootfs squashfs ───────────────────────────────
FROM alpine:3.23 AS builder

ARG ALPINE_RELEASE=3.23.4
ARG ALPINE_ARCH=aarch64

RUN apk add --no-cache curl squashfs-tools tar openssl libcap-utils

WORKDIR /work
RUN curl -L -o minirootfs.tar.gz \
    "https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_RELEASE%.*}/releases/${ALPINE_ARCH}/alpine-minirootfs-${ALPINE_RELEASE}-${ALPINE_ARCH}.tar.gz" \
 && mkdir rootfs \
 && tar -xzf minirootfs.tar.gz -C rootfs \
 && rm minirootfs.tar.gz

# Pull in the shared C agents built in stage 1.
COPY --from=shared-builder /work/vsock-agent/podroid-vsock-agent /work/rootfs/usr/local/bin/podroid-vsock-agent
COPY --from=shared-builder /work/host-bridge/podroid-hostd       /work/rootfs/usr/local/bin/podroid-hostd

ENV ALPINE_VERSION=${ALPINE_RELEASE}
COPY alpine/files     /work/files
COPY alpine/build.sh  /work/build.sh
RUN chmod +x /work/build.sh && /work/build.sh

RUN mksquashfs /work/rootfs /work/rootfs.squashfs \
    -comp zstd -Xcompression-level 19 -all-root -noappend
# (No -no-xattrs: rootless podman's newuidmap/newgidmap need their file
# capabilities preserved; they're stored as security.capability xattrs.)

FROM scratch AS export
COPY --from=builder /work/rootfs.squashfs /rootfs.squashfs
