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
    private val settings get() = RepoSourceSettings.getInstance()
    private val lastErrors = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val server = CatalogHttpServer(
        secret = settings.pathToken(),
        catalogFor = { id -> catalogXml(id) },
        onError = { id, e -> lastErrors[id] = message(e); log.info("Catalog for $id failed: ${message(e)}") },
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

    fun catalogUrl(repo: RepoRef): String = "http://127.0.0.1:$port/${settings.pathToken()}/${repo.id}/updatePlugins.xml"

    fun catalogUrls(): List<String> {
        ensureStarted()
        return settings.repos().map { catalogUrl(it) }
    }

    fun check(repo: RepoRef, tokenOverride: String? = null): RepoCatalog =
        engine.resolve(repo, tokenOverride ?: RepoTokens.get(repo.id), forceRefresh = true)

    fun invalidate(repoId: String) = engine.invalidate(repoId)

    private fun catalogXml(repoId: String): String? {
        val repo = settings.repos().firstOrNull { it.id == repoId } ?: return null
        val catalog = engine.resolve(repo, RepoTokens.get(repo.id))
        lastErrors.remove(repoId)
        return CatalogXml.render(catalog.plugins)
    }

    override fun dispose() = server.stop()

    companion object {
        fun getInstance(): CatalogService = service()
        fun message(e: Throwable): String = if (e is ResolveException) e.message ?: "Failed" else "Unexpected error: ${e.javaClass.simpleName}"
    }
}
