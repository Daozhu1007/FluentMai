package dev.fluentmai.android

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Raw loopback HTTP/1.1 fixture with real flushed chunks, stalls and broken framing. */
internal class WahlapStreamingServer(private val serve: (String, Socket) -> Unit) : AutoCloseable {
    private val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    val requests = Collections.synchronizedList(mutableListOf<String>())
    val activeConnections = AtomicInteger()
    val failures = Collections.synchronizedList(mutableListOf<Throwable>())

    init {
        executor.submit {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: IOException) { break }
                sockets += socket
                activeConnections.incrementAndGet()
                executor.submit {
                    try {
                        socket.use {
                            it.soTimeout = 10_000
                            val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            val path = reader.readLine().split(' ')[1]
                            while (!reader.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                            requests += path
                            serve(path, it)
                        }
                    } catch (_: IOException) {
                        // A reset/broken pipe is expected when the client cancels or hits a limit.
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (error: Throwable) {
                        failures += error
                    } finally {
                        sockets -= socket
                        activeConnections.decrementAndGet()
                    }
                }
            }
        }
    }

    fun url(path: String = "/score") = "http://127.0.0.1:${listener.localPort}$path"

    override fun close() {
        listener.close()
        synchronized(sockets) { sockets.toList() }.forEach { it.close() }
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Fixture worker leaked" }
        check(failures.isEmpty()) { "Fixture worker failed: ${failures.map { it.javaClass.simpleName }}" }
    }
}

internal fun Socket.scoreHeaders(length: Long? = null, charset: String = "UTF-8") {
    val framing = length?.let { "Content-Length: $it" } ?: "Transfer-Encoding: chunked"
    val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=$charset\r\n" +
        "$framing\r\nConnection: close\r\n\r\n"
    getOutputStream().apply { write(header.toByteArray(Charsets.ISO_8859_1)); flush() }
}

internal fun Socket.scoreChunk(bytes: ByteArray) {
    getOutputStream().apply {
        write("${bytes.size.toString(16)}\r\n".toByteArray())
        write(bytes)
        write("\r\n".toByteArray())
        flush()
    }
}

internal fun Socket.finishChunks() { getOutputStream().apply { write("0\r\n\r\n".toByteArray()); flush() } }

/** Wait until the client closes this request, rather than concealing a leaked socket with a timer. */
internal fun Socket.waitForClientClose() { check(getInputStream().read() == -1) }
