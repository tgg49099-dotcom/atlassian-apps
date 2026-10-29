package com.tgglabs.commithistorysearch.core

import java.io.File
import java.util.regex.Pattern

/**
 * The pickaxe search itself: the equivalent of `git log -S<text>` (literal, case sensitive)
 * or `git log -G<regex>` (regular expression) run over the whole history of a repository.
 *
 * The scan covers the whole history of the current branch (minus the merge commits): only the
 * number of results SHOWN is capped, see [MAX_COMMITS].
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
            GitCommand.run(git, root, buildArguments(perlRegex = regex), cancelled)
        } catch (e: GitTimeoutException) {
            // A timeout must not be retried: the fallback would pay the maximum delay twice.
            throw e
        } catch (e: GitException) {
            // Some git builds are compiled without PCRE: fall back to POSIX extended regexps.
            if (!regex) throw e
            GitCommand.run(git, root, buildArguments(perlRegex = false), cancelled)
        }
        return GitLogParser.parse(output, matcher)
    }

    /**
     * @return false when the repository has no commit yet (there is nothing to search)
     */
    private fun hasCommit(git: File, cancelled: () -> Boolean): Boolean =
        GitCommand.runOrNull(git, root, listOf("rev-parse", "--verify", "--quiet", "HEAD"), cancelled) != null

    fun buildArguments(perlRegex: Boolean = regex): List<String> {
        val args = ArrayList<String>()
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
        // git log -S is a literal, case sensitive pickaxe: it is used as is when the search
        // is a plain text and the case matters. Every other case goes through -G.
        val usePickaxeS = !regex && !ignoreCase
        if (!usePickaxeS) {
            if (perlRegex) args.add("--perl-regexp") else args.add("--extended-regexp")
        }
        // A user regex is case sensitive for git unless -i is given (the Java filter is not):
        // without it, commits that differ only by case are missed.
        if (regex && ignoreCase) args.add("--regexp-ignore-case")
        args.add(PRETTY)
        val pickaxe = if (usePickaxeS) "-S$query" else "-G" + if (regex) query else literalToRegex(query, ignoreCase)
        args.add(pickaxe)
        return args
    }
}
