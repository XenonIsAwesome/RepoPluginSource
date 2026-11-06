package io.github.xenonisawesome.reposource.core

/** One entry of the generated updatePlugins.xml. [version] is exactly the version declared inside the plugin. */
data class CatalogPlugin(
    val id: String,
    val name: String?,
    val version: String,
    val sinceBuild: String?,
    val untilBuild: String?,
    val description: String?,
    val vendor: String?,
    val downloadUrl: String,
)

object CatalogXml {
    fun render(plugins: List<CatalogPlugin>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plugins>\n")
        for (p in plugins) {
            sb.append("  <plugin id=\"${esc(p.id)}\" url=\"${esc(p.downloadUrl)}\" version=\"${esc(p.version)}\">\n")
            if (p.sinceBuild != null || p.untilBuild != null) {
                sb.append("    <idea-version")
                p.sinceBuild?.let { sb.append(" since-build=\"${esc(it)}\"") }
                p.untilBuild?.let { sb.append(" until-build=\"${esc(it)}\"") }
                sb.append("/>\n")
            }
            p.name?.let { sb.append("    <name>${esc(it)}</name>\n") }
            p.vendor?.let { sb.append("    <vendor>${esc(it)}</vendor>\n") }
            p.description?.let { sb.append("    <description>${esc(it)}</description>\n") }
            sb.append("  </plugin>\n")
        }
        return sb.append("</plugins>\n").toString()
    }

    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;")
            else -> if (c.code >= 0x20 || c == '\n' || c == '\t') append(c)
        }
    }
}
