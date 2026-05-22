# Podroid AI Context

> **Last updated:** 2026-04-30
> **Current version:** 1.1.8 (versionCode 20)
> **Purpose:** Complete project context for AI-assisted development. Read this before touching any file.

---

## Project Overview

**Podroid** is an Android app that runs rootless Podman containers on arm64 Android devices with no root required. It spins up a headless Alpine Linux 3.23 VM using QEMU TCG and exposes a serial console terminal inside the app.

- **Package:** `com.excp.podroid` (debug: `com.excp.podroid.debug`)
- **Min SDK:** 28 (Android 9.0)  
- **Target SDK:** 36
- **Architecture:** AArch64 only (no x86/x86_64)
- **Root project name in Gradle:** `Podroid`
- **GitHub:** https://github.com/ExTV/Podroid

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| UI | Jetpack Compose + Material 3 |
| DI | Hilt (constructor injection, no @Provides) |
| Async | Kotlin Coroutines + StateFlow |
| Persistence | DataStore Preferences |
| Terminal | Termux TerminalView v0.118.1 (JitPack AAR) |
| VM | QEMU 11.0.0-rc2 TCG (no KVM) |
| Container Runtime | Podman + crun + netavark + slirp4netns |
| VM Init | Minimal initramfs (`init-podroid`, ~45 lines) + OpenRC PID 1 |
| VM System | Standard Alpine 3.23 squashfs + ext4 overlay (persistent) |

---

## Architecture

### High-Level Flow
```
Android App
├── PodroidApplication — extracts assets (vmlinuz, initrd, alpine-rootfs.squashfs, qemu/) on first run (size-checked)
├── PodroidService (foreground) — hosts QEMU, holds WakeLock, updates notification with boot stages
├── PodroidQemu (singleton)
│   ├── QEMU process (libqemu-system-aarch64.so — ELF binary renamed .so for APK)
│   ├── /dev/vda        — storage.img (writable ext4, persistent overlay upper, resized on first boot)
│   ├── /dev/vdb        — alpine-rootfs.squashfs (read-only, gzip squashfs, overlay lower)
│   ├── serial.sock     — ttyAMA0 boot-log sink; monitor stays connected for VM lifetime
│   ├── terminal.sock   — virtio-console hvc0; primary shell I/O for podroid-bridge
│   ├── ctrl.sock       — virtio-console hvc1; debounced RESIZE messages from bridge
│   └── qmp.sock        — QEMU Machine Protocol for runtime control (port forwarding)
└── Compose UI
    └── Screens: Setup → Home → Terminal / Settings
```

### VM Boot Sequence
1. QEMU starts and binds all four sockets (`server,nowait` so it doesn't block on a connection).
2. **Boot monitor** (`PodroidQemu.monitorBootSerial`) connects to `serial.sock` and streams everything kernel + init + OpenRC writes to ttyAMA0 into `console.log` + the in-memory rolling buffer used by `detectBootStage`. Stays connected for the lifetime of the VM (no hand-off).
3. `init-podroid` (initramfs, ~45 lines): mounts `/dev/vda` (ext4, mkfs if needed) at `/mnt/persist`, mounts `/dev/vdb` (squashfs) at `/mnt/lower`, stacks `overlay -o lowerdir=/mnt/lower,upperdir=/mnt/persist/upper,workdir=/mnt/persist/work` at `/mnt/overlay`, `mount --move`s the lower/persist into the new root, then `switch_root /mnt/overlay /sbin/init` (busybox init).
4. `/sbin/init` reads `/etc/inittab` and starts OpenRC. Runlevels are pre-symlinked at build time (`/etc/runlevels/default/{podroid-bootstrap,podroid-network,podroid-resize,dropbear,podroid-ready}`).
5. **podroid-bootstrap** (OpenRC service on squashfs): cgroup v2, devpts/shm/mqueue, sysctl, ZRAM, `mount --make-rshared /`, chmod 0666 /dev/{net/tun,fuse}, hostname.
6. **podroid-network**: `ip link set eth0 up`, assign 10.0.2.15/24, default route 10.0.2.2, write `/etc/resolv.conf` (8.8.8.8, 1.1.1.1).
7. **podroid-ready**: emits `Starting SSH...`, `Almost ready...`, `Ready!` markers consumed by `detectBootStage()`.
8. `detectBootStage("Ready!")` fires → `_state = Running` → `autoStartBridge()` posts to the main thread.
9. `TerminalSession` launches `libpodroid-bridge.so` with `terminal.sock` + `ctrl.sock`. Bridge connects directly to virtio-console hvc0 (which getty owns via `podroid-login`).

### Why switch_root and not chroot
The old init-podroid did `chroot /mnt/overlay`. Inside `crun exec`, `setns(CLONE_NEWNS)` resets `fs->root` — so namespace forks saw raw kernel paths (`/mnt/overlay/proc` instead of `/proc`), which is the root cause of issue #17 (`podman exec -it` failing with `crun: open /dev/ptmx: No such device`). `switch_root` reorganizes the kernel mount tree itself, so subsequent namespace operations land on a clean `/`.

### No socket hand-off
The old PL011 path used a single-client `serial.sock` shared by the boot monitor and the bridge, which forced a `releaseSerial()` + delay dance. With the virtio-console split that constraint is gone. `releaseSerial()` only runs during `cleanup()` on VM stop now.

---

## Performance Tuning (TCG, no KVM)

All tuning applies under software emulation only — KVM is impossible without root.

**In `PodroidQemu.buildCommand()`:**
- `tcg,thread=multi` — one host thread per vCPU.
- `tb-size`: 256 MB for <2 GB RAM, 512 MB for ≥2 GB — larger Translation Block cache reduces re-translation for JIT-heavy workloads (Node, JVM in containers).
- `iothread=iothread0` on `virtio-blk-pci` — dedicated I/O thread decoupled from vCPU threads; biggest win for container image pull/extraction.
- `mitigations=off` in guest kernel cmdline — safe inside TCG VM (speculative execution attacks can't cross the emulated ISA boundary); 5–15% CPU gain.
- `elevator=mq-deadline` in guest kernel cmdline — request merging for virtio-blk random writes (Podman overlay graph driver).

**In `podroid-bootstrap` (OpenRC service):**
- ZRAM swap at half physical RAM using lz4 — gives 1.5–2× effective memory with near-zero I/O cost; swapon at priority 100 so it's preferred over any file swap.
- `CONFIG_EXT4_FS_SECURITY=y` and `CONFIG_SQUASHFS_XATTR=y` are required so `security.capability` xattrs survive the overlay — without them `newuidmap`/`newgidmap` lose their `cap_setuid+ep` / `cap_setgid+ep` capabilities and rootless podman fails.

**In `Dockerfile` (QEMU build):**
- Currently `-fPIC -DANDROID` only (no LTO / -O3) — those previously caused link failures with QEMU 11.0.0-rc2 + NDK r27c. Three minimum patches make the build clean: `--disable-plugins`, empty `contrib/ivshmem-{server,client}/meson.build` stubs, and an `-include shm_shim.h` + `libshm.a` (memfd_create-backed) to satisfy `shm_open`/`shm_unlink`.

---

## Project Structure

### Root Level
```
/
├── app/                            # Android application module
├── init-podroid                    # Minimal initramfs script (~45 lines) — mounts overlay, switch_root
├── podroid-bridge.c                # Native PTY↔virtio-console relay (compiled to libpodroid-bridge.so)
├── Dockerfile                      # Kernel + initramfs + QEMU multi-stage build
├── build-rootfs/                   # Separate Docker pipeline for the Alpine squashfs
│   ├── Dockerfile.rootfs           # apk-installs Alpine, mksquashfs -comp gzip
│   ├── build-rootfs.sh             # apk install list, doas/sudo wheel rules, file caps, runlevel symlinks
│   └── files/                      # OpenRC services + helpers baked into the squashfs
│       ├── etc/init.d/podroid-{bootstrap,network,resize,ready}
│       ├── etc/{inittab,rc.conf,conf.d/podroid}
│       └── usr/local/bin/podroid-{login,resize}
├── build-all.sh                    # Unified build+deploy script (kernel/initramfs/rootfs/qemu/termux/apk)
├── gradle.properties               # podroidQemuVersion=11.0.0-rc2, podroidKernelVersion
├── build.gradle.kts, settings.gradle.kts
└── gradle/, gradlew
```

### app/src/main/java/com/excp/podroid/

```
├── MainActivity.kt                 # Single Activity, WindowSizeClass, Hilt entry point
├── PodroidApplication.kt           # Hilt @HiltAndroidApp, asset extraction on first run
│
├── engine/
│   ├── PodroidQemu.kt              # QEMU lifecycle, boot monitor, bridge session, resize
│   ├── QmpClient.kt                # QMP socket client (runtime port forwarding)
│   └── VmState.kt                  # Sealed: Idle|Starting|Running|Paused|Saving|Resuming|Stopped|Error
│
├── service/
│   └── PodroidService.kt           # Foreground service, WakeLock, notification with boot stages
│       └── Actions: ACTION_START, ACTION_STOP
│
├── data/repository/
│   ├── SettingsRepository.kt       # DataStore Preferences (all settings)
│   ├── PortForwardRepository.kt    # Port forward rules (Set<String> in DataStore)
│   └── UpdateRepository.kt         # GitHub releases API checker
│
├── di/
│   └── AppModule.kt                # Hilt module (minimal — constructor injection used everywhere)
│
└── ui/
    ├── navigation/
    │   └── NavGraph.kt             # Routes: SETUP, HOME, TERMINAL, SETTINGS
    │       └── TerminalViewModel scoped OUTSIDE NavHost (survives screen navigation)
    │
    ├── theme/
    │   ├── Theme.kt                # Material You dynamic colors + dark/light
    │   ├── Color.kt                # Purple80/Grey80/Cyan80 + status colors
    │   └── Type.kt                 # Default Material 3 typography
    │
    ├── HapticManager.kt            # Centralized haptics: extraKeyPress, longPressMenu, bell, error
    │
    └── screens/
        ├── setup/
        │   ├── SetupScreen.kt      # 3-page HorizontalPager: Storage / VM config+SSH / Downloads
        │   └── SetupViewModel.kt   # completeSetup() → DataStore, notification permission request
        │
        ├── home/
        │   ├── HomeScreen.kt       # Start/Stop/Restart VM, open terminal, update dialog, AdaptiveContainer
        │   ├── HomeViewModel.kt    # checkForUpdate(), startPodroid(), stopVm(), restartVm()
        │   └── AnimatedBootProgress.kt  # Rotating arc Canvas + boot stage text (replaces CircularProgressIndicator)
        │
        ├── terminal/
        │   ├── TerminalScreen.kt       # TerminalView composable, extra keys bar, focus observer, pushSize
        │   ├── TerminalViewModel.kt    # Session wiring, CSI keys, focus events, font/theme, forceUpdateSizeFromView
        │   ├── ExtraKey.kt             # sealed KeyAction + JSON serde via ExtraKeySerde
        │   ├── DefaultKeyLayouts.kt    # Built-in layouts: minimal, full (nvim/shell)
        │   └── QuickSettingsDrawer.kt  # Bottom-sheet: font size, theme, font, extras toggle, haptics
        │
        ├── settings/
        │   ├── SettingsScreen.kt   # Sections: VM Resources / Network / Appearance / Diagnostics / About
        │   │                       # Terminal settings moved to QuickSettingsDrawer (NOT here)
        │   └── SettingsViewModel.kt # Port forward CRUD, VM reset, console log export
        │
        └── components/
            └── AdaptiveContainer.kt # WindowSizeClass-based max-width wrapper (tablet/foldable)
```

### Assets (app/src/main/assets/)
```
├── vmlinuz-virt                    # Custom Linux 6.6.87 kernel (built by Dockerfile)
├── initrd.img                      # Minimal initramfs (only needs to mount overlay + switch_root)
├── alpine-rootfs.squashfs          # ~41 MB Alpine 3.23 squashfs (built by build-rootfs/) — mounted as /dev/vdb
├── qemu/
│   ├── efi-virtio.rom
│   └── keymaps/
├── colors/                         # 114 terminal color schemes (.properties files)
└── fonts/                          # 13 terminal fonts (.ttf)
    └── JetBrains-Mono.ttf, Fira-Code.ttf, CascadiaCode.ttf, etc.
```

All three large blobs (vmlinuz-virt, initrd.img, alpine-rootfs.squashfs) are gitignored — built locally and extracted from APK assets into `filesDir` on first run by `PodroidApplication.copyAssetIfNeeded()`.

### Native Libs (app/src/main/jniLibs/arm64-v8a/)

**Note:** All native binaries require 16KB page alignment (`-Wl,-z,max-page-size=16384`) — mandatory on Android 13+.

| File | What it is |
|---|---|
| `libqemu-system-aarch64.so` | QEMU TCG emulator (PIE executable, 16KB aligned) |
| `libpodroid-bridge.so` | PTY ↔ virtio-console relay (PIE executable, 16KB aligned) |
| `libtermux.so` | Termux terminal emulator JNI (rebuilt for 16KB pages) |
| `libslirp.so` | SLIRP user-mode networking (statically linked into QEMU) |

---

## Key Constants & Values

| Item | Value |
|------|-------|
| podroidKernelVersion | Set in `gradle.properties` (controls kernel source version) |
| QEMU binary | `libqemu-system-aarch64.so` in `nativeLibraryDir` |
| Kernel | `filesDir/vmlinuz-virt` |
| Initrd | `filesDir/initrd.img` |
| Storage image | `filesDir/storage.img` (ext4, sparse, /dev/vda — overlay upper) |
| Rootfs squashfs | `filesDir/alpine-rootfs.squashfs` (read-only, /dev/vdb — overlay lower) |
| serial.sock | `filesDir/serial.sock` — ttyAMA0, boot-log only; monitor stays connected for VM lifetime |
| terminal.sock | `filesDir/terminal.sock` — virtio-console hvc0; primary terminal for podroid-bridge |
| ctrl.sock | `filesDir/ctrl.sock` — virtio-console hvc1; bridge writes debounced `RESIZE rows cols\n` |
| qmp.sock | `filesDir/qmp.sock` |
| VM IP | 10.0.2.15/24 (SLIRP) |
| Gateway | 10.0.2.2 |
| DNS (SLIRP) | 10.0.2.3 (unreliable on Android) + 8.8.8.8, 1.1.1.1 |
| SSH host port | 9922 → VM:22 (Dropbear, password "podroid") |
| Default RAM | 512 MB (range: 512–4096) |
| Default CPUs | 1 (range: 1, 2, 4, 6, 8) |
| Default font size | 20sp |
| Default storage | 2 GB (range: 2–64 GB) |
| QEMU machine | `-M virt,gic-version=3 -smp <cpus> -m <ramMb>`; tunable extras (`-cpu`, `-accel`, RNG, etc.) come from the user-editable Settings field |
| Kernel cmdline | `console=ttyAMA0 <user-extras> androidip=<ip> [ssh=1]` (default user-extras: `loglevel=1 quiet mitigations=off elevator=mq-deadline`) |

---

## Boot Stage Strings (OpenRC services → detectBootStage)

These exact strings are emitted by the boot pipeline and matched by `PodroidQemu.detectBootStage()`:

| String in serial output | Source | Android UI label |
|------------------------|--------|-----------------|
| `Mounting storage...` | PodroidQemu (pre-launch) | "Mounting storage..." |
| `Booting kernel...` | PodroidQemu (pre-launch) | "Booting kernel..." |
| `Starting SSH...` | `podroid-ready` | "Starting SSH..." |
| `Almost ready...` | `podroid-ready` | "Almost ready..." |
| `Ready!` | `podroid-ready` | "Ready" → state = Running → autoStartBridge() |

The earlier intermediate stages (`Initializing system...`, `Loading kernel modules...`, `Network found`, `Configuring containers...`) were tied to the old monolithic init-podroid; with OpenRC, the relevant init steps are short enough that only the SSH / Almost ready / Ready trio matters for UI feedback.

---

## init-podroid Structure (post-OpenRC migration)

The initramfs script is now ~45 lines and does the absolute minimum: mount overlay, switch_root.

```sh
#!/bin/sh
mount -t proc proc /proc
mount -t sysfs sys /sys
mount -t devtmpfs dev /dev

# /dev/vda = persistent ext4 (writable upper); /dev/vdb = squashfs (read-only lower)
PERSIST=/dev/vda
LOWER=/dev/vdb

# mkfs.ext4 on first boot if vda is blank, then mount
mount -t ext4 $PERSIST /mnt/persist || (mkfs.ext4 -F $PERSIST && mount -t ext4 $PERSIST /mnt/persist)
mount -t squashfs -o ro $LOWER /mnt/lower

mkdir -p /mnt/persist/upper /mnt/persist/work /mnt/overlay
mount -t overlay overlay \
  -o lowerdir=/mnt/lower,upperdir=/mnt/persist/upper,workdir=/mnt/persist/work \
  /mnt/overlay

# Move lower + persist into the new root tree so OpenRC services can see them
mkdir -p /mnt/overlay/mnt/lower /mnt/overlay/mnt/persist
mount --move /mnt/lower   /mnt/overlay/mnt/lower
mount --move /mnt/persist /mnt/overlay/mnt/persist

exec switch_root /mnt/overlay /sbin/init
```

After `switch_root`, busybox `/sbin/init` reads `/etc/inittab` and starts OpenRC. The squashfs ships pre-symlinked runlevels, so no chroot-into-aarch64 was needed at build time.

### OpenRC Services (build-rootfs/files/etc/init.d/)

| Service | Runlevel | Responsibility |
|---------|----------|----------------|
| `podroid-bootstrap` | default | cgroup v2 unified hierarchy, devpts/shm/mqueue, sysctl (net.ipv4.ip_forward=1, kernel.unprivileged_userns_clone=1), ZRAM swap, `mount --make-rshared /`, chmod 0666 /dev/{net/tun,fuse}, hostname |
| `podroid-network` | default | `ip link set eth0 up`, IP 10.0.2.15/24, default route 10.0.2.2, /etc/resolv.conf |
| `podroid-resize` | default | Daemon reading `RESIZE rows cols` lines from /dev/hvc1, running `stty rows N cols M < /dev/hvc0` |
| `podroid-ready` | default | Emits the boot-stage markers consumed by `detectBootStage` |
| `dropbear` | default | SSH (only when ssh=1 in kernel cmdline — guard logic in podroid-bootstrap) |

### podroid-login (build-rootfs/files/usr/local/bin/podroid-login)
```bash
#!/bin/bash
cat /etc/motd 2>/dev/null
exec /bin/bash --login
```
No `script` wrapper needed any more — virtio-console (`/dev/hvc0`) reports a real `TIOCGWINSZ` via the resize daemon, so nvim/btop work without a fake PTY.

---

## Terminal Architecture

### Data Flow
```
Keyboard  → TerminalView → TerminalSession.write() → PTY master fd
                                                           ↓
                                            podroid-bridge stdin (PTY slave)
                                                           ↓
                                          terminal.sock → /dev/hvc0 in VM
VM output → /dev/hvc0 → terminal.sock → bridge stdout → PTY slave → TerminalEmulator → TerminalView
Resize    → TerminalView.updateSize() → TIOCSWINSZ on PTY → SIGWINCH → bridge
                                           → bridge debounces 200 ms → TIOCGWINSZ
                                           → writes "RESIZE rows cols\n" to ctrl.sock
                                           → init resize daemon reads /dev/hvc1
                                           → stty rows N cols M < /dev/hvc0
                                           → Linux SIGWINCH to VM fg process group
Mouse     → TerminalView touch handler → PTY → bridge → terminal.sock → VM
```

### PodroidQemu Key Methods

| Method | What it does |
|--------|-------------|
| `start(portForwards, ramMb, cpus, sshEnabled, androidIp)` | Launches QEMU process, starts boot monitor coroutine, 60s timeout fallback |
| `monitorBootSerial(proc)` | Reads serial.sock, appends to consoleBuilder + console.log, calls detectBootStage() |
| `detectBootStage()` | Scans the last 1024 chars of `consoleBuilder` (stateful rolling buffer) and matches boot-stage strings; sets _bootStage and _state. The rolling buffer was added in 1.1.5 to survive markers split across `read()` chunks on fast devices (e.g. Pixel 10 Pro XL was missing "Ready!" and falling back to the 60 s timeout). |
| `releaseSerial()` | shutdownInput + shutdownOutput + close on bootSocket |
| `autoStartBridge()` | Posts to MainLooper, calls releaseSerial(), 500ms delay, creates TerminalSession |
| `createTerminalSession(client)` | Returns pre-started session or creates new one; sets sessionClientDelegate |
| `replayBootOutput(sess)` | Appends post-"Ready!" serial bytes to new emulator (so first screen isn't blank) |
| `cleanup()` | Cancels ioScope, kills session, deletes socket files |

### TerminalViewModel Key Patterns

**`forceUpdateSizeFromView(view)`**: Computes cols/rows from `Paint` metrics using `currentTypeface` and raw `terminalFontSize.value.toFloat()` (NO scaledDensity multiplication — matches TerminalView's internal path exactly). Must use `currentTypeface` not `Typeface.MONOSPACE` for custom fonts.

**`pushSizeNow()`**: Fires `v.updateSize()` immediately + at 300ms + 800ms. Called after font size changes and after closing QuickSettings.

**`forceUpdateSizeFromView` via pushSize in TerminalScreen**: Fires at 0/150/600/1500ms after layout change to outlast lazy layout settling.

**Focus events**: `TerminalScreen` installs `LifecycleEventObserver`. ON_RESUME → `sendFocusEvent(true)` → `\x1b[I`. ON_PAUSE → `sendFocusEvent(false)` → `\x1b[O`. Gated on DECSET 1004 via reflection on `mCurrentDecSetFlags`.

**Proxy sessionClient** (`proxySessionClient` in PodroidQemu): Delegates to `sessionClientDelegate`. Lets session be created at boot-complete time before UI exists. TerminalViewModel sets itself as delegate via `qemu.sessionClientDelegate = sessionClient`.

### Key Sequences (TerminalViewModel.viewClient.onKeyDown)

```
mod = 1 + (shift?1:0) + (alt?2:0) + (ctrl?4:0)
arrow(final): if mod==1 → "\x1b[$final"  else → "\x1b[1;$mod$final"
```

| Key | Sequence |
|-----|----------|
| Shift+Tab | `\x1b[Z` |
| Ctrl+Left/Right | `\x1b[1;5D` / `\x1b[1;5C` |
| Shift+Up/Down | `\x1b[1;2A` / `\x1b[1;2B` |
| F1–F4 | `\x1bOP` … `\x1bOS` |
| F5–F12 | `\x1b[15~` … `\x1b[24~` |

**Bracketed paste**: `onPasteTextFromClipboard` calls `emu.paste(text)` — Termux wraps in `\x1b[200~…\x1b[201~` when DECSET 2004 is active.

**Extra keys hold-to-repeat**: `KeyButton` composable uses `pointerInput + LaunchedEffect` — 400ms initial delay then 70ms → 30ms accelerating cadence. Only arrow keys flagged `repeatable = true`.

**CTRL/ALT sticky**: `extraCtrl` / `extraAlt` toggles on the extras bar. Consumed after any non-modifier key press.

---

## podroid-bridge.c

- **Purpose**: Relay PTY (Termux-allocated) ↔ `terminal.sock` (QEMU virtio-console / hvc0).
- **Arguments**: `bridge_exe terminal_sock_path ctrl_sock_path`.
- **STDERR silenced** immediately via `dup2(/dev/null, STDERR_FILENO)` — bridge runs as TerminalSession subprocess so stderr IS the PTY.
- **`cfmakeraw()`** on own stdin (VM's getty handles echo/line editing).
- **terminal.sock retry**: 50 attempts × 200 ms = 10 s max.
- **ctrl.sock retry**: 50 × 100 ms = 5 s max; lazy reconnect on each SIGWINCH if not yet connected.
- **`send_resize()`**: reads new size via `TIOCGWINSZ`, writes `RESIZE rows cols\n` to ctrl.sock.
- **SIGWINCH debounce**: every signal just refreshes a `clock_gettime(CLOCK_MONOTONIC)` timestamp; the actual `send_resize()` only fires once the burst has been quiet for `RESIZE_DEBOUNCE_MS` (200 ms). Collapses the ~25 per-frame layout changes during an Android keyboard slide into a single shell prompt redraw.
- **select timeout**: 50 ms — wakes the loop frequently enough to fire the debounced resize on schedule.
- The old `filter_queries()` intercept (`ESC[18t`/`ESC[19t` xterm size queries) is gone — virtio-console reports real `TIOCGWINSZ` inside the guest, so apps don't need the workaround.

---

## Build Pipelines

There are now **two** Docker pipelines:

### `Dockerfile` (kernel + initramfs + QEMU)
Custom Linux 6.6.87 (built from upstream tarball) + minimal initramfs (only `init-podroid` + a busybox + switch_root) + QEMU 11.0.0-rc2 cross-compiled against NDK r27c. Forced `=y` options include `EXT4_FS_SECURITY`, `SQUASHFS_XATTR`, `SQUASHFS_ZLIB`, `OVERLAY_FS`, `FUSE_FS`, `BRIDGE`, `IPV6`, `NF_TABLES`, `NFT_COMPAT`, MASQUERADE family, `VETH`, `TUN`. A build-time check fails the build if any go missing (silent Kconfig demotion guard).

### `build-rootfs/Dockerfile.rootfs` (Alpine squashfs)
Pulls `alpine-minirootfs-3.23.4-aarch64.tar.gz`, runs `build-rootfs.sh` to apk-install the userland (alpine-base, openrc, busybox-openrc, bash, podman, crun, fuse-overlayfs, iptables/ip6tables/nftables, slirp4netns, aardvark-dns, netavark, dropbear, shadow-uidmap, libcap-utils, doas, sudo, ca-certificates), seal the right caps on newuidmap/newgidmap, set wheel-group sudo/doas rules, copy in the OpenRC service files and /etc configs, then `mksquashfs -comp gzip -all-root -noappend` (no `-no-xattrs` so security.capability is preserved). Output: `app/src/main/assets/alpine-rootfs.squashfs` (~41 MB).

**Why gzip and not lz4/zstd**: the kernel only enables `CONFIG_SQUASHFS_ZLIB=y`. lz4 squashfs fails to mount.

### QEMU Build Details
- NDK r27c, API 28 sysroot
- Dependencies (static): pcre2, libffi, glib 2.82.5, pixman 0.44.2, libattr, libucontext
- **libucontext shim**: Android Bionic lacks `ucontext.h` → built from kaniini/libucontext; header shim remaps `getcontext/swapcontext/etc.` to libucontext functions
- **`--with-coroutine=ucontext`**: Required for ARMv9.2 PAC (Android 15+ Tensor devices) — avoids SIGILL from siglongjmp across alternate signal stacks
- **librt optional** patch: Bionic includes librt functions in libc
- **`st_*_nsec` undef** patch: Android's `sys/stat.h` macros clash with 9p struct fields
- **patchelf**: libslirp soname patched from `libslirp.so.0` → `libslirp.so`

### 16KB Page Alignment
All native libs must have `p_align >= 16384`. Enforced via `-Wl,-z,max-page-size=16384` everywhere. Required for Android devices with 16KB system pages. Verified by Python ELF parser in `build-all.sh`.

### VM Rootfs Packages (Alpine 3.23 aarch64, build-rootfs.sh)
```
alpine-base openrc busybox-openrc bash
podman crun fuse-overlayfs aardvark-dns netavark slirp4netns
iptables ip6tables nftables bridge-utils iproute2
dropbear dropbear-openrc curl ca-certificates
shadow shadow-uidmap libcap-utils doas sudo
```
Plus runtime config: root password "podroid", `/etc/doas.d/doas.conf` = `permit persist :wheel`, `/etc/sudoers.d/wheel` = `%wheel ALL=(ALL) ALL`. Users created with `adduser -G wheel <name>` can run `doas` / `sudo` after entering their password.

---

## Build Commands

All components are coordinated by `build-all.sh`:

```bash
./build-all.sh kernel      # Build custom kernel only (podroid_kernel.config, ~5–10 min)
./build-all.sh initramfs   # Build kernel + minimal initramfs (~10–15 min)
./build-all.sh rootfs      # Build Alpine squashfs (~30 s, Docker-cached)
./build-all.sh qemu        # Build QEMU + podroid-bridge via Docker (~30 min first time)
./build-all.sh termux      # Build libtermux.so via local NDK (16KB page alignment)
./build-all.sh apk         # Build Android APK via Gradle
./build-all.sh all         # Build everything
./build-all.sh deploy      # Full deploy workflow
./build-all.sh test        # Boot validation: deploys APK, polls console.log for "Ready!"
```

**APK only:**
```bash
./gradlew assembleDebug
./gradlew installDebug
```

**Fast bridge rebuild (without full Docker QEMU build):**
```bash
NDK=$HOME/Android/Sdk/ndk/$(ls ~/Android/Sdk/ndk/ | tail -1)
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang
$CC --sysroot=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot \
    -target aarch64-linux-android28 -fPIE -pie -Wl,-z,max-page-size=16384 \
    podroid-bridge.c -o app/src/main/jniLibs/arm64-v8a/libpodroid-bridge.so
./gradlew :app:installDebug
```

**Monitor VM boot:**
```bash
adb logcat -s PodroidQemu
adb shell run-as com.excp.podroid.debug cat files/console.log
```

**Workflow**:
- `init-podroid` changed → `./build-all.sh initramfs` → `gradlew assembleDebug` → `adb install`
- `build-rootfs/files/**` or package list → `./build-all.sh rootfs` → `gradlew assembleDebug` → `adb install`
- Kotlin/Java only → `gradlew assembleDebug` → `adb install`
- `podroid-bridge.c` changed → fast bridge rebuild above → `adb install`
- Check boot: `adb shell run-as com.excp.podroid.debug cat files/console.log`

---

## DataStore Keys (SettingsRepository)

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `dark_theme` | Boolean | true | Dark mode |
| `vm_ram_mb` | Int | 512 | VM RAM (512/1024/2048/4096 MB) |
| `vm_cpus` | Int | 1 | VM CPUs (1/2/4/6/8) |
| `terminal_font_size` | Int | 20 | Font size (sp, raw — no scaledDensity) |
| `terminal_color_theme` | String | "default" | Color scheme name (maps to assets/colors/*.properties) |
| `terminal_font` | String | "default" | Font name (maps to assets/fonts/*.ttf) |
| `storage_gb` | Int | 2 | Persistent storage size (GB) |
| `storage_access_enabled` | Boolean | false | Downloads sharing via virtio-9p |
| `setup_done` | Boolean | false | Initial setup completed |
| `ssh_enabled` | Boolean | false | Dropbear SSH in VM |
| `usb_passthrough_enabled` | Boolean | false | Hot-plug USB devices into the VM (adds `-device qemu-xhci`; QEMU backend only) |
| `port_forwards` | Set\<String\> | {} | `"tcp:8080:80"` format |
| `dismissed_update_version` | String | "" | Last dismissed update version |

---

## Port Forwarding

```
PortForwardRule(hostPort=8080, guestPort=80, protocol="tcp")
Serialized as: "tcp:8080:80"
```

- **At boot**: passed as QEMU `-netdev user,hostfwd=tcp::8080-:80` args
- **At runtime**: QMP `human-monitor-command hostfwd_add` / `hostfwd_remove`
- **SSH preset**: always forwarded as `tcp::9922-:22` when `sshEnabled = true`
- **Service presets** (SettingsScreen): Pi-hole (5300→53, 8080→80), Nginx (8080→80, 8443→443), Gitea (3000→3000, 2222→22), Grafana (3001→3000)
- **Pi-hole DNS**: host port 5300 used because Android blocks ports < 1024

---

## Permissions

| Permission | Purpose |
|------------|---------|
| `INTERNET` | SLIRP, QMP socket, GitHub API |
| `ACCESS_NETWORK_STATE` | Get device IP for display |
| `WAKE_LOCK` | Keep CPU alive during VM |
| `VIBRATE` | Terminal bell, haptic feedback |
| `FOREGROUND_SERVICE` | Host QEMU process |
| `FOREGROUND_SERVICE_SPECIAL_USE` | QEMU "emulation" subtype |
| `POST_NOTIFICATIONS` | Android 13+ notification |
| `MANAGE_EXTERNAL_STORAGE` | Downloads sharing via virtio-9p |

---

## Design Patterns & Quirks

### Architecture
- **Single Activity**: Compose Navigation with NavHost
- **TerminalViewModel scoped outside NavHost**: Survives navigation between screens; session persists
- **Proxy sessionClient**: `proxySessionClient` in PodroidQemu delegates to `sessionClientDelegate`; lets session be pre-created at boot before UI attaches
- **Reflection**: `TerminalView.mTermSession`, `mEmulator`, `TerminalEmulator.mCurrentDecSetFlags`, `mapDecSetBitToInternalBit` — all accessed reflectively (Termux AAR private fields)
- **VmState flow**: Idle → Starting → Running → Stopped/Error; service/UI observe via StateFlow

### Known Quirks
- **QEMU + bridge packaged as `.so`**: ELF executables renamed to `.so` for APK packaging; `nativeLibraryDir` extraction still works
- **libslirp soname patched**: `libslirp.so.0` → `libslirp.so` via patchelf (Android linker needs exact match)
- **libtermux.so custom-built**: Termux prebuilt uses 4KB pages; rebuilt with `-Wl,-z,max-page-size=16384`
- **Root project name is "Podroid"**: In `settings.gradle.kts`
- **Room declared but unused**: DataStore used instead
- **Downloads sharing via 9p**: `msize=1048576,cache=loose,noatime` — 128× fewer round-trips vs default 8KB msize
- **QEMU DNS forwarder (10.0.2.3) unreliable on Android**: init-podroid uses 8.8.8.8 + 1.1.1.1 directly
- **No ICMP in SLIRP**: `ping` doesn't work inside VM (QEMU SLIRP limitation)
- **forceUpdateSizeFromView must NOT multiply by scaledDensity**: TerminalView passes raw int textSize to Paint.setTextSize; our computation must match or TUI apps render in wrong grid
- **Bridge stderr silenced**: bridge runs as TerminalSession subprocess, its stderr IS the PTY; any write would bleed into the terminal. Never add `fprintf(stderr, ...)` to podroid-bridge.c
- **seenActive flag in PodroidService**: Prevents premature wakelock release on service start before VM reaches Running state

---

## Common Tasks Reference

### Rebuild initramfs (init-podroid changed)
```bash
./build-all.sh initramfs && ./gradlew assembleDebug && adb uninstall com.excp.podroid.debug; adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Rebuild rootfs squashfs (OpenRC services / package list changed)
```bash
./build-all.sh rootfs && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Check VM console output
```bash
adb shell run-as com.excp.podroid.debug cat files/console.log
```

### Add a new boot stage
1. `echo "My stage..." > /dev/console` (or use the `boot_stage` helper) in the appropriate OpenRC service under `build-rootfs/files/etc/init.d/`
2. Add match in `PodroidQemu.detectBootStage()`
3. Rebuild rootfs (`./build-all.sh rootfs`)

### Add a setting
1. Add DataStore key + Flow in `SettingsRepository.kt`
2. Add UI in `SettingsScreen.kt` (Slider/Switch)
3. Add setter in `SettingsViewModel.kt`
4. Pass to `PodroidQemu.start()` if VM-time setting

### Add a port forward preset
1. Add to `servicePresets` list in `SettingsScreen.kt`

### Add terminal color theme
1. Add `.properties` file to `app/src/main/assets/colors/`
2. Appears automatically in QuickSettingsDrawer picker

### Add terminal font
1. Add `.ttf` to `app/src/main/assets/fonts/`
2. Appears automatically in QuickSettingsDrawer picker

### Modify QEMU arguments
1. Edit `PodroidQemu.buildCommand()`

### Add a new VM control channel message
1. Add write to `ctrl.sock` in `podroid-bridge.c`
2. Add handler in `build-rootfs/files/usr/local/bin/podroid-resize` (the daemon reading from `/dev/hvc1`)

---

## Dependency Versions (gradle/libs.versions.toml)

| Library | Version |
|---------|---------|
| Android Gradle Plugin | 9.1.0 |
| Kotlin | 2.2.21 |
| KSP | 2.3.6 |
| Compose BOM | 2026.03.01 |
| Navigation Compose | 2.9.7 |
| Hilt | 2.59.2 |
| Coroutines | 1.9.0 |
| DataStore | 1.2.1 |
| Termux terminal-view | v0.118.1 (JitPack) |
| Lifecycle | 2.9.0 |
| Activity Compose | 1.10.1 |
| WindowSizeClass | material3-adaptive (Compose BOM) |
| Room | 2.8.4 (declared, NOT used) |

---

## ProGuard

`app/proguard-rules.pro` keeps Termux reflection targets:
- `TerminalView.mTermSession`
- `TerminalView.mEmulator`

---

## Pending Work (as of 2026-04-30)

### Next Feature: Container Hub
Full container management screen — SSH into VM at `127.0.0.1:9922`, run `podman ps`, one-tap deploy from a catalog of services (Pi-hole, Vaultwarden, code-server, Gitea, Jellyfin, Uptime Kuma, Filebrowser, Nginx, Grafana). Requires JSch dependency.

### Other TODOs
- **Pin Dockerfile packages**: Add version pins to prevent reproducibility breaks
- **DNS configurable**: Currently hardcoded 8.8.8.8 + 1.1.1.1; add settings UI
- **Overlay mount validation**: Detect and surface overlay failures with actionable error
- **Terminal title → TopAppBar**: Wire `onTitleChanged()` to update app bar from OSC sequences

### Recently shipped (1.1.8)
- **Vendored Termux fork (`MatanZ/sixel4`)**: `terminal-emulator` and `terminal-view` now live as local Gradle subprojects (not JitPack AARs), so we can patch the parser without a fork-and-publish dance. Replaces upstream Termux v0.118.1.
- **Sixel + iTerm2 image protocols**: Inline images render in the in-app terminal via `chafa`, `lsix`, `kitty +kitten icat`, lazygit thumbnails, etc.
- **Kitty graphics protocol** (minimum-viable: `a=T,f=100,t=d,m=0/1,c=,r=`): multi-chunk PNG inline rendering, used by yazi/lf/fzf preview.
- **DECSET 2026 synchronized output**: Atomic-frame mode advertised + honored — btop / nvim / lazygit no longer flicker mid-redraw.
- **OSC 8 hyperlinks**: tap-to-open URLs from `git`, `lazygit`, `gh` output. Region-list lookup avoids per-cell metadata.
- **HarfBuzz ligatures**: `'liga' on, 'calt' on` Paint feature → JetBrains Mono / FiraCode / Cascadia render `===`, `=>`, `!=` etc. as fused glyphs.
- **XTVERSION (`CSI > 0 q`) responder**: emulator answers `\eP>|Podroid <ver>\e\` so modern apps (Neovim ≥0.10) detect us correctly.
- **Sixel parser fixes** (upstream sixel4 had two bugs we patched locally): the `Pa;Pb;Pcq` intro form (chafa's default) was rejected; the multi-part flush trigger missed `$` and `-`. Both fixed.
- **Custom font import (Quick Settings → `+ Add` → SAF picker)**: drop in any `.ttf`, long-press to delete. Files live in app-private external dir; no permission prompts.
- **Custom theme import** from `terminalcolors.com` URLs: paste a theme page URL; we fetch the Alacritty TOML export, parse, and convert to our `.properties` format.
- **Top-sheet Quick Settings** (replaces sliding chip rows): drops down from header, font size slider, 4 visible chips per picker + `More` (search-aware full picker) + `+ Add` (font/theme import). Adapts portrait/landscape via height cap + scroll.
- **PAC-free QEMU coroutine** (`libqemujmp` shim): replaces Bionic's `sigsetjmp`/`siglongjmp` in `coroutine-ucontext.c` with raw aarch64 asm that doesn't use PAC. Fixes the SIGILL crash on Pixel 10 Pro XL when Downloads sharing was enabled.
- **PR_SET_PDEATHSIG launcher** (`libpodroid-launcher.so`): tiny wrapper that sets the parent-death signal before `execv`'ing QEMU, so the VM dies with the app instead of orphaning under PPID=1 across uninstalls/OOM/force-stop.
- **64 KB PTY buffers + dedup'd MSG_NEW_INPUT + reused RectF**: terminal-layer perf — fewer wakeups, less GC pressure on image-heavy frames.
- **`COLORTERM=truecolor` baked into rootfs** via `/etc/profile.d/podroid-color.sh` so apps detect 24-bit support.
- **`-cpu max,sve=off` and `tb-size=512`**: SVE's variable-length vector instructions are expensive to TCG-translate and unused by Node/Podman/etc.; disabling speeds general workloads. Bigger TB cache helps JIT-heavy guests like V8.
- **Kernel cmdline cleanups**: removed deprecated `elevator=mq-deadline` (now set per-device in `podroid-bootstrap` via sysfs); 9p `msize` lowered to virtio's actual cap (512000) to silence kernel warning.
- **Update-dialog version compare fix**: `1.1.x-debug` no longer shows up as "older than 1.1.x" in the update prompt.
- **Cursor-flicker on keyboard slide fix**: removed redundant `forceUpdateSizeFromView` call from layout listener (was racing with `tv.updateSize()` and triggering double resize).

### Recently shipped (1.1.7)
- **Zero-config Docker**: `podroid-bootstrap` bind-mounts `/var/lib/docker` to raw ext4 on every boot, sidestepping Linux's overlay-on-overlay rejection. `apk add docker; rc-service docker start; docker run` works end to end with the kernel `overlay2` driver (not the slower FUSE fallback).
- **Zero-config LXC**: `podroid-bootstrap` pre-creates `lxcbr0` (10.0.3.1/24) and the matching MASQUERADE rule. `apk add lxc lxc-templates; lxc-create -t busybox; lxc-start` works out of the box.
- **Complete container kernel features**: 36 forced `=y` Kconfig additions (BTRFS, IPVS, VXLAN, IPVLAN, MACVLAN, IPsec/XFRM, FTP/TFTP NAT helpers, AppArmor LSM stub, raw iptables, CFS bandwidth, etc.). Docker `check-config.sh` and `lxc-checkconfig` both report zero fixable complaints.
- **`/proc/config.gz` shipped**: `CONFIG_IKCONFIG=y` + `CONFIG_IKCONFIG_PROC=y` so any tool that wants to introspect the running kernel config can.
- **Terminal regression fix**: inittab now exports `TERM=xterm-256color` instead of `vt100`, restoring 256-color/mouse/italics in the in-app terminal (regressed during the OpenRC migration).

### Recently shipped (1.1.6)
- **OpenRC migration**: Real Alpine root running OpenRC as PID 1; switch_root replaces chroot pivot
- **Adaptive UI**: Phone-landscape and tablet layouts in setup / home / settings / terminal; chip-row selectors with horizontal fade indicators; Quick Settings dialog with left-edge vertical scrollbar
- **Issue #17 fix**: `podman exec -it` works rootful and rootless after switch_root replaces chroot
