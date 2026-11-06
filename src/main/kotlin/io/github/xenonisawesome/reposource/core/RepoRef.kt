package io.github.xenonisawesome.reposource.core

import java.net.URI

enum class Provider(val label: String) { GITHUB("GitHub"), GITLAB("GitLab") }

/** A repository on GitHub or GitLab (gitlab.com or self-hosted). [baseUrl] has no trailing slash. */
data class RepoRef(val provider: Provider, val baseUrl: String, val path: String) {
    val host: String get() = URI(baseUrl).host

    /** Stable id used in the catalog URL path. Only [a-z0-9._-]. */
    val id: String
        get() = "${provider.name.lowercase()}-$host-$path".lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')

    val displayUrl: String get() = "$baseUrl/$path"
}

class RepoUrlException(message: String) : IllegalArgumentException(message)

object RepoUrlParser {
    /**
     * Accepts `github.com/o/r`, `https://github.com/o/r.git`, `https://gitlab.com/g/sub/p/-/releases`,
     * `https://git.example.com/g/p`. Anything that is not github.com is treated as GitLab.
     */
    fun parse(input: String): RepoRef {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw RepoUrlException("Enter a repository URL, for example https://github.com/owner/repo")
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            throw RepoUrlException("This does not look like a valid URL")
        }
        if (uri.scheme != "https") throw RepoUrlException("Only https:// repository URLs are supported")
        val host = uri.host?.lowercase() ?: throw RepoUrlException("The URL has no host name")
        val port = if (uri.port in 1..65535) ":${uri.port}" else ""
        val base = "https://$host$port"
        var segments = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
        val dash = segments.indexOf("-")
        if (dash >= 0) segments = segments.subList(0, dash)
        if (segments.isNotEmpty()) {
            segments = segments.dropLast(1) + segments.last().removeSuffix(".git")
        }
        return if (host == "github.com" || host == "www.github.com") {
            if (segments.size < 2) throw RepoUrlException("A GitHub URL needs an owner and a repository: https://github.com/owner/repo")
            RepoRef(Provider.GITHUB, "https://github.com", "${segments[0]}/${segments[1]}")
        } else {
            if (segments.size < 2) throw RepoUrlException("A GitLab URL needs a group and a project: https://gitlab.example.com/group/project")
            RepoRef(Provider.GITLAB, base, segments.joinToString("/"))
        }
    }
}
