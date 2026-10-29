package com.tgglabs.branchbadge

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Lecture de la branche Git courante.
 *
 * Source de verite : le fichier `.git/HEAD`, c'est-a-dire exactement ce que
 * Git lit lui-meme. Aucun import IntelliJ ici : cette classe est testable
 * hors IDE (JUnit pur), ce qui permet la preuve ROUGE -> VERT.
 *
 * Cas couverts :
 *  - depot normal : `.git` est un dossier, `HEAD` contient `ref: refs/heads/<branche>` ;
 *  - projet ouvert plus bas que la racine du depot : on remonte les dossiers ;
 *  - worktree git ou sous-module : `.git` est un FICHIER `gitdir: <chemin>` ;
 *  - HEAD detache (SHA) ou reference distante : aucune branche locale a afficher.
 */
object GitHead {

    /**
     * Nombre maximum de niveaux de dossiers remontes pour trouver `.git`.
     *
     * 8 etait trop court : un projet ouvert 9 niveaux sous la racine du depot
     * (typiquement `monorepo/packages/frontend/apps/web/src/main/kotlin/...`)
     * perdait le badge alors que git, lui, affiche la branche. 32 couvre les
     * cas reels sans jamais boucler (la remontee s'arrete a la racine du
     * systeme de fichiers).
     */
    const val MAX_PARENTS: Int = 32

    private const val REF_PREFIX = "ref: "
    private const val HEADS_PREFIX = "refs/heads/"
    private const val GITDIR_PREFIX = "gitdir:"

    /** Un SHA-1 complet : 40 caracteres hexadecimaux. */
    private val SHA_RE = Regex("[0-9a-fA-F]{40}")

    /**
     * Le repertoire git du depot qui contient [dir], ou null si [dir] n'est
     * dans aucun depot (a la racine du disque, ou au-dela de [MAX_PARENTS]).
     *
     * Les deux regles de frontieriere sont celles de git lui-meme (verifiees
     * avec `git rev-parse`, cf. les tests) :
     *  - un `.git` **dossier** qui n'est pas un vrai depot (dossier vide,
     *    copie interrompue) est IGNORE : git remonte vers le parent ;
     *  - un `.git` **fichier** est une FRONTIERE : git ne remonte JAMAIS
     *    vers le parent. Si le `gitdir:` qu'il designe est introuvable, il n'y
     *    a pas de branche a afficher — surtout pas celle d'un depot sans
     *    rapport trouve plus haut.
     */
    fun findGitDir(dir: File): File? {
        var current: File? = dir.absoluteFile
        var depth = 0
        while (current != null && depth <= MAX_PARENTS) {
            val dotGit = File(current, ".git")
            if (dotGit.isDirectory) {
                // `.git` incomplet (pas de HEAD) : git le saute et continue
                // vers le parent — on fait pareil, sinon le badge disparait
                // alors que `git branch` affiche une branche.
                if (File(dotGit, "HEAD").isFile) return dotGit
            } else if (dotGit.isFile) {
                // worktree ou sous-module : `.git` est un fichier `gitdir: <chemin>`
                val target = readGitDirPointer(dotGit)
                // Frontiere de depot : on ne cherche pas plus haut.
                return if (target != null && target.isDirectory) target else null
            }
            current = current.parentFile
            depth++
        }
        return null
    }

    /**
     * Le nom de la branche locale courante du depot qui contient [dir],
     * ou null quand il n'y a rien a afficher (pas de depot, HEAD detache,
     * reference distante, nom vide).
     */
    fun currentBranch(dir: File): String? {
        val gitDir = findGitDir(dir) ?: return null
        val head = File(gitDir, "HEAD")
        if (!head.isFile) return null
        val content = try {
            readHead(head)
        } catch (e: Exception) {
            return null
        }
        // HEAD detache : le fichier contient un SHA, pas une reference.
        if (!content.startsWith(REF_PREFIX)) return null
        val ref = content.removePrefix(REF_PREFIX).trim()
        // Une reference distante (refs/remotes/...) n'est pas une branche locale.
        if (!ref.startsWith(HEADS_PREFIX)) return null
        val name = ref.removePrefix(HEADS_PREFIX)
        return name.ifEmpty { null }
    }

    /**
     * Le SHA COURT quand le HEAD du depot qui contient [dir] est DETACHE
     * (`git checkout <sha>` : le fichier HEAD contient un SHA et non une
     * reference), sinon null.
     *
     * C'est le cas ou l'utilisateur veut JUSTE l'information : on affiche
     * « detached @ abc1234 » plutot que de faire disparaitre le badge.
     *
     * Le SHA lu est verifie : seuls les 40 caracteres hexadecimaux d'un vrai
     * SHA-1 sont acceptes. Un fichier HEAD corrompu ne doit pas produire une
     * etiquette bizarre.
     */
    fun detachedShortSha(dir: File): String? {
        val gitDir = findGitDir(dir) ?: return null
        val head = File(gitDir, "HEAD")
        if (!head.isFile) return null
        val content = try {
            readHead(head)
        } catch (e: Exception) {
            return null
        }
        // HEAD normal : il y a une reference, donc pas de HEAD detache.
        if (content.startsWith(REF_PREFIX)) return null
        val sha = content.trim()
        if (!SHA_RE.matches(sha)) return null
        return sha.take(BranchLabel.SHORT_SHA_LENGTH)
    }

    /**
     * Lit le fichier HEAD.
     *
     * Git ecrit les noms de branche en OCTETS (UTF-8 par convention, mais
     * aucun octet n'est interdit hors NUL/LF). `readText()` decode avec le
     * charset PAR DEFAUT de la JVM et REMPLACE les octets invalides par
     * U+FFFD : un nom de branche latin1 s'affichait `feat/<?>`.
     * On decode donc en UTF-8 STRICT, et on retombe sur ISO-8859-1 (qui ne
     * peut pas echouer : 1 octet -> 1 caractere) quand le nom n'est pas de
     * l'UTF-8 valide.
     */
    private fun readHead(head: File): String {
        val bytes = head.readBytes()
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString().trim()
        } catch (e: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1).trim()
        }
    }

    /** Lit un fichier `.git` de type pointeur (`gitdir: <chemin>`). */
    private fun readGitDirPointer(pointer: File): File? {
        val content = try {
            pointer.readText().trim()
        } catch (e: Exception) {
            return null
        }
        if (!content.startsWith(GITDIR_PREFIX)) return null
        val path = content.removePrefix(GITDIR_PREFIX).trim()
        if (path.isEmpty()) return null
        val target = File(path)
        return if (target.isAbsolute) target else File(pointer.parentFile, path)
    }
}
