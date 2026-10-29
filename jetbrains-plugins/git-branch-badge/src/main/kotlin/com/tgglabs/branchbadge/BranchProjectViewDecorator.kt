package com.tgglabs.branchbadge

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import java.io.File

/**
 * Affiche la branche Git courante sur la RACINE DU PROJET du panneau
 * « Project ».
 *
 * Une seule fonction, une seule fois : le noeud racine du projet montre
 * « chemin [branche] » — le chemin ET la branche (le libelle ne remplace
 * plus le chemin, il s'y ajoute ; revue du 27/10).
 *
 * Point d'extension utilise : `com.intellij.projectViewNodeDecorator`, declare
 * dans l'IDE avec la signature
 * `decorate(ProjectViewNode<?>, PresentationData)` — identique en 2024.3 et
 * en 2026.2 (verifie par javap sur les deux IDE).
 */
class BranchProjectViewDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, presentation: PresentationData) {
        val file = node.virtualFile ?: return
        if (!file.isDirectory) return
        val path = file.path

        // Uniquement la racine du projet : le noeud dont le chemin est la base
        // du projet ouvert. Aucun autre noeud de l'arbre n'est touche.
        val project = ProjectManager.getInstance().openProjects.firstOrNull { it.basePath == path }
            ?: return

        // Demarre (une seule fois) la surveillance de la branche : c'est elle
        // qui met a jour le libelle quand la branche change. Le panneau «
        // Project » etant construit, le service est utile des la premiere
        // decoration.
        project.getService(BranchRefreshService::class.java)?.ensureStarted()

        // Le libelle affiche par l'IDE avant decoration (le chemin du projet) :
        // on le garde pour pouvoir le restaurer si la branche disparait.
        val originalLocation = presentation.locationString

        // Le chemin affiche devant la branche : celui que l'IDE affichait
        // s'il y en a un, sinon le chemin du projet. « chemin [branche] ».
        val displayPath = originalLocation?.takeIf { it.isNotBlank() } ?: path

        // Etat affiche : la branche locale, ou « detached @ abc1234 » quand le
        // HEAD est detache, ou null si le dossier n'est dans aucun depot.
        val branch = GitHead.currentBranch(File(path))
        val shortSha = if (branch == null) GitHead.detachedShortSha(File(path)) else null
        val status = BranchLabel.status(branch, shortSha)

        if (status == null) {
            // Aucune branche a afficher (le dossier n'est pas encore un depot
            // git) : on ne touche PAS au libelle, mais on ENREGISTRE quand meme
            // le noeud.
            //
            // Sans cet enregistrement, une branche qui apparaitra plus tard
            // (`git init` + `git checkout` depuis un terminal) ne pourra jamais
            // s'ecrire dans ce noeud : `BranchDisplay.setStatus` ne trouve
            // aucune entree et renvoie false — et comme l'etat a deja ete
            // confirme, plus aucune tentative n'est faite. Le badge restait
            // invisible jusqu'a la reconstruction complete de l'arbre.
            BranchDisplay.onDecorated(path, presentation, originalLocation, displayPath, null)
            return
        }

        // Le chemin et la branche sont affiches ensemble.
        presentation.locationString = BranchLabel.compose(displayPath, status)

        val lines = ArrayList<String>(3)
        presentation.tooltip?.takeIf { it.isNotBlank() }?.let { lines.add(it) }
        lines.add("Git branch: $status")
        originalLocation?.takeIf { it.isNotBlank() }?.let { lines.add(it) }
        presentation.tooltip = lines.joinToString("\n")

        // Etat affiche precedemment sur ce noeud (null = premiere fois).
        // On le lit AVANT `onDecorated`, qui met le registre a jour.
        val previousStatus = BranchDisplay.displayedStatus(path)

        // On retient l'objet de presentation : c'est lui que le panneau relit a
        // chaque redessin, donc c'est lui qu'il faut modifier pour mettre a
        // jour le libelle sans reconstruire l'arbre (cf. BranchDisplay).
        BranchDisplay.onDecorated(path, presentation, originalLocation, displayPath, status)

        // Trace : le decorateur n'est appele qu'a la construction de l'arbre.
        // S'il est rappele avec un etat different, c'est que la construction a
        // eu lieu — on le note pour pouvoir le diagnostiquer.
        if (previousStatus != null && previousStatus != status) {
            LOG.info("Git Branch Badge : le panneau « Project » a ete reconstruit ($previousStatus -> $status)")
        }
    }

    companion object {
        private val LOG = Logger.getInstance(BranchProjectViewDecorator::class.java)
    }
}
