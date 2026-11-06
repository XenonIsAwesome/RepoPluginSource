package io.github.xenonisawesome.reposource.core

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class HttpResult(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun text(): String = String(body, Charsets.UTF_8)
}

/** Failure with a message that is safe to show to the user (never contains tokens). */
class ResolveException(message: String, cause: Throwable? = null) : IOException(message, cause)

interface HttpFetcher {
    fun get(url: String, headers: Map<String, String>): HttpResult
    fun download(url: String, headers: Map<String, String>, target: Path, maxBytes: Long = MAX_ZIP_BYTES)

    companion object {
        const val MAX_ZIP_BYTES: Long = 512L * 1024 * 1024
    }
}

/**
 * Follows redirects manually so credentials are never forwarded to a different host (GitHub answers asset
 * requests with a 302 to a signed URL that must not receive the Authorization header).
 */
class JdkHttpFetcher(private val allowInsecure: Boolean = false) : HttpFetcher {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    override fun get(url: String, headers: Map<String, String>): HttpResult {
        var out: HttpResult? = null
        send(url, headers) { r ->
            val bytes = r.body().use { it.readNBytes(32 * 1024 * 1024) }
            out = HttpResult(r.statusCode(), flatten(r), bytes)
        }
        return out!!
    }

    override fun download(url: String, headers: Map<String, String>, target: Path, maxBytes: Long) {
        send(url, headers) { r ->
            if (r.statusCode() !in 200..299) throw ResolveException("Download failed with HTTP ${r.statusCode()}")
            r.body().use { ins ->
                Files.newOutputStream(target).use { os ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw ResolveException("The release asset is larger than the ${maxBytes / (1024 * 1024)} MB limit")
                        os.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private fun send(url: String, headers: Map<String, String>, consume: (HttpResponse<java.io.InputStream>) -> Unit) {
        var current = URI.create(url)
        var hdrs = headers
        repeat(6) {
            checkScheme(current)
            val b = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(60)).GET()
            hdrs.forEach { (k, v) -> b.header(k, v) }
            val resp = try {
                client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: IOException) {
                throw ResolveException("Could not connect to ${current.host}: ${e.message ?: e.javaClass.simpleName}", e)
            }
            val code = resp.statusCode()
            if (code in listOf(301, 302, 303, 307, 308)) {
                resp.body().close()
                val loc = resp.headers().firstValue("location").orElseThrow { ResolveException("Redirect without Location header") }
                val next = current.resolve(loc)
                if (next.host != current.host) hdrs = hdrs.filterKeys { it.lowercase() !in SENSITIVE }
                current = next
                return@repeat
            }
            consume(resp)
            return
        }
        throw ResolveException("Too many redirects")
    }

    private fun checkScheme(u: URI) {
        if (u.scheme == "https") return
        if (allowInsecure && u.scheme == "http") return
        throw ResolveException("Refusing to use non-HTTPS URL for ${u.host}")
    }

    private fun flatten(r: HttpResponse<*>): Map<String, String> =
        r.headers().map().mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull() ?: "" }

    private companion object {
        val SENSITIVE = setOf("authorization", "private-token")
    }
}
