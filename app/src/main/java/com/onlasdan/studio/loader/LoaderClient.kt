package com.onlasdan.studio.loader

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Client for the in-game modloader plugin (`libimgtxd_hook.so`, an AML mod
 * inside GTA SA) — protocol in `imgtxd-hook/docs/PROTOCOL.md` (v1.1).
 *
 * Transport is a loopback TCP connection to 127.0.0.1:38177 (an abstract
 * Unix socket was dropped: SELinux per-app isolation blocks cross-app
 * connects on it, loopback TCP is not affected). One connection carries
 * exactly one message: a 24-byte little-endian header; SUBSTITUTE then
 * streams the raw model bytes over the same connection. The plugin closes
 * the connection after each message. All functions are main-safe (IO
 * dispatcher) and never throw.
 */
object LoaderClient {

    private const val TAG = "LoaderClient"
    private const val HOST = "127.0.0.1"
    private const val PORT = 38177
    private const val MAGIC = 0x44585449 // 'ITXD'
    private const val CMD_SUBSTITUTE = 1
    private const val CMD_RELOAD = 2
    private const val CMD_PING = 3
    private const val HEADER_SIZE = 24
    private const val PING_REPLY_OK = 0x01
    private const val CONNECT_TIMEOUT_MS = 1500
    private const val IO_TIMEOUT_MS = 3000
    private const val WRITE_CHUNK_BYTES = 64 * 1024

    /** Largest payload [substitute] accepts; shared with payload readers. */
    const val MAX_PAYLOAD_BYTES = 512L * 1024 * 1024

    const val MAX_MODEL_ID = 26315

    private const val MSG_NOT_CONNECTED =
        "Tidak dapat terhubung ke game. Pastikan GTA SA sedang berjalan dan plugin Modloader aktif."
    private const val MSG_MODEL_ID_RANGE = "Model ID harus antara 0 dan $MAX_MODEL_ID."

    private fun header(cmd: Int, modelId: Int, payloadSize: Long): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(MAGIC)
            putShort(cmd.toShort())
            putShort(0)
            putInt(modelId)
            putInt(0)
            putLong(payloadSize)
        }.array()

    private fun connect(): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
        socket.soTimeout = IO_TIMEOUT_MS
        return socket
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: IOException) {
            // Already closed by the peer; nothing to do.
        }
    }

    /** True when the plugin answers PING with 0x01 within the timeout. */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = connect()
            socket.outputStream.apply {
                write(header(CMD_PING, modelId = 0, payloadSize = 0))
                flush()
            }
            socket.inputStream.read() == PING_REPLY_OK
        } catch (e: Exception) {
            Log.d(TAG, "ping failed: ${e.message}")
            false
        } finally {
            closeQuietly(socket)
        }
    }

    /**
     * Hands [bytes] (a raw DFF/TXD) to the plugin as the pending substitute
     * for [modelId]: header first, then the payload streamed over the same
     * connection.
     */
    suspend fun substitute(modelId: Int, bytes: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (modelId !in 0..MAX_MODEL_ID) {
                return@withContext Result.failure(IllegalArgumentException(MSG_MODEL_ID_RANGE))
            }
            if (bytes.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Berkas model kosong."))
            }
            if (bytes.size.toLong() > MAX_PAYLOAD_BYTES) {
                return@withContext Result.failure(
                    IllegalArgumentException("Berkas model terlalu besar (maksimum 512 MB).")
                )
            }
            var socket: Socket? = null
            try {
                socket = connect()
                socket.outputStream.apply {
                    write(header(CMD_SUBSTITUTE, modelId, bytes.size.toLong()))
                    var offset = 0
                    while (offset < bytes.size) {
                        val end = minOf(offset + WRITE_CHUNK_BYTES, bytes.size)
                        write(bytes, offset, end - offset)
                        offset = end
                    }
                    flush()
                }
                Log.d(TAG, "substitute sent: modelId=$modelId bytes=${bytes.size}")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "substitute failed: ${e.message}")
                Result.failure(IOException(MSG_NOT_CONNECTED, e))
            } finally {
                closeQuietly(socket)
            }
        }

    /** Asks the plugin to reload [modelId] in game (RemoveModel + RequestModel). */
    suspend fun reload(modelId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        if (modelId !in 0..MAX_MODEL_ID) {
            return@withContext Result.failure(IllegalArgumentException(MSG_MODEL_ID_RANGE))
        }
        var socket: Socket? = null
        try {
            socket = connect()
            socket.outputStream.apply {
                write(header(CMD_RELOAD, modelId, payloadSize = 0))
                flush()
            }
            Log.d(TAG, "reload sent: modelId=$modelId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "reload failed: ${e.message}")
            Result.failure(IOException(MSG_NOT_CONNECTED, e))
        } finally {
            closeQuietly(socket)
        }
    }
}
