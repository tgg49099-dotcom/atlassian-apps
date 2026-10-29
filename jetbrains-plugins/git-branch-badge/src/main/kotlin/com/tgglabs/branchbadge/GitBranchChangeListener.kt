package com.tgglabs.branchbadge

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/**
 * Rafraichit le panneau « Project » quand la branche Git change.
 *
 * Ce listener n'est charge QUE si le plugin Git de l'IDE est present
 * (voir `META-INF/git4idea.xml`, charge par la dependance optionnelle
 * `Git4Idea`). Sans lui, la fonction principale fonctionne toujours : seule
 * la mise a jour instantanee disparait.
 *
 * LE `topic` DU DESCRIPTEUR DOIT ETRE LE NOM DE L'INTERFACE
 * (`git4idea.repo.GitRepositoryChangeListener`), pas le nom d'un champ. La
 * revue du 27/10 a corrige ce defaut : avec `topic="...GIT_REPO_CHANGE"`, la
 * plateforme ne pouvait pas resoudre la classe du topic, le listener n'etait
 * donc JAMAIS enregistre, et le rafraichissement ne provenait que du sondage
 * de 2 secondes. Le test `Git4IdeaDescriptorTest` verifie ce point a chaque
 * build.
 *
 * La ligne de log du constructeur est la PREUVE que le listener a bien ete
 * enregistre : la plateforme ne l'instancie que si elle a resolu le topic et
 * souscrit l'ecouteur.
 */
class GitBranchChangeListener : GitRepositoryChangeListener {

    init {
        LOG.info("Git Branch Badge : listener Git enregistre (topic=$TOPIC)")
    }

    override fun repositoryChanged(repository: GitRepository) {
        val project = repository.project ?: return
        LOG.info("Git Branch Badge : le plugin Git de l'IDE signale un changement de depot")
        ApplicationManager.getApplication().invokeLater {
            ProjectView.getInstance(project)?.refresh()
        }
    }

    companion object {
        /** Le topic declare dans `META-INF/git4idea.xml` (cf. le test). */
        const val TOPIC = "git4idea.repo.GitRepositoryChangeListener"

        private val LOG = Logger.getInstance(GitBranchChangeListener::class.java)
    }
}
