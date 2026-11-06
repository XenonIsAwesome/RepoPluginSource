package io.github.xenonisawesome.reposource.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import io.github.xenonisawesome.reposource.core.CatalogEngine
import io.github.xenonisawesome.reposource.core.CatalogHttpServer
import io.github.xenonisawesome.reposource.core.CatalogXml
import io.github.xenonisawesome.reposource.core.JdkHttpFetcher
import io.github.xenonisawesome.reposource.core.RepoCatalog
import io.github.xenonisawesome.reposource.core.RepoRef
import io.github.xenonisawesome.reposource.core.ResolveException

/** Owns the loopback catalog server and the resolver engine. */
@Service(Service.Level.APP)
class CatalogService : Disposable {
    private val log = logger<CatalogService>()
    private val engine = CatalogEngine(JdkHttpFetcher())
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(4) { r -> Thread(r, "RepoReleaseSource-resolve").apply { isDaemon = true } }
    private val settings get() = RepoSourceSettings.getInstance()
    private val server = CatalogHttpServer(
        secret = settings.pathToken(),
        catalog = { combinedXml() },
        onError = { e -> log.info("Combined catalog failed: ${message(e)}") },
    )

    val port: Int get() = server.port
    val running: Boolean get() = server.running

    /** Idempotent. Port: persisted one first, fall back to a free one and tell the user. */
    @Synchronized
    fun ensureStarted() {
        if (server.running) return
        val preferred = settings.persistedPort()
        val bound = server.start(preferred)
        if (bound != preferred) {
            settings.persistPort(bound)
            if (preferred != 0) {
                Notifier.warn(
                    null, "Repo Release Source: local port changed",
                    "Port $preferred was busy, so the local catalog now listens on $bound. " +
                        "Plugins installed from your repositories keep working; if one stops offering updates, reinstall it once.",
                    withSettingsAction = true,
                )
            }
        }
    }

    /** The one combined catalog URL; independent of which repositories are configured, so it stays stable. */
    fun catalogUrl(): String = "http://127.0.0.1:$port/${settings.pathToken()}/updatePlugins.xml"

    fun catalogUrls(): List<String> {
        ensureStarted()
        return listOf(catalogUrl())
    }

    fun check(repo: RepoRef, tokenOverride: String? = null): RepoCatalog =
        engine.resolve(repo, tokenOverride ?: RepoTokens.get(repo.id), forceRefresh = true)

    fun invalidate(repoId: String) = engine.invalidate(repoId)

    /**
     * All repositories in one catalog. A failing repository is skipped (its problem shows in the settings page)
     * so one broken or private-without-token repo never hides the others. If every repository fails, the request fails.
     */
    private fun combinedXml(): String {
        val repos = settings.repos()
        if (repos.isEmpty()) return CatalogXml.render(emptyList())
        val results = repos.map { repo ->
            java.util.concurrent.CompletableFuture.supplyAsync({
                runCatching { engine.resolve(repo, RepoTokens.get(repo.id)) }
            }, pool)
        }.map { it.join() }
        val ok = results.mapNotNull { it.getOrNull() }
        if (ok.isEmpty()) throw results.first().exceptionOrNull() ?: ResolveException("No plugins found")
        // The same plugin id in two repositories: the first repository in the list wins.
        val plugins = ok.flatMap { it.plugins }.distinctBy { it.id }
        return CatalogXml.render(plugins)
    }

    override fun dispose() { server.stop(); pool.shutdownNow() }

    companion object {
        fun getInstance(): CatalogService = service()
        fun message(e: Throwable): String = if (e is ResolveException) e.message ?: "Failed" else "Unexpected error: ${e.javaClass.simpleName}"
    }
}
