package com.tgglabs.commithistorysearch.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project

/**
 * Shows the content of a file before / after a commit in the IDE diff viewer
 * (API of the 2024.3 platform: com.intellij.diff.*).
 */
object DiffOpener {

    fun show(project: Project, title: String, oldText: String, newText: String) {
        val factory = DiffContentFactory.getInstance()
        val oldContent = factory.create(oldText)
        val newContent = factory.create(newText)
        val request = SimpleDiffRequest(title, oldContent, newContent, "before", "after")
        DiffManager.getInstance().showDiff(project, request)
    }
}
