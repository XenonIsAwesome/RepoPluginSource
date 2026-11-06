package io.github.xenonisawesome.reposource.adapter

import com.intellij.ide.plugins.auth.PluginRepositoryAuthProvider
import com.intellij.openapi.updateSettings.impl.UpdateSettingsProvider
import io.github.xenonisawesome.reposource.core.AuthRouting
import io.github.xenonisawesome.reposource.ide.CatalogService
import io.github.xenonisawesome.reposource.ide.RepoSourceSettings
import io.github.xenonisawesome.reposource.ide.RepoTokens

/**
 * The ONLY place that touches @ApiStatus.Internal platform API (see SPIKE.md):
 *  - UpdateSettingsProvider (EP com.intellij.updateSettingsProvider)
 *  - PluginRepositoryAuthProvider (EP com.intellij.pluginRepositoryAuthProvider)
 * Everything else in the plugin uses open API. Both classes swallow every Throwable and degrade to "no effect";
 * [missingApi] lets the startup activity explain once what is unavailable if a future IDE removes either interface.
 */
object InternalApiAdapter {
    private const val UPDATE_PROVIDER = "com.intellij.openapi.updateSettings.impl.UpdateSettingsProvider"
    private const val AUTH_PROVIDER = "com.intellij.ide.plugins.auth.PluginRepositoryAuthProvider"

    /** Human-readable names of the features that cannot work in this IDE build. */
    fun missingApi(): List<String> {
        val out = ArrayList<String>()
        if (!exists(UPDATE_PROVIDER)) out += "automatic registration of repositories in Settings | Plugins"
        if (!exists(AUTH_PROVIDER)) out += "downloads from private repositories"
        return out
    }

    private fun exists(name: String): Boolean = try {
        Class.forName(name, false, InternalApiAdapter::class.java.classLoader)
        true
    } catch (t: Throwable) {
        false
    }
}

class RepoUpdateSettingsProvider : UpdateSettingsProvider {
    override fun getPluginRepositories(): List<String> = try {
        CatalogService.getInstance().catalogUrls()
    } catch (t: Throwable) {
        emptyList()
    }
}

class RepoAuthProvider : PluginRepositoryAuthProvider {
    override fun getAuthHeaders(url: String): Map<String, String> = headers(url) ?: emptyMap()
    override fun canHandle(url: String): Boolean = headers(url) != null

    private fun headers(url: String): Map<String, String>? = try {
        AuthRouting.headersFor(url, RepoSourceSettings.getInstance().repos()) { RepoTokens.get(it.id) }
    } catch (t: Throwable) {
        null
    }
}
