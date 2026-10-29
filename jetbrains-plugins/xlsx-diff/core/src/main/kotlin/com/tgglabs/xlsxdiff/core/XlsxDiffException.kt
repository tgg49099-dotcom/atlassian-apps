package com.tgglabs.xlsxdiff.core

/**
 * Error that is safe to show to an end user.
 *
 * The message never mentions the underlying spreadsheet library: a user only
 * needs to know which file is broken and what to do about it.
 *
 * The messages are **in English**, like the rest of the strings the user
 * reads (the listing of the plugin is in English).
 */
class XlsxDiffException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when the user cancels the comparison.
 *
 * This is not an error: the comparison simply produces no result. It is a
 * [RuntimeException] so that the code of the comparison does not have to
 * declare it everywhere, but it must never be shown as a failure.
 */
class XlsxDiffCancelledException : RuntimeException("The comparison was cancelled.")
