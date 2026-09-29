package dev.fluentmai.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.fluentmai.android.core.privacy.PrivacyRedactor
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WahlapHookHttpService : Service() {
    private var hookServer: SimpleHttpServer? = null
    private var redirectServer: SimpleHttpServer? = null
    private val redactor = PrivacyRedactor()
    private val authUrlClient by lazy { WahlapWechatAuthUrlClient(redactor) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopServers()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        promoteToForeground()
        startServers()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopServers()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun startServers() {
        var hookStarted = true
        var redirectStarted = true
        if (hookServer == null) {
            hookServer = SimpleHttpServer(PORT) { path -> serveHook(path) }
            hookStarted = hookServer?.start() == true
        }
        if (redirectServer == null) {
            redirectServer = SimpleHttpServer(REDIRECT_PORT) { serveCapturedPage() }
            redirectStarted = redirectServer?.start() == true
        }
        if (hookStarted && redirectStarted) {
            WahlapHookBridge.setStatus("本地 Hook 服务已启动，请复制链接到微信打开。")
        } else {
            WahlapHookBridge.setStatus("本地 Hook 服务启动失败：端口未能监听。")
        }
    }

    private fun stopServers() {
        hookServer?.stop()
        redirectServer?.stop()
        hookServer = null
        redirectServer = null
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "FluentMai Hook",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持微信 Hook 本地服务运行"
            }
            manager.createNotificationChannel(channel)
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("FluentMai 正在等待微信授权")
            .setContentText("本地 Hook 链接已保持可访问")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun serveHook(path: String): HookHttpResponse =
        when {
            path == "/0" || path.startsWith("/auth/maimai") -> serveMaimaiAuthRedirect()
            else -> HookHttpResponse.html(404, "FluentMai Hook 链接无效。")
        }

    private fun serveMaimaiAuthRedirect(): HookHttpResponse {
        if (WahlapHookBridge.isImporting()) {
            return HookHttpResponse.html(202, "查分进程已经开始，请切回 FluentMai 等待导入完成。")
        }
        return runCatching {
            WahlapHookBridge.setStatus("微信已打开 Hook 链接，正在生成舞萌授权跳转。")
            HookHttpResponse.redirect(authUrlClient.maimaiDxAuthUrl())
        }.getOrElse { error ->
            val safeMessage = redactor.redact(error.message ?: error::class.java.simpleName)
            Log.e(TAG, "Failed to build Wahlap auth URL: $safeMessage")
            HookHttpResponse.html(500, "生成舞萌授权跳转失败：$safeMessage")
        }
    }

    private fun serveCapturedPage(): HookHttpResponse =
        HookHttpResponse.html(202, "登录信息已捕获，可以切回 FluentMai 等待成绩导入。")

    companion object {
        const val PORT = 8284
        const val REDIRECT_PORT = 9457
        const val HOOK_URL = "http://127.0.0.1:$PORT/0"
        private const val NOTIFICATION_ID = 8284
        private const val NOTIFICATION_CHANNEL_ID = "fluentmai_hook"
        private const val ACTION_STOP = "dev.fluentmai.android.action.STOP_HOOK_HTTP"
        private const val TAG = "WahlapHookHttp"

        fun start(context: Context) {
            val intent = Intent(context, WahlapHookHttpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WahlapHookHttpService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}

private data class HookHttpResponse(
    val statusCode: Int,
    val reason: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
) {
    companion object {
        fun redirect(location: String): HookHttpResponse =
            HookHttpResponse(
                statusCode = 302,
                reason = "Found",
                headers = mapOf(
                    "Location" to location,
                    "Cache-Control" to "no-cache, no-store, must-revalidate",
                    "Pragma" to "no-cache",
                    "Expires" to "0",
                ),
            )

        fun html(statusCode: Int, text: String): HookHttpResponse {
            val escapedText = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            return HookHttpResponse(
                statusCode = statusCode,
                reason = when (statusCode) {
                    202 -> "Accepted"
                    404 -> "Not Found"
                    500 -> "Internal Server Error"
                    else -> "OK"
                },
                headers = mapOf("Content-Type" to "text/html; charset=utf-8"),
                body = "<html><body><h1>$escapedText</h1></body></html>",
            )
        }
    }
}

private class SimpleHttpServer(
    private val port: Int,
    private val handler: (String) -> HookHttpResponse,
) {
    @Volatile
    private var stopped = false
    @Volatile
    private var ready = false
    private val readyLatch = CountDownLatch(1)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    fun start(): Boolean {
        if (serverThread != null) return ready
        stopped = false
        ready = false
        serverThread = thread(name = "FluentMaiHookHttp-$port", isDaemon = true) {
            runServer()
        }
        if (!readyLatch.await(1500, TimeUnit.MILLISECONDS)) {
            Log.w("FluentMaiHookHttp", "HTTP server bind timed out on port=$port")
        }
        return ready
    }

    fun stop() {
        stopped = true
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverThread = null
    }

    private fun runServer() {
        try {
            ServerSocket().use { socket ->
                serverSocket = socket
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", port))
                ready = true
                readyLatch.countDown()
                while (!stopped) {
                    val client = try {
                        socket.accept()
                    } catch (_: IOException) {
                        if (stopped) return
                        continue
                    }
                    thread(name = "FluentMaiHookHttpClient-$port", isDaemon = true) {
                        client.use { handleClient(it) }
                    }
                }
            }
        } catch (error: Exception) {
            readyLatch.countDown()
            ready = false
            if (!stopped) {
                Log.e("FluentMaiHookHttp", "HTTP server failed on port=$port", error)
                WahlapHookBridge.setStatus("本地 Hook 服务启动失败：${error::class.java.simpleName}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 3000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine().orEmpty()
        val headers = mutableMapOf<String, String>()
        runCatching {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                }
            }
        }.onFailure { error ->
            if (error !is java.net.SocketTimeoutException) {
                Log.w("FluentMaiHookHttp", "Failed to read HTTP headers on port=$port", error)
            }
        }
        val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
        Log.i(
            "FluentMaiHookHttp",
            "HTTP hit port=$port remote=${socket.remoteSocketAddress} request=$requestLine host=${headers["host"]}",
        )
        if (port == WahlapHookHttpService.PORT) {
            WahlapHookBridge.setStatus("Hook 已收到请求：$path")
        }
        writeResponse(socket.getOutputStream(), handler(path))
    }

    private fun writeResponse(output: OutputStream, response: HookHttpResponse) {
        val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
        val headers = response.headers + mapOf(
            "Content-Length" to bodyBytes.size.toString(),
            "Connection" to "close",
        )
        val head = buildString {
            append("HTTP/1.1 ${response.statusCode} ${response.reason}\r\n")
            headers.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        output.write(head)
        output.write(bodyBytes)
        output.flush()
    }
}
