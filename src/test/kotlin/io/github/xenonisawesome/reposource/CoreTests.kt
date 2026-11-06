package io.github.xenonisawesome.reposource

import com.sun.net.httpserver.HttpServer
import io.github.xenonisawesome.reposource.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun jar(pluginXml: String): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
        z.putNextEntry(ZipEntry("META-INF/plugin.xml")); z.write(pluginXml.toByteArray()); z.closeEntry()
    }
    return bos.toByteArray()
}

private fun pluginZip(id: String, version: String, since: String? = "261"): ByteArray {
    val xml = """<idea-plugin><id>$id</id><name>Name $id</name><version>$version</version><vendor>V</vendor>
        <description>Desc &amp; more</description>${if (since != null) "<idea-version since-build=\"$since\"/>" else ""}</idea-plugin>"""
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { z ->
        z.putNextEntry(ZipEntry("plug/lib/dep.jar")); z.write(jar("<other/>")); z.closeEntry()
        z.putNextEntry(ZipEntry("plug/lib/plug.jar")); z.write(jar(xml)); z.closeEntry()
    }
    return bos.toByteArray()
}

class RepoUrlParserTest {
    @Test fun github() {
        val r = RepoUrlParser.parse("github.com/owner/repo.git")
        assertEquals(Provider.GITHUB, r.provider); assertEquals("owner/repo", r.path)
        assertEquals("owner/repo", RepoUrlParser.parse("https://github.com/owner/repo/releases/tag/v1").path)
    }
    @Test fun gitlabSelfHostedAndSubgroups() {
        val r = RepoUrlParser.parse("https://git.example.com:8443/g/sub/proj/-/releases")
        assertEquals(Provider.GITLAB, r.provider); assertEquals("https://git.example.com:8443", r.baseUrl); assertEquals("g/sub/proj", r.path)
        assertTrue(r.id.matches(Regex("[a-z0-9._-]+")))
    }
    @Test fun rejects() {
        for (bad in listOf("", "http://github.com/o/r", "https://github.com/onlyowner", "ftp://x/y/z"))
            assertThrows(RepoUrlException::class.java) { RepoUrlParser.parse(bad) }
    }
}

class XmlAndZipTest {
    @Test fun xmlEscapesAndKeepsExactVersion() {
        val xml = CatalogXml.render(listOf(CatalogPlugin("a&b", "N<1>", "1.0.0-SNAPSHOT", "261", null, "d\"q", "v", "https://x/y?a=1&b=2")))
        assertTrue(xml.contains("id=\"a&amp;b\""))
        assertTrue(xml.contains("version=\"1.0.0-SNAPSHOT\""))
        assertTrue(xml.contains("url=\"https://x/y?a=1&amp;b=2\""))
        assertTrue(xml.contains("<idea-version since-build=\"261\"/>"))
        assertFalse(xml.contains("until-build"))
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    }
    @Test fun readsDescriptorFromNestedJar() {
        val f = java.nio.file.Files.createTempFile("t", ".zip")
        try {
            java.nio.file.Files.write(f, pluginZip("com.x", "2.3.4"))
            val d = PluginZipReader.read(f)!!
            assertEquals("com.x", d.id); assertEquals("2.3.4", d.version); assertEquals("261", d.sinceBuild); assertNull(d.untilBuild)
            assertEquals("Desc & more", d.description)
        } finally { java.nio.file.Files.deleteIfExists(f) }
    }
    @Test fun miniJson() {
        val m = MiniJson.asMap(MiniJson.parse("""{"a":[1,2.5,"x\nA",true,null],"b":{"c":-3}}"""))
        assertEquals(listOf(1L, 2.5, "x\nA", true, null), m["a"])
        assertEquals(-3L, MiniJson.asMap(m["b"])["c"])
    }
}

class MockServerTest {
    private lateinit var srv: HttpServer
    private val base get() = "http://127.0.0.1:${srv.address.port}"
    private val apiHits = AtomicInteger(); private val zipHits = AtomicInteger(); private val notModified = AtomicInteger()
    @Volatile private var releaseId = 1L
    @Volatile private var version = "1.0.0"
    @Volatile private var seenAuth: String? = null
    @Volatile private var seenPrivateToken: String? = null

    @Before fun up() {
        srv = HttpServer.create(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 0)
        srv.createContext("/repos/o/r/releases/latest") { ex ->
            apiHits.incrementAndGet(); seenAuth = ex.requestHeaders.getFirst("Authorization")
            val etag = "\"e$releaseId\""
            if (ex.requestHeaders.getFirst("If-None-Match") == etag) { notModified.incrementAndGet(); ex.sendResponseHeaders(304, -1); ex.close(); return@createContext }
            val body = """{"id":$releaseId,"tag_name":"v$version","assets":[
              {"id":${releaseId * 10},"name":"plugin-$version.zip","url":"$base/repos/o/r/releases/assets/${releaseId * 10}","browser_download_url":"$base/dl/${releaseId * 10}"},
              {"id":${releaseId * 10 + 1},"name":"notes.txt","url":"$base/x","browser_download_url":"$base/x"}]}""".toByteArray()
            ex.responseHeaders.add("ETag", etag); ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        srv.createContext("/dl/") { ex -> zipHits.incrementAndGet(); val b = pluginZip("com.x", version); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        srv.createContext("/repos/o/r/releases/assets/") { ex ->
            zipHits.incrementAndGet(); seenAuth = ex.requestHeaders.getFirst("Authorization")
            val b = pluginZip("com.x", version); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        srv.createContext("/api/v4/projects/") { ex ->
            seenPrivateToken = ex.requestHeaders.getFirst("PRIVATE-TOKEN")
            val body = """{"tag_name":"v$version","released_at":"2026-01-01","assets":{"links":[
              {"id":7,"name":"p.zip","url":"$base/pkg/p.zip","direct_asset_url":"$base/pkg/p.zip","link_type":"package"}]}}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        srv.createContext("/pkg/") { ex -> seenPrivateToken = ex.requestHeaders.getFirst("PRIVATE-TOKEN"); zipHits.incrementAndGet()
            val b = pluginZip("com.gl", version, since = null); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        srv.start()
    }
    @After fun down() = srv.stop(0)

    private val gh = RepoRef(Provider.GITHUB, "https://github.com", "o/r")
    private fun engine(now: () -> Long = System::currentTimeMillis, ttl: Long = 60_000): CatalogEngine {
        val http = JdkHttpFetcher(allowInsecure = true)
        return CatalogEngine(http, ResolverCache(ttl, now), GitHubSource(http, base), GitLabSource(http), requireHttpsPluginUrl = false)
    }

    @Test fun githubPublicUsesBrowserUrlAndExactVersion() {
        val c = engine().resolve(gh, null)
        assertEquals("1.0.0", c.plugins.single().version)
        assertEquals("$base/dl/10", c.plugins.single().downloadUrl)
        assertNull(seenAuth)
    }

    @Test fun cacheAvoidsRedownloadAndApiWithinTtl() {
        val e = engine()
        e.resolve(gh, null); e.resolve(gh, null)
        assertEquals(1, apiHits.get()); assertEquals(1, zipHits.get())
    }

    @Test fun afterTtlUsesEtagAndKeepsZipCached() {
        var t = 0L
        val e = engine({ t }, 1000)
        e.resolve(gh, null)
        t = 5000
        e.resolve(gh, null)
        assertEquals(2, apiHits.get()); assertEquals(1, notModified.get()); assertEquals(1, zipHits.get())
    }

    @Test fun newReleaseRedownloadsAndReportsNewVersion() {
        var t = 0L
        val e = engine({ t }, 1000)
        assertEquals("1.0.0", e.resolve(gh, null).plugins.single().version)
        releaseId = 2; version = "1.1.0"; t = 5000
        assertEquals("1.1.0", e.resolve(gh, null).plugins.single().version)
        assertEquals(2, zipHits.get())
    }

    @Test fun githubPrivateUsesApiAssetUrlWithBearer() {
        val c = engine().resolve(gh, "s3cret")
        assertEquals("$base/repos/o/r/releases/assets/10", c.plugins.single().downloadUrl)
        assertEquals("Bearer s3cret", seenAuth)
        assertFalse(CatalogXml.render(c.plugins).contains("s3cret"))
    }

    @Test fun gitlabSendsPrivateTokenAndPluginWithoutSinceBuild() {
        val http = JdkHttpFetcher(allowInsecure = true)
        val repoHttp = RepoRef(Provider.GITLAB, base, "g/p")
        val e = CatalogEngine(http, ResolverCache(), GitHubSource(http, base), GitLabSource(http), requireHttpsPluginUrl = false)
        val c = e.resolve(repoHttp, "glpat")
        assertEquals("com.gl", c.plugins.single().id); assertNull(c.plugins.single().sinceBuild)
        assertEquals("glpat", seenPrivateToken)
    }

    @Test fun errorsAreFriendly() {
        val http = JdkHttpFetcher(allowInsecure = true)
        val e = CatalogEngine(http, ResolverCache(), GitHubSource(http, base), GitLabSource(http), requireHttpsPluginUrl = false)
        val ex = assertThrows(ResolveException::class.java) { e.resolve(RepoRef(Provider.GITHUB, "https://github.com", "no/such"), null) }
        assertTrue(ex.message!!.contains("private"))
    }

    @Test fun httpsRequiredForPluginUrlByDefault() {
        val http = JdkHttpFetcher(allowInsecure = true)
        val e = CatalogEngine(http, ResolverCache(), GitHubSource(http, base), GitLabSource(http))
        assertThrows(ResolveException::class.java) { e.resolve(gh, null) }
    }

    @Test fun credentialsNotForwardedAcrossHosts() {
        // /redir on host 127.0.0.1 redirects to "localhost" (different host name): headers must be dropped.
        var got: String? = "unset"
        srv.createContext("/final") { ex -> got = ex.requestHeaders.getFirst("Authorization"); ex.sendResponseHeaders(200, 2); ex.responseBody.use { it.write("ok".toByteArray()) } }
        srv.createContext("/redir") { ex -> ex.responseHeaders.add("Location", "http://localhost:${srv.address.port}/final"); ex.sendResponseHeaders(302, -1); ex.close() }
        val r = JdkHttpFetcher(allowInsecure = true).get("$base/redir", mapOf("Authorization" to "Bearer x"))
        assertEquals(200, r.status); assertNull(got)
    }
}

class ServerAndAuthTest {
    @Test fun serverBindsLoopbackChecksTokenAndFallsBackOnPortConflict() {
        val a = CatalogHttpServer("tok", { id -> if (id == "r1") "<plugins/>" else null })
        val port = a.start(0)
        try {
            fun code(path: String) = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).responseCode
            assertEquals(200, code("/tok/r1/updatePlugins.xml"))
            assertEquals(404, code("/wrong/r1/updatePlugins.xml"))
            assertEquals(404, code("/tok/nope/updatePlugins.xml"))
            assertEquals(404, code("/"))
            assertEquals("127.0.0.1", a.javaClass.getDeclaredField("server").also { it.isAccessible = true }.get(a).let { (it as HttpServer).address.address.hostAddress })
            val b = CatalogHttpServer("tok", { null })
            val port2 = b.start(port) // preferred port occupied
            try { assertNotEquals(port, port2) } finally { b.stop() }
        } finally { a.stop() }
    }

    @Test fun serverErrorBodyHasNoDetails() {
        val s = CatalogHttpServer("t", { throw ResolveException("secret detail") })
        val p = s.start(0)
        try {
            val c = URL("http://127.0.0.1:$p/t/x/updatePlugins.xml").openConnection() as HttpURLConnection
            assertEquals(502, c.responseCode)
            assertFalse(c.errorStream.readAllBytes().toString(Charsets.UTF_8).contains("secret"))
        } finally { s.stop() }
    }

    @Test fun authRoutingOnlyMatchesApiAssetsNotRedirectTargets() {
        val gh = RepoRef(Provider.GITHUB, "https://github.com", "o/r")
        val gl = RepoRef(Provider.GITLAB, "https://git.example.com", "g/p")
        val tok = { r: RepoRef -> if (r == gh) "T1" else "T2" }
        val repos = listOf(gh, gl)
        val h = AuthRouting.headersFor("https://api.github.com/repos/o/r/releases/assets/5", repos, tok)!!
        assertEquals("Bearer T1", h["Authorization"]); assertEquals("application/octet-stream", h["Accept"])
        assertNull(AuthRouting.headersFor("https://objects.githubusercontent.com/signed?x=1", repos, tok))
        assertNull(AuthRouting.headersFor("https://api.github.com/repos/other/r/releases/assets/5", repos, tok))
        assertEquals("T2", AuthRouting.headersFor("https://git.example.com/api/v4/projects/1/packages/generic/a/1/p.zip", repos, tok)!!["PRIVATE-TOKEN"])
        assertNull(AuthRouting.headersFor("http://git.example.com/x", repos, tok))
        assertNull(AuthRouting.headersFor("https://api.github.com/repos/o/r/releases/assets/5", repos) { null })
    }
}
