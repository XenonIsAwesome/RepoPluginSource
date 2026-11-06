package io.github.xenonisawesome.reposource.ide

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import io.github.xenonisawesome.reposource.core.RepoRef
import io.github.xenonisawesome.reposource.core.RepoUrlParser
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Persisted settings. Contains no secrets other than the local URL path token (which only guards 127.0.0.1). */
@Service(Service.Level.APP)
@State(name = "RepoReleaseSourceSettings", storages = [Storage("repoReleaseSource.xml")])
class RepoSourceSettings : PersistentStateComponent<RepoSourceSettings.StateData> {
    class StateData {
        var pathToken: String = ""
        var ports: MutableMap<String, Int> = HashMap()
        var repoUrls: MutableList<String> = ArrayList()
    }

    private var data = StateData()

    override fun getState(): StateData = data
    override fun loadState(state: StateData) { data = state }

    private val productKey: String get() = ApplicationInfo.getInstance().build.productCode

    @Synchronized
    fun pathToken(): String {
        if (data.pathToken.isBlank()) {
            val b = ByteArray(24)
            SecureRandom().nextBytes(b)
            data.pathToken = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }
        return data.pathToken
    }

    @Synchronized fun persistedPort(): Int = data.ports[productKey] ?: 0
    @Synchronized fun persistPort(port: Int) { data.ports[productKey] = port }

    /** Parsed repositories; entries that no longer parse are skipped. */
    @Synchronized
    fun repos(): List<RepoRef> = data.repoUrls.mapNotNull { runCatching { RepoUrlParser.parse(it) }.getOrNull() }.distinctBy { it.id }

    @Synchronized
    fun setRepoUrls(urls: List<String>) { data.repoUrls = urls.toMutableList() }

    companion object {
        fun getInstance(): RepoSourceSettings = service()
    }
}

/** Tokens live in the IDE's PasswordSafe only; they are cached in memory for the HTTP threads. */
object RepoTokens {
    private val cache = ConcurrentHashMap<String, String>()
    private val loaded = ConcurrentHashMap.newKeySet<String>()

    private fun attrs(repoId: String) = CredentialAttributes(generateServiceName("RepoReleaseSource", repoId))

    fun get(repoId: String): String? {
        if (loaded.add(repoId)) {
            val p = runCatching { PasswordSafe.instance.getPassword(attrs(repoId)) }.getOrNull()
            if (!p.isNullOrBlank()) cache[repoId] = p
        }
        return cache[repoId]
    }

    fun set(repoId: String, token: String?) {
        loaded.add(repoId)
        if (token.isNullOrBlank()) cache.remove(repoId) else cache[repoId] = token
        PasswordSafe.instance.set(attrs(repoId), if (token.isNullOrBlank()) null else Credentials("token", token))
    }
}
