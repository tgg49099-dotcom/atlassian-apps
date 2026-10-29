package com.tgglabs.xlsxdiff.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * The short messages the plugin shows in the IDE.
 *
 * The notification group is declared in `META-INF/plugin.xml`, inside
 * `<extensions>`: at the root of the descriptor the IDE 2024.3 refuses it
 * ("Unknown element: notificationGroup") and refuses the whole plugin.
 */
object Notifications {

    /** The identifier of the group, as declared in `plugin.xml`. */
    const val GROUP = "XLSX Diff"

    /**
     * Shows a balloon in the IDE.
     *
     * Nothing happens when the group is not registered: the caller always
     * shows the same information somewhere else (in the panel).
     */
    fun show(project: Project, title: String, content: String) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP) ?: return
        group.createNotification(title, content, NotificationType.INFORMATION).notify(project)
    }
}
