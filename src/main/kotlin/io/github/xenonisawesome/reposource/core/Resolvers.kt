package io.github.xenonisawesome.reposource.core

import java.net.URLEncoder
import java.nio.file.Files

class AssetInfo(
    val id: String,
    val name: String,
    /** Plain download URL (works without credentials for public repos). */
    val publicUrl: String,
    /** Authenticated download URL (GitHub API asset endpoint); null if the same as [publicUrl]. */
    val authUrl: String?,
)

class ReleaseInfo(val id: String, val tag: String, val assets: List<AssetInfo>, val etag: String? = null)

/** Provider-specific part: how to find the latest release and how to talk to its API/downloads. */
interface ReleaseSource {
    /** [etag] from a previous call; returns null if the server says "not modified". */
    fun latestRelease(repo: RepoRef, token: String?, etag: String?): ReleaseInfo?

    /** URL and headers used by the resolver itself to fetch [asset]. */
    fun downloadRequest(repo: RepoRef, asset: AssetInfo, token: String?): Pair<String, Map<String, String>>

    /** URL placed in the generated XML. Must not contain credentials. */
    fun pluginUrl(repo: RepoRef, asset: AssetInfo, token: String?): String
}

class GitHubSource(private val http: HttpFetcher, private val apiBase: String = "https://api.github.com") : ReleaseSource {
    override fun latestRelease(repo: RepoRef, token: String?, etag: String?): ReleaseInfo? {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/vnd.github+json"
        headers["X-GitHub-Api-Version"] = "2022-11-28"
        if (!token.isNullOrBlank()) headers["Authorization"] = "Bearer $token"
        if (etag != null) headers["If-None-Match"] = etag
        val r = http.get("$apiBase/repos/${repo.path}/releases/latest", headers)
        when (r.status) {
            304 -> return null
            200 -> {}
            404 -> throw ResolveException(
                if (token.isNullOrBlank())
                    "No release found. If ${repo.path} is private, add an access token with read access to its contents."
                else "No release found, or the token cannot access ${repo.path}."
            )
            401 -> throw ResolveException("GitHub rejected the access token (expired or revoked?).")
            403, 429 -> throw ResolveException("GitHub refused the request (rate limit reached?). Adding a token raises the limit.")
            else -> throw ResolveException("GitHub API answered HTTP ${r.status}.")
        }
        val json = MiniJson.asMap(MiniJson.parse(r.text()))
        val assets = MiniJson.asList(json["assets"]).map { MiniJson.asMap(it) }.mapNotNull { a ->
            val name = a["name"] as? String ?: return@mapNotNull null
            val pub = a["browser_download_url"] as? String ?: return@mapNotNull null
            AssetInfo(a["id"].toString(), name, pub, a["url"] as? String)
        }
        return ReleaseInfo(json["id"].toString(), json["tag_name"]?.toString() ?: "", assets, r.headers["etag"])
    }

    override fun downloadRequest(repo: RepoRef, asset: AssetInfo, token: String?): Pair<String, Map<String, String>> =
        if (!token.isNullOrBlank() && asset.authUrl != null)
            asset.authUrl to mapOf("Authorization" to "Bearer $token", "Accept" to "application/octet-stream")
        else asset.publicUrl to emptyMap()

    /** With a token the API asset URL is used so the IDE's auth provider can authenticate the download. */
    override fun pluginUrl(repo: RepoRef, asset: AssetInfo, token: String?): String =
        if (!token.isNullOrBlank() && asset.authUrl != null) asset.authUrl else asset.publicUrl
}

class GitLabSource(private val http: HttpFetcher) : ReleaseSource {
    override fun latestRelease(repo: RepoRef, token: String?, etag: String?): ReleaseInfo? {
        val project = URLEncoder.encode(repo.path, Charsets.UTF_8)
        val base = "${repo.baseUrl}/api/v4/projects/$project"
        val headers = LinkedHashMap<String, String>()
        if (!token.isNullOrBlank()) headers["PRIVATE-TOKEN"] = token
        if (etag != null) headers["If-None-Match"] = etag
        var r = http.get("$base/releases/permalink/latest", headers)
        var release: Map<String, Any?>? = null
        if (r.status == 200) {
            release = MiniJson.asMap(MiniJson.parse(r.text()))
        } else if (r.status == 304) {
            return null
        } else if (r.status == 404) {
            // Older GitLab versions lack the permalink endpoint; the list is newest-first.
            r = http.get("$base/releases?per_page=1", headers - "If-None-Match")
            if (r.status == 200) release = MiniJson.asList(MiniJson.parse(r.text())).firstOrNull()?.let { MiniJson.asMap(it) }
        }
        if (r.status == 401) throw ResolveException("GitLab rejected the access token (expired or revoked?).")
        if (r.status == 404 || (r.status == 200 && release == null)) throw ResolveException(
            if (token.isNullOrBlank())
                "No release found. If this project is private, add an access token with the read_api scope."
            else "No release found, or the token cannot access this project."
        )
        if (r.status != 200 || release == null) throw ResolveException("GitLab API answered HTTP ${r.status}.")
        val links = MiniJson.asList(MiniJson.asMap(release["assets"])["links"]).map { MiniJson.asMap(it) }
        val assets = links.mapNotNull { l ->
            val name = l["name"] as? String ?: return@mapNotNull null
            val url = (l["direct_asset_url"] as? String) ?: (l["url"] as? String) ?: return@mapNotNull null
            AssetInfo(l["id"].toString(), name, url, null)
        }
        val tag = release["tag_name"]?.toString() ?: ""
        // GitLab releases have no numeric id; tag plus timestamp identifies a (re)published release.
        val id = tag + "@" + (release["released_at"] ?: release["created_at"] ?: "")
        return ReleaseInfo(id, tag, assets, r.headers["etag"])
    }

    override fun downloadRequest(repo: RepoRef, asset: AssetInfo, token: String?): Pair<String, Map<String, String>> {
        val sameHost = runCatching { java.net.URI(asset.publicUrl).host == repo.host }.getOrDefault(false)
        return asset.publicUrl to (if (!token.isNullOrBlank() && sameHost) mapOf("PRIVATE-TOKEN" to token) else emptyMap())
    }

    override fun pluginUrl(repo: RepoRef, asset: AssetInfo, token: String?): String = asset.publicUrl
}

/** Short-TTL release cache plus a per-asset descriptor cache so a zip is only downloaded once per release asset. */
class ResolverCache(private val ttlMillis: Long = 5 * 60_000L, private val clock: () -> Long = System::currentTimeMillis) {
    class ReleaseEntry(val fetchedAt: Long, val release: ReleaseInfo)

    private val releases = HashMap<String, ReleaseEntry>()
    private val assets = HashMap<String, PluginDescriptorInfo?>()

    @Synchronized fun release(repoId: String): ReleaseEntry? = releases[repoId]
    @Synchronized fun putRelease(repoId: String, r: ReleaseInfo) { releases[repoId] = ReleaseEntry(clock(), r) }
    @Synchronized fun touch(repoId: String) { releases[repoId]?.let { releases[repoId] = ReleaseEntry(clock(), it.release) } }
    @Synchronized fun isFresh(e: ReleaseEntry) = clock() - e.fetchedAt < ttlMillis
    @Synchronized fun hasAsset(repoId: String, assetId: String) = assets.containsKey("$repoId:$assetId")
    @Synchronized fun asset(repoId: String, assetId: String): PluginDescriptorInfo? = assets["$repoId:$assetId"]
    @Synchronized fun putAsset(repoId: String, assetId: String, d: PluginDescriptorInfo?) { assets["$repoId:$assetId"] = d }
    @Synchronized fun invalidate(repoId: String) {
        releases.remove(repoId)
        assets.keys.removeIf { it.startsWith("$repoId:") }
    }
}

class RepoCatalog(val release: ReleaseInfo, val plugins: List<CatalogPlugin>)

/** Turns a repo into catalog entries: finds the latest release, reads each zip asset's own plugin.xml, caches by asset id. */
class CatalogEngine(
    private val http: HttpFetcher,
    private val cache: ResolverCache = ResolverCache(),
    private val github: ReleaseSource = GitHubSource(http),
    private val gitlab: ReleaseSource = GitLabSource(http),
    private val requireHttpsPluginUrl: Boolean = true,
) {
    fun invalidate(repoId: String) = cache.invalidate(repoId)

    fun resolve(repo: RepoRef, token: String?, forceRefresh: Boolean = false): RepoCatalog {
        val source = if (repo.provider == Provider.GITHUB) github else gitlab
        val cached = cache.release(repo.id)
        var release: ReleaseInfo? = cached?.release
        if (forceRefresh || cached == null || !cache.isFresh(cached)) {
            val fresh = source.latestRelease(repo, token, if (forceRefresh) null else cached?.release?.etag)
            if (fresh != null) {
                cache.putRelease(repo.id, fresh)
                release = fresh
            } else {
                cache.touch(repo.id)
            }
        }
        release ?: throw ResolveException("No release found.")
        val zips = release.assets.filter { it.name.endsWith(".zip", ignoreCase = true) }
        if (zips.isEmpty()) throw ResolveException("Release ${release.tag} has no .zip asset. Attach the plugin distribution zip to the release.")
        val plugins = ArrayList<CatalogPlugin>()
        for (asset in zips) {
            val d = descriptorFor(repo, source, release, asset, token) ?: continue
            val url = source.pluginUrl(repo, asset, token)
            if (requireHttpsPluginUrl && !url.startsWith("https://")) throw ResolveException("The download URL is not HTTPS.")
            plugins.add(CatalogPlugin(d.id, d.name, d.version, d.sinceBuild, d.untilBuild, d.description, d.vendor, url))
        }
        if (plugins.isEmpty()) throw ResolveException(
            "None of the .zip assets of release ${release.tag} contains a plugin (META-INF/plugin.xml with an id and version)."
        )
        return RepoCatalog(release, plugins)
    }

    private fun descriptorFor(repo: RepoRef, source: ReleaseSource, release: ReleaseInfo, asset: AssetInfo, token: String?): PluginDescriptorInfo? {
        if (cache.hasAsset(repo.id, asset.id)) return cache.asset(repo.id, asset.id)
        val (url, headers) = source.downloadRequest(repo, asset, token)
        val tmp = Files.createTempFile("repo-release-source", ".zip")
        try {
            http.download(url, headers, tmp)
            val d = try { PluginZipReader.read(tmp) } catch (e: java.util.zip.ZipException) { null }
            cache.putAsset(repo.id, asset.id, d)
            return d
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}

/** Decides which requests of the IDE's own plugin downloader get credentials. Never matches a redirect target. */
object AuthRouting {
    fun headersFor(url: String, repos: List<RepoRef>, tokenOf: (RepoRef) -> String?): Map<String, String>? {
        for (repo in repos) {
            val token = tokenOf(repo)?.takeIf { it.isNotBlank() } ?: continue
            when (repo.provider) {
                Provider.GITHUB -> if (url.startsWith("https://api.github.com/repos/${repo.path}/releases/assets/", ignoreCase = true))
                    return mapOf("Authorization" to "Bearer $token", "Accept" to "application/octet-stream")
                Provider.GITLAB -> {
                    val host = runCatching { java.net.URI(url) }.getOrNull()
                    if (host != null && host.scheme == "https" && host.host == repo.host) return mapOf("PRIVATE-TOKEN" to token)
                }
            }
        }
        return null
    }
}
