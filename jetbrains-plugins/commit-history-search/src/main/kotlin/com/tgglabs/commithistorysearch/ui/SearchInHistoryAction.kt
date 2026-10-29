package com.tgglabs.commithistorysearch.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Entry point of the plugin: opens the "Search in Commit History" window.
 *
 * The action is declared once, in plugin.xml, in the "Git" menu of the IDE
 * (group "Git.MainMenu"). It stays reachable with "Find Action" (Ctrl+Shift+A) even when
 * the Git plugin of the IDE is disabled.
 */
class SearchInHistoryAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && ProjectRepository.root(project) != null
    }

    /**
     * `update()` touche le disque (il cherche la racine du depot) : il doit donc tourner
     * sur un thread de fond, sinon l'IDE geles pendant l'appel. Regle IntelliJ :
     * des que l'action fait un acces disque ou lent, on renvoie BGT.
     */
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        HistorySearchDialog(project).show()
    }
}
