package com.tgglabs.branchbadge

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Met a jour le libelle de branche sur la racine du projet quand la branche
 * Git change.
 *
 * CE QUE C'EST : un service de projet qui relit periodiquement le fichier
 * `.git/HEAD` du projet ouvert et met a jour l'affichage UNIQUEMENT quand le
 * nom de branche a change.
 *
 * POURQUOI SONNE AUTRE NE SUFFIT :
 *  - le `projectViewNodeDecorator` n'est appele qu'au (re)build de l'arbre ;
 *  - le listener `GIT_REPO_CHANGE` du plugin Git de l'IDE (voir
 *    `GitBranchChangeListener`) ne se declenche que si git4idea voit le
 *    changement, ce qui passe par le watcher VFS : c'est le maillon qui ne
 *    propage pas dans tous les environnements (conteneur, systemes de
 *    fichiers virtuels, WSL, conteneurs Docker) ;
 *  - le plugin Git de l'IDE peut etre desactive par l'utilisateur.
 *
 * Cette surveillance est donc SELF-CONTAINED : elle lit le meme fichier que
 * Git lui-meme (`.git/HEAD`), sans reseau, sans dependance externe, et elle
 * fonctionne meme si le plugin Git est desactive. Cout : la lecture d'un
 * fichier de quelques dizaines d'octets toutes les 2 secondes et par projet
 * ouvert.
 *
 * Elle ne remplace pas le listener git4idea : celui-ci donne un rafraichissement
 * instantane quand le changement est fait DANS l'IDE. Les deux cohabitent.
 *
 * COMMENT L'AFFICHAGE EST MIS A JOUR : deux leviers, dans cet ordre.
 *  1. `BranchDisplay` ecrit la nouvelle branche dans l'objet `PresentationData`
 *     du noeud racine — c'est cet objet que le panneau relit a chaque
 *     redessin, donc le libelle change meme si l'arbre n'est pas reconstruit ;
 *  2. `ProjectView.refresh()` est appele ensuite, pour redessiner le panneau
 *     (et, quand la plateforme le fait, reconstruire l'arbre — ce qui est
 *     aussi correct).
 *
 * Le service est declare dans `plugin.xml` (`<projectService>`), comme le
 * service de reglages du plugin 35416 deja publie sur la Marketplace : une
 * seule methode d'enregistrement, donc aucun risque de double declaration.
 */
class BranchRefreshService(private val project: Project) : Disposable {

    private val started = AtomicBoolean(false)
    private val state = BranchWatchState()

    @Volatile
    private var task: ScheduledFuture<*>? = null

    /**
     * Demarre la surveillance. Idempotent : n'importe quel appelant peut
     * l'appeler sans risque de lancer deux boucle de lecture.
     */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        // Etat initial : la branche affichee a cet instant. Sans cela, une
        // premiere lecture plus tard memoriserait un etat deja obsolete (un
        // `git checkout` fait dans les 3 premieres secondes passerait
        // inapercu).
        project.basePath?.let { basePath ->
            state.branchChanged(currentStatus(File(basePath)))
        }
        task = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            {
                try {
                    tick()
                } catch (t: Throwable) {
                    // Une exception ne doit jamais tuer la tache planifiee.
                    LOG.warn("Git Branch Badge : lecture de la branche impossible", t)
                }
            },
            INITIAL_DELAY_SECONDS,
            PERIOD_SECONDS,
            TimeUnit.SECONDS
        )
        LOG.info("Git Branch Badge : surveillance de la branche demarree pour ${project.name}")
    }

    /** Une lecture : branche courante -> changement ? -> mise a jour. */
    private fun tick() {
        if (project.isDisposed) return
        val basePath = project.basePath ?: return
        val current = currentStatus(File(basePath))
        if (!state.branchChanged(current)) return

        LOG.info("Git Branch Badge : branche changee -> $current")

        // L'ecriture dans l'objet de presentation et le redessin doivent se
        // faire sur le thread UI.
        ApplicationManager.getApplication().invokeLater(
            {
                if (project.isDisposed) return@invokeLater
                val changed = BranchDisplay.setStatus(basePath, current)
                // Redessin du panneau. `refresh()` reconstruit l'arbre quand la
                // plateforme le fait (et le decorateur reecrit alors le
                // libelle) ; sinon le repaint suffit, car le panneau relit
                // l'objet de presentation que l'on vient de modifier.
                ProjectView.getInstance(project)?.refresh()
                if (!changed) {
                    // Rien n'a pu etre ecrit (arbre pas encore decore) : on
                    // redessine quand meme la fenetre.
                    WindowManager.getInstance().getFrame(project)?.repaint()
                }
            },
            ModalityState.defaultModalityState()
        )
    }

    override fun dispose() {
        task?.cancel(false)
        task = null
        project.basePath?.let { BranchDisplay.forget(it) }
    }

    /**
     * L'etat git affiche pour ce projet : le nom de la branche locale, ou
     * « detached @ abc1234 » quand le HEAD est detache, ou null si le projet
     * n'est dans aucun depot.
     */
    private fun currentStatus(dir: File): String? {
        val branch = GitHead.currentBranch(dir)
        if (branch != null) return branch
        val shortSha = GitHead.detachedShortSha(dir) ?: return null
        return BranchLabel.DETACHED_PREFIX + shortSha
    }

    companion object {
        private val LOG = Logger.getInstance(BranchRefreshService::class.java)

        /** Premiere lecture : laisse le temps au premier build de l'arbre. */
        private const val INITIAL_DELAY_SECONDS = 3L

        /** Periode de lecture de `.git/HEAD`. */
        private const val PERIOD_SECONDS = 2L
    }
}
