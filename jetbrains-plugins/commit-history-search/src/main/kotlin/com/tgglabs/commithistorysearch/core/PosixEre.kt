package com.tgglabs.commithistorysearch.core

/**
 * git's pickaxe (`git log -G`) compiles its pattern with POSIX **extended** regular
 * expressions, whatever the options are: `--perl-regexp` does NOT change that.
 *
 * Measured on 30/10/2026 (git 2.39.5 here, git 2.43 on the reviewer's machine):
 * `-G'\d+'` finds NOTHING, with or without `--perl-regexp` ; `-G'[0-9]+'` finds the commit.
 *
 * The window, on the other hand, filters the lines with a JAVA regular expression, which
 * DOES understand `\d`. The user who typed `\d+` therefore saw the Java filter say "it
 * matches" while git returned nothing at all: no result, and no message either.
 *
 * This object does two things:
 *  - it converts the pattern of the user into the POSIX ERE that git understands ;
 *  - it lists the constructs that POSIX ERE cannot do at all, so that the window can
 *    WARN the user (in the window itself) instead of silently returning nothing.
 */
object PosixEre {

    /**
     * The label of the "Regular expression" checkbox.
     *
     * It must not hide the difference between the two syntaxes: the user has to know
     * that the pattern is a POSIX ERE (the one of `git -G`), not a Java regular expression.
     */
    const val REGEX_LABEL = "Regular expression (POSIX ERE, like git -G: use [0-9] not \\d)"

    /**
     * The Java classes that POSIX ERE does not know, and their POSIX equivalent.
     *
     * `\w` and `\s` happen to work with the C library of glibc, but they are NOT POSIX:
     * they fail on musl (Alpine) and on BSD. The bracket expressions work everywhere.
     *
     * The negated forms are written with a balanced bracket: `[^_[:alnum:]]` (a set that
     * excludes the underscore and the alphanumeric characters) and `[^[:space:]]`.
     */
    private val CLASSES: Map<Char, String> = mapOf(
        'd' to "[0-9]",
        'D' to "[^0-9]",
        'w' to "[_[:alnum:]]",
        'W' to "[^_[:alnum:]]",
        's' to "[[:space:]]",
        'S' to "[^[:space:]]"
    )

    /**
     * Converts a Java regular expression into the POSIX ERE that git compiles.
     *
     * The replacement happens OUTSIDE the bracket expressions: inside `[...]`, `\d` is not
     * a class but a literal backslash and a literal "d" for POSIX, and converting it
     * there would change the meaning of the set. Such a pattern is reported by
     * [warnings] instead.
     */
    fun toEre(pattern: String): String {
        val out = StringBuilder(pattern.length)
        var index = 0
        var inBracket = false
        while (index < pattern.length) {
            val ch = pattern[index]
            if (ch == '\\' && index + 1 < pattern.length) {
                val next = pattern[index + 1]
                val replacement = if (inBracket) null else CLASSES[next]
                if (replacement != null) {
                    out.append(replacement)
                } else {
                    out.append(ch).append(next)
                }
                index += 2
                continue
            }
            if (!inBracket && ch == '[') {
                inBracket = true
                out.append(ch)
                index++
                // In POSIX, a ']' written just after '[' (or just after '[^') is a literal
                // ']' and does not close the set.
                if (index < pattern.length && pattern[index] == ']') {
                    out.append(']')
                    index++
                } else if (index + 1 < pattern.length &&
                    pattern[index] == '^' && pattern[index + 1] == ']'
                ) {
                    out.append('^').append(']')
                    index += 2
                }
                continue
            }
            if (inBracket && ch == ']') {
                inBracket = false
            }
            out.append(ch)
            index++
        }
        return out.toString()
    }

    /**
     * The constructs of the pattern that POSIX ERE (git -G) cannot do.
     *
     * The list is empty when the pattern can be given to git as it is.
     */
    fun warnings(pattern: String): List<String> {
        val found = LinkedHashSet<String>()
        var index = 0
        var inBracket = false
        while (index < pattern.length) {
            val ch = pattern[index]
            if (ch == '\\' && index + 1 < pattern.length) {
                val next = pattern[index + 1]
                if (next in '1'..'9') {
                    found.add(
                        "POSIX ERE (git -G) has no backreference: \\$next is not supported."
                    )
                } else if (inBracket && CLASSES.containsKey(next)) {
                    found.add(
                        "\\$next inside [...] is not converted: git reads it literally " +
                            "(write [0-9] and not \\d)."
                    )
                }
                index += 2
                continue
            }
            if (!inBracket && ch == '[') {
                inBracket = true
                index++
                if (index < pattern.length && pattern[index] == ']') index++
                continue
            }
            if (inBracket && ch == ']') {
                inBracket = false
                index++
                continue
            }
            if (!inBracket && ch == '(' && index + 1 < pattern.length && pattern[index + 1] == '?') {
                val rest = pattern.substring(index)
                when {
                    rest.startsWith("(?=") || rest.startsWith("(?!") ->
                        found.add("POSIX ERE (git -G) has no lookahead: (?= and (?!) are not supported.")
                    rest.startsWith("(?<=") || rest.startsWith("(?<!") ->
                        found.add("POSIX ERE (git -G) has no lookbehind: (?<= and (?<! are not supported.")
                    else ->
                        found.add(
                            "POSIX ERE (git -G) does not understand the syntax \"(?\" " +
                                "(group or flag, like (?i)): it is not supported."
                        )
                }
                index += 2
                continue
            }
            index++
        }
        return found.toList()
    }
}
