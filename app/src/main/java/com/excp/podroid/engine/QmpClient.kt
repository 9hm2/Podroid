/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Minimal QMP (QEMU Machine Protocol) client for runtime VM management.
 * Used for adding/removing port forwards and hot-plugging USB passthrough
 * devices while the VM is running.
 */
package com.excp.podroid.engine

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.InputStreamReader

class QmpClient(private val socketPath: String) {

    companion object {
        private const val TAG = "QmpClient"
        private const val SOCKET_TIMEOUT_MS = 5000
    }

    /**
     * Run one QMP command over a fresh connection. When [sendFd] is non-null it
     * is handed to QEMU as SCM_RIGHTS ancillary data on the command write — the
     * mechanism `add-fd` needs to ingest a file descriptor (e.g. an Android
     * UsbDeviceConnection fd for usb-host passthrough).
     */
    private suspend fun exec(
        command: String,
        arguments: JSONObject?,
        sendFd: FileDescriptor? = null,
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            LocalSocket().use { socket ->
                socket.connect(
                    LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)
                )
                socket.soTimeout = SOCKET_TIMEOUT_MS

                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val out = socket.outputStream

                // Read QMP greeting, then enter command mode.
                Log.v(TAG, "QMP greeting: ${reader.readLine()}")
                out.write("{\"execute\":\"qmp_capabilities\"}\n".toByteArray())
                out.flush()
                Log.v(TAG, "Capabilities response: ${reader.readLine()}")

                val cmd = JSONObject().apply {
                    put("execute", command)
                    if (arguments != null) put("arguments", arguments)
                }
                // The fd (if any) must ride on the SAME write that carries the
                // command JSON: QEMU pairs the SCM_RIGHTS payload with the
                // add-fd command currently being parsed.
                if (sendFd != null) socket.setFileDescriptorsForSend(arrayOf(sendFd))
                out.write((cmd.toString() + "\n").toByteArray())
                out.flush()

                val response = reader.readLine()
                Log.d(TAG, "Command response ($command): $response")
                val json = JSONObject(response ?: "{}")
                if (json.has("error")) {
                    val desc = json.optJSONObject("error")?.optString("desc") ?: "QMP error"
                    Result.failure(RuntimeException(desc))
                } else {
                    Result.success(json)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "QMP command failed: $command", e)
            Result.failure(e)
        }
    }

    suspend fun execute(command: String, arguments: JSONObject? = null): Result<JSONObject> =
        exec(command, arguments)

    suspend fun addPortForward(hostPort: Int, guestPort: Int, protocol: String = "tcp"): Result<JSONObject> {
        val monitorCmd = "hostfwd_add net0 ${protocol}::${hostPort}-:${guestPort}"
        return execute(
            "human-monitor-command",
            JSONObject().put("command-line", monitorCmd)
        )
    }

    suspend fun removePortForward(hostPort: Int, protocol: String = "tcp"): Result<JSONObject> {
        val monitorCmd = "hostfwd_remove net0 ${protocol}::${hostPort}"
        return execute(
            "human-monitor-command",
            JSONObject().put("command-line", monitorCmd)
        )
    }

    /**
     * Pass [fd] to QEMU via SCM_RIGHTS and register it in a freshly-created fd
     * set. Returns the new fdset-id, referenceable from device properties as
     * `/dev/fdset/<id>` — used to hot-plug usb-host devices without QEMU ever
     * needing direct access to /dev/bus/usb (which is unreachable to an
     * unprivileged Android app).
     */
    suspend fun addFd(fd: FileDescriptor): Result<Int> =
        exec("add-fd", null, fd).mapCatching {
            it.getJSONObject("return").getInt("fdset-id")
        }

    suspend fun removeFd(fdSetId: Int): Result<JSONObject> =
        execute("remove-fd", JSONObject().put("fdset-id", fdSetId))

    suspend fun deviceAdd(arguments: JSONObject): Result<JSONObject> =
        execute("device_add", arguments)

    suspend fun deviceDel(id: String): Result<JSONObject> =
        execute("device_del", JSONObject().put("id", id))
}
