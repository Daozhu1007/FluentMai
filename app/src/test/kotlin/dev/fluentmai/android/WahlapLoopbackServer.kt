package dev.fluentmai.android

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Local deterministic HTTP fixture; no Wahlap traffic or real credentials. */
internal class WahlapLoopbackServer(
    private val respond: (Request) -> Response = { Response() },
) : AutoCloseable {
    data class Request(val path: String, val headers: Map<String, String>)
    data class Response(
        val status: Int = 200,
        val body: String = "<html><body>fixture</body></html>",
        val headers: Map<String, String> = emptyMap(),
    )

    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()

    init {
        executor.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                executor.submit {
                    socket.use {
                        it.soTimeout = 5_000
                        val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val path = reader.readLine().split(' ')[1].substringBefore('?')
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val separator = line.indexOf(':')
                            if (separator > 0) headers[line.substring(0, separator).lowercase()] =
                                line.substring(separator + 1).trim()
                        }
                        val request = Request(path, headers)
                        requests += request
                        val response = respond(request)
                        val body = response.body.toByteArray(Charsets.UTF_8)
                        val head = buildString {
                            append("HTTP/1.1 ${response.status} Fixture\r\n")
                            append("Content-Type: text/html; charset=UTF-8\r\n")
                            append("Content-Length: ${body.size}\r\nConnection: close\r\n")
                            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                            append("\r\n")
                        }
                        it.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1) + body)
                        it.getOutputStream().flush()
                    }
                }
            }
        }
    }

    fun url(path: String): String = "http://127.0.0.1:${server.localPort}$path"
    fun requestsAt(path: String): List<Request> = synchronized(requests) { requests.filter { it.path == path } }

    override fun close() {
        server.close()
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
