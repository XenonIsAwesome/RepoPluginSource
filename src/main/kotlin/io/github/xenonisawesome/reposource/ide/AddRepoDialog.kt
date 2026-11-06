package io.github.xenonisawesome.reposource.ide

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.xenonisawesome.reposource.core.RepoRef
import io.github.xenonisawesome.reposource.core.RepoUrlException
import io.github.xenonisawesome.reposource.core.RepoUrlParser
import javax.swing.JComponent

/** Asks for a repository URL (validated while typing) and an optional access token. [existing] blocks duplicates. */
class AddRepoDialog(private val existing: Set<String>, private val urlFixed: RepoRef? = null) :
    DialogWrapper(true) {

    private val urlField = JBTextField(urlFixed?.displayUrl ?: "", 40).apply { isEnabled = urlFixed == null }
    private val tokenField = JBPasswordField()
    private val detected = com.intellij.ui.components.JBLabel(" ")

    init {
        title = if (urlFixed == null) "Add Repository" else "Set Access Token"
        init()
        urlField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refresh()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refresh()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refresh()
        })
        refresh()
    }

    val repo: RepoRef get() = RepoUrlParser.parse(urlField.text)

    /** Empty string means "no token". */
    val token: String get() = String(tokenField.password).trim()

    private fun refresh() {
        detected.text = try {
            val r = RepoUrlParser.parse(urlField.text)
            "Detected: ${r.provider.label} project ${r.path}" + if (r.provider.name == "GITLAB") " on ${r.host}" else ""
        } catch (e: RepoUrlException) {
            if (urlField.text.isBlank()) " " else e.message ?: " "
        }
    }

    override fun createCenterPanel(): JComponent = panel {
        if (urlFixed == null) {
            row("Repository URL:") { cell(urlField).align(AlignX.FILL).focused() }
            row("") { cell(detected) }
            row { comment("GitHub (github.com/owner/repo) or GitLab, including self-hosted (https://git.example.com/group/project).") }
        } else {
            row { label(urlFixed.displayUrl) }
        }
        row("Access token:") { cell(tokenField).align(AlignX.FILL).comment("Optional. Only needed for private repositories. Stored in the IDE's secure password store.") }
        row {
            comment(
                "GitHub: a token with read access to the repository's Contents. " +
                    "GitLab: a token with the read_api scope. " +
                    if (urlFixed != null) "Leave empty to remove the stored token." else ""
            )
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = if (urlFixed == null) urlField else tokenField

    override fun doValidate(): ValidationInfo? {
        if (urlFixed != null) return null
        return try {
            val r = RepoUrlParser.parse(urlField.text)
            if (r.id in existing) ValidationInfo("This repository is already in the list.", urlField) else null
        } catch (e: RepoUrlException) {
            ValidationInfo(e.message ?: "Invalid URL", urlField)
        }
    }
}
