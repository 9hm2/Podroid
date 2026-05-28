/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Parses one guest request line, executes it, and returns one response line.
 * Backend-neutral and free of Android framework deps (transport + posting are
 * injected) so it is fully unit-testable.
 */
package com.excp.podroid.engine.hostbridge

import com.excp.podroid.data.repository.PortForwardRule

class HostRequestDispatcher(
    private val notifications: NotificationPoster,
    private val addForward: suspend (PortForwardRule) -> Unit,
    private val removeForward: suspend (PortForwardRule) -> Unit,
    private val listForwards: suspend () -> List<PortForwardRule>,
) {
    private val validProtocols = setOf("tcp", "udp")

    suspend fun handle(line: String): String {
        val parts = line.trim().split(" ")
        return try {
            when (parts[0]) {
                "NOTIFY" -> handleNotify(parts)
                "FWD-ADD" -> handleFwdAdd(parts)
                "FWD-REMOVE" -> handleFwdRemove(parts)
                "FWD-LIST" -> handleFwdList()
                "PING" -> "PONG"
                else -> HostProtocol.err("bad request")
            }
        } catch (e: Exception) {
            HostProtocol.err(e.message ?: e.javaClass.simpleName)
        }
    }

    // NOTIFY <prio> <id|-> <b64title|-> <b64body>
    private fun handleNotify(p: List<String>): String {
        if (p.size != 5) return HostProtocol.err("bad request")
        val prio = p[1]
        if (prio !in HostProtocol.VALID_PRIORITIES) return HostProtocol.err("bad priority")
        val id = if (p[2] == "-") null else p[2].toIntOrNull() ?: return HostProtocol.err("bad id")
        val title = if (p[3] == "-") null else HostProtocol.dec(p[3]) ?: return HostProtocol.err("bad title")
        val body = HostProtocol.dec(p[4]) ?: return HostProtocol.err("bad body")
        if (!notifications.notificationsPermitted()) return HostProtocol.err("notifications not permitted")
        val used = notifications.post(title, body, prio, id)
        return HostProtocol.ok(used.toString())
    }

    // FWD-ADD <hostPort> <guestPort> <proto>
    private suspend fun handleFwdAdd(p: List<String>): String {
        if (p.size != 4) return HostProtocol.err("bad request")
        val host = p[1].toIntOrNull() ?: return HostProtocol.err("bad host port")
        val guest = p[2].toIntOrNull() ?: return HostProtocol.err("bad guest port")
        val proto = p[3]
        if (host !in 1..65535 || guest !in 1..65535) return HostProtocol.err("port out of range")
        if (proto !in validProtocols) return HostProtocol.err("bad protocol")
        addForward(PortForwardRule(host, guest, proto))
        return HostProtocol.ok()
    }

    // FWD-REMOVE <hostPort> <proto>
    private suspend fun handleFwdRemove(p: List<String>): String {
        if (p.size != 3) return HostProtocol.err("bad request")
        val host = p[1].toIntOrNull() ?: return HostProtocol.err("bad host port")
        val proto = p[2]
        val existing = listForwards().firstOrNull { it.hostPort == host && it.protocol == proto }
            ?: return HostProtocol.err("no such forward")
        removeForward(existing)
        return HostProtocol.ok()
    }

    private suspend fun handleFwdList(): String {
        val table = listForwards().joinToString("\n") { "${it.hostPort} ${it.guestPort} ${it.protocol}" }
        return HostProtocol.ok(HostProtocol.enc(table))
    }
}
