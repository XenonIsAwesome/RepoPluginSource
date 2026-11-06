package io.github.xenonisawesome.reposource.ide

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

object Notifier {
    const val GROUP = "Repo Release Source"
    const val SETTINGS_ID = "io.github.xenonisawesome.reposource.settings"

    fun info(project: Project?, title: String, content: String, withSettingsAction: Boolean = false) =
        show(project, title, content, NotificationType.INFORMATION, withSettingsAction)

    fun warn(project: Project?, title: String, content: String, withSettingsAction: Boolean = false) =
        show(project, title, content, NotificationType.WARNING, withSettingsAction)

    private fun show(project: Project?, title: String, content: String, type: NotificationType, withSettingsAction: Boolean) {
        val n = NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(title, content, type)
        if (withSettingsAction) {
            n.addAction(NotificationAction.createSimple("Open settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, SETTINGS_ID)
            })
        }
        n.notify(project)
    }
}
