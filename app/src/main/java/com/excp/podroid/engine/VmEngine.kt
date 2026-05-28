/*
 * Podroid
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package com.excp.podroid.engine

import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.VmRecord
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.StateFlow

/**
 * The seam between Podroid's UI/service layer and a concrete VM runtime
 * (QEMU/TCG today, AVF/pKVM on Pixel 8+ with `adb pm grant`). Implementations
 * are picked by `EngineFactory` at service-construction time and live for the
 * lifetime of the foreground service; swapping requires the VM to be stopped.
 */
interface VmEngine {
    val state: StateFlow<VmState>
    val bootStage: StateFlow<String>
    val consoleText: StateFlow<String>
    val terminalSession: TerminalSession?

    /** Identifier for logs + the diagnostic dialog. Stable, lowercase. */
    val backendId: String

    /**
     * Wall-clock millis at which the VM reached Running, or null when not
     * running. Defaulted so engine implementations compile unchanged; they
     * override it to drive the uptime readout.
     */
    val runningSinceMs: Long? get() = null

    /** QEMU-specific. Null on backends that don't use QMP (e.g. AVF). */
    val qmpClient: QmpClient?

    /**
     * Open a guest -> Android host-bridge connection for the current session, or
     * null if this backend/build can't (default). Called repeatedly by
     * HostRequestServer with retry, so a null here just means "not ready yet".
     */
    fun openHostTransport(): com.excp.podroid.engine.hostbridge.HostTransport? = null

    /**
     * Proxy delegate forwarded to the terminal session client. Set by the
     * terminal UI layer so the engine can relay events before the UI attaches.
     */
    var sessionClientDelegate: TerminalSessionClient?

    suspend fun start(portForwards: List<PortForwardRule>, config: VmConfig)
    fun stop()

    /** Create (or return the pre-started) terminal session wired to the bridge. */
    fun createTerminalSession(client: TerminalSessionClient): TerminalSession

    /** Number of independent terminal channels (= UI tabs) this backend
     *  exposes. Defaults to 1; backends that wire multiple virtio-console
     *  gettys (QEMU) advertise more so the terminal UI shows a tab strip. */
    val terminalChannelCount: Int get() = 1

    /** Create / fetch the terminal session bound to channel [index]. The
     *  default serves only the primary (index 0); backends advertising
     *  [terminalChannelCount] > 1 override this to spawn extra bridges
     *  against their additional channels. */
    fun createTerminalSession(index: Int, client: TerminalSessionClient): TerminalSession {
        require(index == 0) { "backend $backendId has no terminal at index $index" }
        return createTerminalSession(client)
    }

    /**
     * Apply a port-forward rule live to a running VM. No-op when state is not
     * Running — caller is expected to include the rule in [start]'s argument
     * list for the cold-start path. EngineHolder routes DataStore-flow diffs
     * through these methods.
     */
    suspend fun addPortForward(rule: PortForwardRule)
    suspend fun removePortForward(rule: PortForwardRule)

    /**
     * Backend-specific diagnostics for the export log: AVF stop/crash reason +
     * launch config, or QEMU exit code + stderr tail. Empty when there's nothing
     * backend-specific to add. Observational; never mutates VM state.
     */
    fun diagnosticsReport(): String = ""
}

/**
 * Engine-agnostic launch parameters. Strict superset of PodroidQemu.LaunchConfig
 * so existing call sites don't change.
 */
data class VmConfig(
    val ramMb: Int = 512,
    val cpus: Int = 1,
    val sshEnabled: Boolean = false,
    val androidIp: String = "unknown",
    val storageAccessEnabled: Boolean = false,
    val qemuExtraArgs: String = "",
    val kernelExtraCmdline: String = "",
    val verboseLogging: Boolean = false,
    val x11Dpi: Int = 96,
    val usbPassthroughEnabled: Boolean = false,
    /**
     * The VM the user picked to run. Required for start() — when null the
     * engine refuses to launch (no rootfs / no storage to mount). PodroidService
     * passes [VmRegistry.activeSnapshot] here; the UI must show a non-empty
     * VM picker so this never reaches the engine null.
     *
     * The engine reads the per-VM storage size, distro, and init system from
     * this record rather than from SettingsRepository.
     */
    val vmRecord: VmRecord? = null,
)
