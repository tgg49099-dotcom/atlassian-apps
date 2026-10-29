package com.tgglabs.branchbadge

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests de la lecture de la branche Git courante (`GitHead`).
 *
 * La classe testee n'a AUCUNE dependance IntelliJ : elle se teste hors IDE,
 * en Java/Kotlin pur. C'est ce qui permet de jouer la preuve ROUGE -> VERT
 * (le test echoue sur l'implementation naive, puis passe sur l'implementation
 * complete) sans dependre du framework de test de la plateforme.
 *
 * Chaque test correspond a un cas REEL :
 *  - `ref: refs/heads/main`  : le cas normal d'un depot git ;
 *  - un SHA a la place de `ref:` : HEAD detache (git checkout d'un commit) ;
 *  - `.git` absent : le dossier n'est pas un depot ;
 *  - un sous-dossier du depot : le projet est ouvert plus bas que la racine ;
 *  - `.git` est un FICHIER : worktree git ou sous-module (git >= 2.5) ;
 *  - `ref: refs/remotes/...` : une branche distante n'est pas une branche locale.
 */
class GitHeadTest {

    @TempDir
    lateinit var tmp: File

    private fun writeFile(file: File, content: String) {
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    /** Cree un depot git minimal : <dir>/.git/HEAD. */
    private fun repo(dir: File, head: String): File {
        writeFile(File(dir, ".git/HEAD"), head)
        return dir
    }

    @Test
    @DisplayName("la branche locale est lue dans .git/HEAD")
    fun readsLocalBranchFromHead() {
        val dir = repo(tmp, "ref: refs/heads/main\n")
        assertEquals("main", GitHead.currentBranch(dir))
    }

    @Test
    @DisplayName("le nom de branche complet est conserve (avec /)")
    fun keepsSlashesInBranchName() {
        val dir = repo(tmp, "ref: refs/heads/feature/nouvelle-fonction\n")
        assertEquals("feature/nouvelle-fonction", GitHead.currentBranch(dir))
    }

    @Test
    @DisplayName("HEAD detache (SHA) : pas de branche a afficher")
    fun detachedHeadHasNoBranch() {
        val dir = repo(tmp, "9f2a1c4d8e7b6a5f4c3d2e1b0a9f8e7d6c5b4a39\n")
        assertNull(GitHead.currentBranch(dir))
    }

    @Test
    @DisplayName("pas de depot git : pas de branche")
    fun noRepositoryHasNoBranch() {
        val dir = File(tmp, "pas-un-depot")
        dir.mkdirs()
        assertNull(GitHead.currentBranch(dir))
    }

    @Test
    @DisplayName("le depot parent est trouve quand le projet est ouvert plus bas")
    fun findsRepositoryInParentDirectory() {
        val root = repo(tmp, "ref: refs/heads/release/2026-10\n")
        val sub = File(root, "src/main/kotlin")
        sub.mkdirs()
        assertEquals("release/2026-10", GitHead.currentBranch(sub))
    }

    @Test
    @DisplayName("worktree : .git est un fichier pointant vers gitdir:")
    fun supportsWorktreeWithGitFile() {
        val realGitDir = File(tmp, "depot-reel/.git/worktrees/feature")
        writeFile(File(realGitDir, "HEAD"), "ref: refs/heads/worktree/1\n")
        val worktree = File(tmp, "worktree")
        worktree.mkdirs()
        writeFile(File(worktree, ".git"), "gitdir: ${realGitDir.absolutePath}\n")
        assertEquals("worktree/1", GitHead.currentBranch(worktree))
    }

    @Test
    @DisplayName("une reference distante n'est pas une branche locale")
    fun remoteReferenceIsNotALocalBranch() {
        val dir = repo(tmp, "ref: refs/remotes/origin/main\n")
        assertNull(GitHead.currentBranch(dir))
    }

    @Test
    @DisplayName("le nom de branche n'est jamais vide")
    fun emptyBranchNameIsRejected() {
        val dir = repo(tmp, "ref: refs/heads/\n")
        assertNull(GitHead.currentBranch(dir))
    }

    // -------------------------------------------------------------------------
    // Correctifs du cycle 70 (relecture hostile). Chaque test reproduit un
    // comportement REEL de git, verifie avec `git rev-parse` (cf. le rapport).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("un pointeur gitdir: casse est une frontiere : pas de branche du depot parent")
    fun brokenGitDirPointerDoesNotFallBackToTheParentRepository() {
        // Depot PARENT, valide, sur `main`.
        val parent = repo(tmp, "ref: refs/heads/main\n")
        // Sous-module dont le repertoire de modules a disparu (ou depot deplace) :
        // `.git` est un FICHIER qui designe un gitdir inexistant.
        val child = File(parent, "enfant")
        child.mkdirs()
        writeFile(File(child, ".git"), "gitdir: ${File(parent, ".git/modules/enfant").absolutePath}\n")

        // git dit : « fatal: not a git repository ».
        // Le plugin ne doit donc PAS afficher « main » (la branche du parent,
        // sans rapport avec l'enfant) : il n'y a rien a afficher.
        assertNull(GitHead.currentBranch(child))
    }

    @Test
    @DisplayName("un .git dossier INCOMPLET est ignore : git remonte vers le parent")
    fun incompleteGitDirectoryIsIgnoredAndTheParentIsUsed() {
        // Depot PARENT valide, sur `main`.
        val parent = repo(tmp, "ref: refs/heads/main\n")
        // Sous-dossier contenant un `.git` VIDE (copie interrompue, zip, rsync).
        val child = File(parent, "sous-dossier")
        File(child, ".git").mkdirs()

        // git -C sous-dossier rev-parse --abbrev-ref HEAD  ->  « main »
        assertEquals("main", GitHead.currentBranch(child))
    }

    @Test
    @DisplayName("un projet ouvert 12 niveaux sous la racine du depot garde son badge")
    fun deepProjectStillFindsTheRepository() {
        val root = repo(tmp, "ref: refs/heads/main\n")
        // 12 niveaux : au-dela de l'ancien plafond de 8, le badge disparait
        // alors que git, lui, affiche la branche.
        var deep = root
        repeat(12) { deep = File(deep, "niveau"); deep.mkdirs() }
        assertEquals("main", GitHead.currentBranch(deep))
    }

    @Test
    @DisplayName("un nom de branche en octets non-UTF-8 est affiche sans caractere de remplacement")
    fun nonUtf8BranchNameIsDecodedWithoutReplacementCharacter() {
        val dir = File(tmp, "latin1")
        dir.mkdirs()
        // Git ecrit les refs en octets : « ref: refs/heads/café » en
        // latin1 (0xE9), pas de l'UTF-8 valide.
        val head = File(dir, ".git/HEAD")
        head.parentFile.mkdirs()
        head.writeBytes("ref: refs/heads/café\n".toByteArray(Charsets.ISO_8859_1))

        // Avant le correctif : « caf? » (U+FFFD de remplacement).
        // Apres : « café » (l'octet 0xE9 lu comme latin1).
        assertEquals("café", GitHead.currentBranch(dir))
    }

    // -------------------------------------------------------------------------
    // HEAD detache : le cas ou l'utilisateur veut JUSTE l'information
    // (amélioration demandée par la revue du 27/10).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("un HEAD detache donne le SHA court (7 caracteres)")
    fun detachedHeadGivesShortSha() {
        val dir = repo(tmp, "9f2a1c4d8e7b6a5f4c3d2e1b0a9f8e7d6c5b4a39\n")
        assertEquals("9f2a1c4", GitHead.detachedShortSha(dir))
    }

    @Test
    @DisplayName("une branche normale ne donne aucun SHA detache")
    fun normalHeadGivesNoDetachedSha() {
        val dir = repo(tmp, "ref: refs/heads/main\n")
        assertNull(GitHead.detachedShortSha(dir))
    }

    @Test
    @DisplayName("pas de depot git : pas de SHA detache")
    fun noRepositoryHasNoDetachedSha() {
        val dir = File(tmp, "pas-un-depot")
        dir.mkdirs()
        assertNull(GitHead.detachedShortSha(dir))
    }

    @Test
    @DisplayName("un HEAD corrompu (pas un SHA) ne donne pas de SHA detache")
    fun corruptedHeadGivesNoDetachedSha() {
        val dir = repo(tmp, "ceci-n-est-pas-un-sha\n")
        assertNull(GitHead.detachedShortSha(dir))
    }
}
