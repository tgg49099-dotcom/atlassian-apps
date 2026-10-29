package com.tgglabs.commithistorysearch.core

/**
 * The sentence shown under the result table.
 *
 * It lives in the core package, and not in the dialog, for one reason: what it says must be
 * TESTABLE without the IDE.
 *
 * The result list is capped at [GitHistorySearch.MAX_COMMITS] matching commits: git's
 * `--max-count` limits the number of commits DISPLAYED, not the depth of the scan (measured
 * on 29/10/2026, git 2.39.5). When the cap is reached the user has to be told that the list
 * is not the whole truth: without that warning, a search on a common string looks exhaustive
 * when it is not.
 */
object SearchSummary {

    /**
     * The sentence shown when the cap is reached.
     *
     * @param maxCommits the number of matching commits the list can hold
     */
    fun describe(
        rowCount: Int,
        commitCount: Int,
        query: String,
        maxCommits: Int = GitHistorySearch.MAX_COMMITS
    ): String {
        if (rowCount == 0) return "No commit diff contains \"$query\"."
        val base = "$rowCount match(es) in $commitCount commit(s)."
        return if (commitCount >= maxCommits) {
            "$base Only the $maxCommits most recent matching commits are listed; " +
                "older matches, if any, are not shown."
        } else {
            base
        }
    }
}
