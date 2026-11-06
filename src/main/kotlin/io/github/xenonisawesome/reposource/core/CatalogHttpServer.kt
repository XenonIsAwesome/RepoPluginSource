package io.github.xenonisawesome.reposource.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Serves /<secret>/<repoId>/updatePlugins.xml on 127.0.0.1 only.
 * [catalogFor] returns the XML or throws [ResolveException] (shown to the user in the settings page, not in the HTTP body).
 */
class CatalogHttpServer(
    private val secret: String,
    private val catalogFor: (repoId: String) -> String?,
    private val onError: (repoId: String, e: Throwable) -> Unit = { _, _ -> },
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
            val okToken = parts.size == 3 && MessageDigest.isEqual(parts[0].toByteArray(), secret.toByteArray())
            if (ex.requestMethod != "GET" || !okToken || parts[2] != "updatePlugins.xml") {
                reply(ex, 404, "Not found")
                return
            }
            val repoId = parts[1]
            try {
                val xml = catalogFor(repoId)
                if (xml == null) reply(ex, 404, "Not found") else reply(ex, 200, xml, "application/xml; charset=utf-8")
            } catch (e: Throwable) {
                onError(repoId, e)
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
