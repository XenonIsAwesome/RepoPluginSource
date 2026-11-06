package io.github.xenonisawesome.reposource.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.xenonisawesome.reposource.adapter.InternalApiAdapter
import java.util.concurrent.atomic.AtomicBoolean

class Startup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!done.compareAndSet(false, true)) return
        val missing = InternalApiAdapter.missingApi()
        if (missing.isNotEmpty()) {
            Notifier.warn(
                project, "Repo Release Source is partly unavailable",
                "This IDE version no longer provides the API needed for: ${missing.joinToString("; ")}. " +
                    "The rest of the plugin keeps working. An updated plugin version may be needed.",
            )
            return
        }
        runCatching { CatalogService.getInstance().ensureStarted() }
    }

    private companion object { val done = AtomicBoolean(false) }
}
