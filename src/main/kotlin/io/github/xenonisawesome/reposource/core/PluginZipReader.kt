package io.github.xenonisawesome.reposource.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Values taken verbatim from the plugin's own META-INF/plugin.xml. */
data class PluginDescriptorInfo(
    val id: String,
    val name: String?,
    val version: String,
    val sinceBuild: String?,
    val untilBuild: String?,
    val description: String?,
    val vendor: String?,
)

object PluginZipReader {
    /** Returns the descriptor of the plugin jar inside a distribution zip, or null if the zip is not a plugin. */
    fun read(zip: Path): PluginDescriptorInfo? {
        ZipFile(zip.toFile()).use { zf ->
            val entries = zf.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".jar") }
                .sortedBy { it.name.count { c -> c == '/' } }
            for (e in entries) {
                zf.getInputStream(e).use { ins -> readFromJar(ins)?.let { return it } }
            }
        }
        return null
    }

    fun readFromJar(jar: InputStream): PluginDescriptorInfo? {
        ZipInputStream(jar).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: return null
                if (e.name == "META-INF/plugin.xml") return parse(zis.readAllBytes())
            }
        }
    }

    fun parse(xml: ByteArray): PluginDescriptorInfo? {
        val f = DocumentBuilderFactory.newInstance()
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        val root = try {
            f.newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement
        } catch (e: Exception) {
            return null
        }
        if (root.tagName != "idea-plugin") return null
        fun text(tag: String): String? = children(root, tag).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        val name = text("name")
        val id = text("id") ?: name ?: return null
        val version = text("version") ?: return null
        val iv = children(root, "idea-version").firstOrNull()
        return PluginDescriptorInfo(
            id = id,
            name = name,
            version = version,
            sinceBuild = iv?.getAttribute("since-build")?.takeIf { it.isNotEmpty() },
            untilBuild = iv?.getAttribute("until-build")?.takeIf { it.isNotEmpty() },
            description = text("description"),
            vendor = text("vendor"),
        )
    }

    private fun children(e: Element, tag: String): List<Element> {
        val out = ArrayList<Element>()
        val nl = e.childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element && n.tagName == tag) out.add(n)
        }
        return out
    }
}
