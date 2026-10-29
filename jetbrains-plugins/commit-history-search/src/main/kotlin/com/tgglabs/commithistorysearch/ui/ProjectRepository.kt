package com.tgglabs.commithistorysearch.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.tgglabs.commithistorysearch.core.GitLocator
import java.io.File

/**
 * Finds the Git repository that the project belongs to.
 *
 * The IDE can be opened on a sub-directory of a repository, or on a project with several
 * content roots: the search has to run at the root of the repository to see the whole
 * history. Only the file system is used here (no dependency on the Git plugin), so the
 * plugin also works when the Git integration of the IDE is disabled.
 */
object ProjectRepository {

    fun root(project: Project): File? {
        val candidates = LinkedHashSet<File>()
        ProjectRootManager.getInstance(project).contentRoots.forEach { candidates.add(File(it.path)) }
        project.basePath?.let { candidates.add(File(it)) }
        for (candidate in candidates) {
            val root = GitLocator.findRepositoryRoot(candidate)
            if (root != null) return root
        }
        return null
    }
}
