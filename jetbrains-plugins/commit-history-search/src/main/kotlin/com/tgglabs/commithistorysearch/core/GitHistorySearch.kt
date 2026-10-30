package com.tgglabs.commithistorysearch.core

import java.io.File
import java.util.regex.Pattern

/**
 * The pickaxe search itself: the equivalent of `git log -G<text>` (a literal, escaped, or a
 * regular expression) run over the whole history of a repository.
 *
 * The scan covers the whole history of the current branch (minus the merge commits): only the
 * number of results SHOWN is capped, see [MAX_COMMITS].
 *
 * In "regular expression" mode the pattern of the user is a JAVA regular expression for the
 * filter of the lines (see [matcherPattern]), and a POSIX ERE for git (see [PosixEre.toEre]):
 * the two syntaxes are not the same, and git is the one that decides which commits are found.
 */
class GitHistorySearch(
    private val root: File,
    private val query: String,
    private val regex: Boolean = false,
    private val ignoreCase: Boolean = true
) {

    companion object {
        /**
         * Maximum number of MATCHING commits shown in the result list (git's `--max-count`).
         * It caps the size of the list, NOT the depth of the scan: git walks the whole history
         * of the branch and stops when it has that many matches (measured on 29/10/2026).
         */
        const val MAX_COMMITS = 500

        /**
         * Header of every commit: record separator, then the fields, then the patch.
         * The body of the message is deliberately left out: a message line starting with
         * "+" or "-" must never be counted as a diff line.
         */
        const val PRETTY = "--pretty=format:%x1e%H%x1f%h%x1f%an%x1f%ae%x1f%aI%x1f%s%x1f"

        /**
         * Escapes a literal so that it can be given to git as a regular expression.
         * With [ignoreCase], every letter becomes a character class [aA] : git's pickaxe
         * has no case insensitive mode, this is the portable equivalent.
         */
        fun literalToRegex(literal: String, ignoreCase: Boolean): String {
            val sb = StringBuilder()
            for (ch in literal) {
                when {
                    ch.isLetter() -> {
                        if (ignoreCase) {
                            sb.append('[').append(ch.lowercaseChar()).append(ch.uppercaseChar()).append(']')
                        } else {
                            sb.append(ch)
                        }
                    }
                    ch in ".^$*+?()[]{}|\\" -> sb.append('\\').append(ch)
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }

    /**
     * The pattern used to tell whether a diff line matches. In text mode the query is a
     * literal: it is escaped so that git and Java agree on what a match is.
     *
     * In regular expression mode the pattern is the one of the USER, unchanged: the Java
     * filter must understand `\d` (it does) and the conversion to POSIX ERE (which git
     * understands) is done for git only, see [PosixEre.toEre].
     */
    private val matcherPattern: Pattern = if (regex) {
        val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0
        try {
            Pattern.compile(query, flags)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid regular expression: ${e.message}", e)
        }
    } else {
        Pattern.compile(literalToRegex(query, ignoreCase))
    }

    private val matcher: (String) -> Boolean = { line -> matcherPattern.matcher(line).find() }

    /**
     * @param cancelled polled while git runs, so that the user can stop the search
     * @return the commits whose diffs contain the searched text
     */
    fun search(cancelled: () -> Boolean = { false }): List<CommitHit> {
        val git = GitLocator.find()
            ?: throw GitException("git executable not found (checked PATH and the usual install locations)")
        if (GitLocator.findRepositoryRoot(root) == null) {
            throw GitException("$root is not inside a Git repository")
        }
        if (!hasCommit(git, cancelled)) {
            // A brand new repository has no commit yet: `git log` fails with "your current
            // branch does not have any commits yet" (exit code 128). There is no diff to
            // search, so the answer is "nothing found", not an error.
            return emptyList()
        }
        val output = try {
            GitCommand.run(git, root, buildArguments(), cancelled)
        } catch (e: GitTimeoutException) {
            // A timeout must not be retried: it would pay the maximum delay twice.
            throw e
        }
        return GitLogParser.parse(output, matcher)
    }

    /**
     * @return false when the repository has no commit yet (there is nothing to search)
     */
    private fun hasCommit(git: File, cancelled: () -> Boolean): Boolean =
        GitCommand.runOrNull(git, root, listOf("rev-parse", "--verify", "--quiet", "HEAD"), cancelled) != null

    fun buildArguments(): List<String> {
        val args = ArrayList<String>()
        // git escapes the path of the files whose name is not ASCII ("caf\303\251.txt").
        // The escaped path cannot be given back to git, so the diff of such a file would
        // open empty. core.quotePath=false gives the real path in the diff.
        args.add("-c")
        args.add("core.quotePath=false")
        // `git log` starts a pager (less) when it can: in the IDE there is no terminal to
        // interact with, so the pager would wait for a keyboard input that never comes
        // (the IDE freezes) and it would pollute the captured output with its escape codes.
        // --no-pager is a global git option, it must come before the sub command.
        args.add("--no-pager")
        args.add("log")
        args.add("--no-color")
        args.add("--no-merges")
        args.add("-U0")
        args.add("-p")
        args.add("--max-count=$MAX_COMMITS")
        // The pickaxe is ALWAYS -G. `git log -S` only reports the commits where the
        // NUMBER of occurrences of the text changed: a line that is modified without
        // changing that number was missed, while the plugin promises the commits where
        // the text was "added, removed or modified". -G matches the text in the added
        // and in the removed lines, so a modification is found too.
        //
        // git compiles the pattern of -G with POSIX EXTENDED regular expressions, and
        // `--perl-regexp` does NOT change that (measured on 30/10/2026 on git 2.39.5 and
        // git 2.43 : `-G'\d+'` finds nothing, with or without --perl-regexp). The pattern
        // is therefore converted to POSIX ERE before it is given to git.
        args.add("--extended-regexp")
        // A user regex is case sensitive for git unless -i is given (the Java filter is not):
        // without it, commits that differ only by case are missed.
        if (regex && ignoreCase) args.add("--regexp-ignore-case")
        args.add(PRETTY)
        args.add("-G" + if (regex) PosixEre.toEre(query) else literalToRegex(query, ignoreCase))
        return args
    }
}
