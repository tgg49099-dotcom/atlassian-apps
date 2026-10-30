package com.tgglabs.commithistorysearch.core

import java.io.ByteArrayOutputStream

/**
 * How a commit changed the searched string in one file.
 */
enum class ChangeKind(val label: String) {
    ADDED("added"),
    REMOVED("removed"),
    MODIFIED("modified"),
    UNKNOWN("changed")
}

/**
 * One file touched by a commit, with the number of matching lines added / removed.
 */
data class FileHit(
    val path: String,
    val addedMatches: Int,
    val removedMatches: Int,
    val sample: String?
) {
    val change: ChangeKind
        get() = when {
            addedMatches > 0 && removedMatches == 0 -> ChangeKind.ADDED
            removedMatches > 0 && addedMatches == 0 -> ChangeKind.REMOVED
            addedMatches > 0 && removedMatches > 0 -> ChangeKind.MODIFIED
            else -> ChangeKind.UNKNOWN
        }
}

/**
 * One commit found by the pickaxe search.
 */
data class CommitHit(
    val hash: String,
    val shortHash: String,
    val author: String,
    val email: String,
    val date: String,
    val subject: String,
    val files: List<FileHit>
)

/**
 * Parser for the output of
 * `git log --pretty=format:<record/field separators> -p -U0`.
 *
 * The record separator (U+001E) is emitted by git at the beginning of every commit header,
 * the field separator (U+001F) between the header fields. The patch of a commit follows its
 * own header, so splitting the whole output on the record separator gives one chunk per commit.
 *
 * This class is deliberately free of any IntelliJ dependency: it is covered by plain JUnit tests.
 */
object GitLogParser {

    const val RECORD: Char = '\u001e'
    const val FIELD: Char = '\u001f'

    /**
     * @param output raw stdout of the git command
     * @param matcher tells whether a diff line matches the searched text
     */
    fun parse(output: String, matcher: (String) -> Boolean): List<CommitHit> {
        val commits = ArrayList<CommitHit>()
        for (chunk in output.split(RECORD)) {
            if (chunk.isBlank()) continue
            val fields = chunk.split(FIELD)
            if (fields.size < 7) continue
            val hash = fields[0].trim()
            if (hash.length < 7) continue
            val files = parsePatch(fields[6], matcher)
            commits.add(
                CommitHit(
                    hash = hash,
                    shortHash = fields[1].trim(),
                    author = fields[2].trim(),
                    email = fields[3].trim(),
                    date = fields[4].trim(),
                    subject = fields[5].trim(),
                    // A commit found by git must never disappear: when no line of its patch
                    // could be matched (binary file, mode change...) it is kept with an
                    // empty file name.
                    files = files.ifEmpty { listOf(FileHit("", 0, 0, null)) }
                )
            )
        }
        return commits
    }

    /**
     * Parses the patch part of a commit and keeps the files that really contain a match.
     */
    fun parsePatch(patch: String, matcher: (String) -> Boolean): List<FileHit> {
        val hits = LinkedHashMap<String, Accumulator>()
        var oldPath: String? = null
        var newPath: String? = null
        var current: String? = null
        var inHunk = false

        for (line in patch.lines()) {
            if (line.startsWith("diff --git ")) {
                oldPath = null
                newPath = null
                current = null
                inHunk = false
                continue
            }
            if (!inHunk) {
                if (line.startsWith("--- ")) {
                    oldPath = pathOf(line.substring(4))
                    continue
                }
                if (line.startsWith("+++ ")) {
                    newPath = pathOf(line.substring(4))
                    val curr = newPath ?: oldPath
                    current = curr
                    if (curr != null) hits.getOrPut(curr) { Accumulator() }
                    continue
                }
                if (line.startsWith("rename from ")) {
                    oldPath = pathOf(line.substring("rename from ".length))
                    continue
                }
                if (line.startsWith("rename to ")) {
                    newPath = pathOf(line.substring("rename to ".length))
                    current = newPath
                    continue
                }
            }
            if (line.startsWith("@@")) {
                inHunk = true
                continue
            }
            if (inHunk && (line.startsWith("+") || line.startsWith("-"))) {
                val isAddition = line.startsWith("+")
                val text = line.substring(1)
                val path = current
                if (path != null && matcher(text)) {
                    val accumulator = hits.getOrPut(path) { Accumulator() }
                    if (isAddition) {
                        accumulator.added++
                        if (accumulator.sampleAdded == null) accumulator.sampleAdded = text.trim()
                    } else {
                        accumulator.removed++
                        if (accumulator.sampleRemoved == null) accumulator.sampleRemoved = text.trim()
                    }
                }
            }
        }
        return hits
            .filter { it.value.added > 0 || it.value.removed > 0 }
            .map { (path, accumulator) ->
                FileHit(
                    path,
                    accumulator.added,
                    accumulator.removed,
                    accumulator.sampleAdded ?: accumulator.sampleRemoved
                )
            }
    }

    /** "b/src/main.kt" -> "src/main.kt"; "/dev/null" -> null */
    private fun pathOf(raw: String): String? {
        var path = raw.trim()
        if (path == "/dev/null") return null
        path = unquote(path)
        if (path.startsWith("a/") || path.startsWith("b/")) path = path.substring(2)
        return path.ifEmpty { null }
    }

    /**
     * git quotes exotic paths: "src/a\"b.kt" and, when core.quotePath is on, the bytes of
     * a name that is not ASCII in octal: "caf\303\251.txt".
     *
     * Without decoding the escapes, the path of such a file is wrong, `git show` cannot
     * find it and the diff of the file opens EMPTY. The octal escapes are BYTES of the
     * name: they are collected as bytes and decoded as UTF-8 at the end.
     */
    private fun unquote(path: String): String {
        if (path.length < 2 || !path.startsWith('"') || !path.endsWith('"')) return path
        val body = path.substring(1, path.length - 1)
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (ch == '\\' && i + 1 < body.length) {
                val next = body[i + 1]
                if (next in '0'..'7') {
                    // octal escape: one to three digits, the value is one byte of the name
                    var value = 0
                    var digits = 0
                    var j = i + 1
                    while (j < body.length && digits < 3 && body[j] in '0'..'7') {
                        value = value * 8 + (body[j] - '0')
                        j++
                        digits++
                    }
                    bytes.write(value)
                    i = j
                    continue
                }
                // \" \\ \t \n ... : the escaped character itself
                val decoded = when (next) {
                    'a' -> '\u0007'
                    'b' -> '\b'
                    't' -> '\t'
                    'n' -> '\n'
                    'v' -> '\u000b'
                    'f' -> '\u000c'
                    'r' -> '\r'
                    else -> next
                }
                bytes.write(decoded.toString().toByteArray(Charsets.UTF_8))
                i += 2
                continue
            }
            bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private class Accumulator {
        var added: Int = 0
        var removed: Int = 0
        var sampleAdded: String? = null
        var sampleRemoved: String? = null
    }
}
