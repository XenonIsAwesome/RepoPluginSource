package io.github.xenonisawesome.reposource.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Serves the single combined /<secret>/updatePlugins.xml on 127.0.0.1 only.
 * [catalog] returns the XML or throws (details stay in the IDE, not in the HTTP body).
 */
class CatalogHttpServer(
    private val secret: String,
    private val catalog: () -> String,
    private val onError: (e: Throwable) -> Unit = {},
) {
    private var server: HttpServer? = null
    val port: Int get() = server?.address?.port ?: -1
    val running: Boolean get() = server != null

    /** Binds [preferredPort] if possible, otherwise any free port. Returns the bound port. */
    @Synchronized
    fun start(preferredPort: Int): Int {
        server?.let { return it.address.port }
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val s = try {
            if (preferredPort in 1..65535) HttpServer.create(InetSocketAddress(loopback, preferredPort), 0)
            else HttpServer.create(InetSocketAddress(loopback, 0), 0)
        } catch (e: java.io.IOException) {
            HttpServer.create(InetSocketAddress(loopback, 0), 0)
        }
        s.createContext("/") { ex -> handle(ex) }
        s.executor = Executors.newCachedThreadPool { r -> Thread(r, "RepoReleaseSource-http").apply { isDaemon = true } }
        s.start()
        server = s
        return s.address.port
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        (server?.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        server = null
    }

    private fun handle(ex: HttpExchange) {
        ex.use {
            val parts = ex.requestURI.path.split('/').filter { it.isNotEmpty() }
            val okToken = parts.size == 2 && MessageDigest.isEqual(parts[0].toByteArray(), secret.toByteArray())
            if (ex.requestMethod != "GET" || !okToken || parts[1] != "updatePlugins.xml") {
                reply(ex, 404, "Not found")
                return
            }
            try {
                reply(ex, 200, catalog(), "application/xml; charset=utf-8")
            } catch (e: Throwable) {
                onError(e)
                reply(ex, 502, "Could not build the catalog; see Settings | Tools | Repo Release Sources.")
            }
        }
    }

    private fun reply(ex: HttpExchange, code: Int, body: String, type: String = "text/plain; charset=utf-8") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
