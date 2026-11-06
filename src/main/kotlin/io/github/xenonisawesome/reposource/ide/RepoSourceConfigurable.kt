package io.github.xenonisawesome.reposource.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.xenonisawesome.reposource.core.RepoRef
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

class RepoSourceConfigurable : Configurable {
    private class Row(val ref: RepoRef, var hasToken: Boolean, var status: String = "Not checked yet") {
        /** null = unchanged, "" = remove stored token, otherwise new token. */
        var pendingToken: String? = null
        var checked = false
    }

    private val rows = ArrayList<Row>()
    private val model = object : AbstractTableModel() {
        private val cols = arrayOf("Repository", "Provider", "Token", "Status")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = cols.size
        override fun getColumnName(c: Int) = cols[c]
        override fun getValueAt(r: Int, c: Int): Any = rows[r].let {
            when (c) {
                0 -> it.ref.displayUrl
                1 -> it.ref.provider.label
                2 -> if (it.hasToken) "set" else "—"
                else -> it.status
            }
        }
    }
    private val table = JBTable(model)
    private val serverLabel = JBLabel()
    private var root: JPanel? = null

    override fun getDisplayName() = "Repo Release Sources"

    override fun createComponent(): JComponent {
        reset()
        table.emptyText.text = "No repositories yet"
        table.emptyText.appendLine("Add a GitHub or GitLab repository to install its plugin releases", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        table.columnModel.getColumn(0).preferredWidth = 320
        table.columnModel.getColumn(3).preferredWidth = 380

        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { addRepo() }
            .setRemoveAction { removeSelected() }
            .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction("Set Access Token…", "Set or remove the access token of the selected repository", com.intellij.icons.AllIcons.Actions.Edit) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = setToken()
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) { e.presentation.isEnabled = table.selectedRow >= 0 }
            })
            .createPanel()

        val check = JButton("Check now").apply { addActionListener { checkAll() } }
        val hint = JBLabel(
            "<html>After adding a repository, open <b>Settings | Plugins | Marketplace</b> and search for its plugin. " +
                "Installed plugins then show the usual update badge when a newer release is published.</html>"
        ).apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }
        val bottom = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(4))).apply {
            add(serverLabel, BorderLayout.CENTER)
            add(check, BorderLayout.EAST)
            add(hint, BorderLayout.SOUTH)
            border = JBUI.Borders.emptyTop(8)
        }
        updateServerLabel()
        return JPanel(BorderLayout()).apply { add(decorated, BorderLayout.CENTER); add(bottom, BorderLayout.SOUTH) }.also { root = it }
    }

    private fun updateServerLabel() {
        val svc = CatalogService.getInstance()
        serverLabel.text = if (svc.running) "Local catalog server: running on 127.0.0.1:${svc.port}"
        else "Local catalog server: not running (it starts when the IDE first needs it)"
    }

    private fun addRepo() {
        val dlg = AddRepoDialog(rows.map { it.ref.id }.toSet())
        if (!dlg.showAndGet()) return
        val row = Row(dlg.repo, dlg.token.isNotEmpty())
        if (dlg.token.isNotEmpty()) row.pendingToken = dlg.token
        rows.add(row)
        model.fireTableDataChanged()
        table.setRowSelectionInterval(rows.size - 1, rows.size - 1)
        check(row) // immediate feedback: does it work?
    }

    private fun removeSelected() {
        val i = table.selectedRow
        if (i < 0) return
        rows.removeAt(i)
        model.fireTableDataChanged()
    }

    private fun setToken() {
        val row = rows.getOrNull(table.selectedRow) ?: return
        val dlg = AddRepoDialog(emptySet(), row.ref)
        if (!dlg.showAndGet()) return
        row.pendingToken = dlg.token
        row.hasToken = dlg.token.isNotEmpty()
        row.status = "Not checked yet"
        model.fireTableDataChanged()
        check(row)
    }

    private fun checkAll() { rows.forEach { check(it) } }

    private fun check(row: Row) {
        row.status = "Checking…"
        model.fireTableDataChanged()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                val tokenToUse = when (val p = row.pendingToken) { null -> null; "" -> ""; else -> p }
                val cat = CatalogService.getInstance().let { svc ->
                    svc.ensureStarted()
                    if (tokenToUse != null) svc.invalidate(row.ref.id)
                    svc.check(row.ref, tokenToUse)
                }
                "OK — " + cat.plugins.joinToString(", ") { "${it.name ?: it.id} ${it.version}" } + " (release ${cat.release.tag})"
            } catch (t: Throwable) {
                "Problem: " + CatalogService.message(t)
            }
            ApplicationManager.getApplication().invokeLater({
                row.status = result
                row.checked = true
                model.fireTableDataChanged()
                updateServerLabel()
            }, ModalityState.any())
        }
    }

    override fun isModified(): Boolean {
        val s = RepoSourceSettings.getInstance().repos()
        return s.map { it.id } != rows.map { it.ref.id } || rows.any { it.pendingToken != null }
    }

    override fun apply() {
        val settings = RepoSourceSettings.getInstance()
        val old = settings.repos().map { it.id }.toSet()
        val keep = rows.map { it.ref.id }.toSet()
        (old - keep).forEach { RepoTokens.set(it, null); CatalogService.getInstance().invalidate(it) }
        rows.forEach { r ->
            r.pendingToken?.let { RepoTokens.set(r.ref.id, it); CatalogService.getInstance().invalidate(r.ref.id); r.pendingToken = null }
        }
        settings.setRepoUrls(rows.map { it.ref.displayUrl })
        CatalogService.getInstance().ensureStarted()
        updateServerLabel()
        // Give feedback for rows the user never checked.
        rows.filter { !it.checked }.forEach { check(it) }
    }

    override fun reset() {
        rows.clear()
        RepoSourceSettings.getInstance().repos().forEach { rows.add(Row(it, RepoTokens.get(it.id) != null)) }
        model.fireTableDataChanged()
    }

    override fun disposeUIResources() { root = null }
}
