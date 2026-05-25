/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Tiny HTTP client for talking to the on-device llama-server. Streams
 * Server-Sent-Events token-by-token via a Flow<String>. Used by both
 * Android-side UI features (Custom Command synthesis, output explainer)
 * and as the reference implementation behind the VM-side podroid-ai
 * wrapper script — but inside the VM that goes through SLIRP loopback
 * (10.0.2.2:port) using plain curl, not this class.
 *
 * No external HTTP dependency — HttpURLConnection is enough for a single
 * streaming POST and keeps the APK lean.
 */
package com.excp.podroid.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiEngineClient @Inject constructor(
    private val repository: AiEngineRepository,
) {
    /** Streamed chat completion. Emits text deltas as the model writes
     *  them; collector cancellation closes the underlying socket so the
     *  server can drop generation. */
    fun chatStream(
        userPrompt: String,
        systemPrompt: String? = null,
        maxTokens: Int = 512,
        temperature: Double = 0.4,
    ): Flow<String> = flow {
        val port = repository.snapshotPort()
        val url = URL("http://127.0.0.1:$port/v1/chat/completions")
        val body = JSONObject().apply {
            put("model", "podroid")
            put("stream", true)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("messages", JSONArray().apply {
                if (!systemPrompt.isNullOrBlank()) put(JSONObject().apply {
                    put("role", "system"); put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user"); put("content", userPrompt)
                })
            })
        }.toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 120_000  // generation can be slow on big models
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                throw RuntimeException("HTTP ${conn.responseCode} from llama-server: ${conn.errorStream?.bufferedReader()?.readText()}")
            }
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val chunk = runCatching {
                        JSONObject(payload).getJSONArray("choices").getJSONObject(0)
                            .optJSONObject("delta")?.optString("content")
                            ?: JSONObject(payload).getJSONArray("choices").getJSONObject(0)
                                .optString("text", "")
                    }.getOrNull().orEmpty()
                    if (chunk.isNotEmpty()) emit(chunk)
                }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }.flowOn(Dispatchers.IO)

    /** Single-call convenience: collects the stream and returns the full
     *  response. Useful for one-shot Custom Command synthesis where the
     *  UI shows a spinner, not a live cursor. */
    suspend fun chat(prompt: String, systemPrompt: String? = null): String {
        val sb = StringBuilder()
        chatStream(prompt, systemPrompt).collect { sb.append(it) }
        return sb.toString()
    }
}
