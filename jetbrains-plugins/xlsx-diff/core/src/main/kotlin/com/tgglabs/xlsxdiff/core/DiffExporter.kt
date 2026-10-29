package com.tgglabs.xlsxdiff.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Turns a [DiffResult] into something a user can save and share.
 *
 * Three rules drive the format, all of them because of what the file is
 * opened with on the user's computer:
 *
 *  - the file is **always** written in UTF-8, whatever the encoding of the
 *    machine it is written on. Without that, a French Windows (cp1252) breaks
 *    every accent of the report;
 *  - the CSV separates its columns with **','**, the separator of the CSV
 *    standard (RFC 4180) and the one Excel, LibreOffice, Google Sheets and
 *    every script expect. With ';' an English Excel puts the whole line in a
 *    single cell;
 *  - a value that Excel would read as a **formula** is neutralised, so that
 *    opening the report cannot execute anything (CSV injection). The rule is
 *    applied to **every** value of the report, and to every cell of a row
 *    whose values are joined into a single column.
 */
object DiffExporter {

    /** The separator of the CSV: ',' is the standard one. */
    const val SEPARATOR = ","

    /** The header line of the CSV. */
    const val HEADER =
        "type${SEPARATOR}sheet${SEPARATOR}cell${SEPARATOR}row${SEPARATOR}column" +
            "${SEPARATOR}left_value${SEPARATOR}right_value" +
            "${SEPARATOR}left_formula${SEPARATOR}right_formula"

    /**
     * The UTF-8 byte order mark.
     *
     * It is written at the very beginning of the CSV because Excel only
     * recognises UTF-8 in a spreadsheet file when the file starts with it:
     * without the mark, Excel reads the file with the local code page and
     * every accent is broken.
     */
    const val UTF8_BOM = "\uFEFF"

    /**
     * The note shown next to the export buttons.
     *
     * It says exactly what the exporter does to a value that Excel would read
     * as a formula, so that nobody wonders why a cell starts with an
     * apostrophe. The wording is the one the rule below implements, and it is
     * also written in the README.
     */
    const val FORMULA_SAFETY_NOTE =
        "A cell whose text starts with = + - @ or a tabulation is prefixed with an " +
            "apostrophe, for Excel safety."

    /**
     * A flat CSV, one line per difference.
     *
     * Columns: type, sheet, cell, row, column, left_value, right_value,
     * left_formula, right_formula
     *
     * The returned text has no byte order mark: it is the content of the
     * report, the mark is added when the file is written.
     */
    fun toCsv(result: DiffResult): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (sheet in result.sheets) {
            when (sheet.change) {
                SheetChange.ONLY_LEFT -> sb.append(fields(
                    "SHEET_ONLY_LEFT", sheet.name, "", "", "",
                    "${sheet.leftColumnCount} column(s)", "", "", "",
                ))
                SheetChange.ONLY_RIGHT -> sb.append(fields(
                    "SHEET_ONLY_RIGHT", sheet.name, "", "", "",
                    "", "${sheet.rightColumnCount} column(s)", "", "",
                ))
                null -> Unit
            }
            for (rowDiff in sheet.rows) {
                when (rowDiff.change) {
                    RowChange.MODIFIED -> {
                        for (cell in rowDiff.cells) {
                            sb.append(fields(
                                cell.changes.joinToString("+") { it.name },
                                sheet.name,
                                cell.ref.address,
                                cell.ref.row.toString(),
                                cell.ref.columnName,
                                // only the *values* are neutralised: the structural
                                // columns (type, sheet, cell, row, column) are data of
                                // the diff itself, not content of the spreadsheets.
                                sanitize(cell.leftValue ?: ""),
                                sanitize(cell.rightValue ?: ""),
                                sanitize(cell.leftFormula ?: ""),
                                sanitize(cell.rightFormula ?: ""),
                            ))
                        }
                    }
                    RowChange.ADDED -> {
                        sb.append(fields(
                            "ROW_ADDED", sheet.name, "", rowDiff.row.toString(), "",
                            "", joinedValues(rowDiff.cells) { it.rightValue },
                            "", "",
                        ))
                    }
                    RowChange.REMOVED -> {
                        sb.append(fields(
                            "ROW_REMOVED", sheet.name, "", rowDiff.row.toString(), "",
                            joinedValues(rowDiff.cells) { it.leftValue },
                            "", "", "",
                        ))
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * A human-readable report.
     */
    fun toText(result: DiffResult): String {
        val sb = StringBuilder()
        sb.append("XLSX Diff\n")
        sb.append("Left file: ").append(result.leftPath).append('\n')
        sb.append("Right file: ").append(result.rightPath).append('\n')
        sb.append('\n')
        if (!result.hasDifferences) {
            sb.append("No differences: the two files are identical.\n")
            return sb.toString()
        }
        sb.append("${result.changedRowCount} row(s) differ, ")
            .append("${result.modifiedCellCount} cell(s) modified, ")
            .append("${result.exclusiveSheetCount} sheet(s) in one file only.\n")
        for (sheet in result.sheets) {
            sb.append('\n').append("Sheet \"").append(sheet.name).append("\"")
            when (sheet.change) {
                SheetChange.ONLY_LEFT -> sb.append(": only in the left file.")
                SheetChange.ONLY_RIGHT -> sb.append(": only in the right file.")
                null -> sb.append(": ").append(sheet.rows.size).append(" row(s) differ.")
            }
            sb.append('\n')
            for (rowDiff in sheet.rows) {
                val label = when (rowDiff.change) {
                    RowChange.ADDED -> "ADDED"
                    RowChange.REMOVED -> "REMOVED"
                    RowChange.MODIFIED -> "MODIFIED"
                }
                sb.append("  Row ").append(rowDiff.row).append(" ").append(label)
                if (rowDiff.change == RowChange.MODIFIED) {
                    sb.append(" (").append(rowDiff.cells.size).append(" cell(s))")
                }
                sb.append('\n')
                for (cell in rowDiff.cells) {
                    sb.append("    ").append(cell.ref.address).append(" ")
                        .append(cell.changes.joinToString("+"))
                    if (rowDiff.change != RowChange.MODIFIED) continue
                    sb.append("  left=").append(quote(cell.leftValue))
                        .append("  right=").append(quote(cell.rightValue))
                    if (cell.leftFormula != null || cell.rightFormula != null) {
                        sb.append("  formula(").append(quote(cell.leftFormula))
                            .append(" -> ").append(quote(cell.rightFormula)).append(")")
                    }
                    sb.append('\n')
                }
            }
        }
        return sb.toString()
    }

    /**
     * Writes the diff as a CSV file.
     *
     * The file is written in UTF-8, with the byte order mark at the beginning
     * so that Excel opens it correctly.
     */
    fun writeCsv(path: Path, result: DiffResult) {
        Files.writeString(path, UTF8_BOM + toCsv(result), StandardCharsets.UTF_8)
    }

    /**
     * Writes the diff as a text file, in UTF-8.
     */
    fun writeText(path: Path, result: DiffResult) {
        Files.writeString(path, toText(result), StandardCharsets.UTF_8)
    }

    private fun quote(value: String?): String {
        if (value == null) return "-"
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** One line of the CSV: every field is quoted when it has to be. */
    private fun fields(vararg cells: String): String =
        cells.joinToString(SEPARATOR) { csvField(sanitize(it)) } + "\n"

    /**
     * A field of the CSV is quoted when it holds the separator, a quote or a
     * line break: otherwise the columns would not be cut in the right place.
     */
    private fun csvField(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    /**
     * The values of the cells of a row, joined for the CSV.
     *
     * Every value is neutralised **on its own**, before the join.
     *
     * The rule of the apostrophe looks at the first character of a value, and
     * a value is a **cell**: when the cells of a row are joined into a single
     * field, the rule has to be applied to each cell, not to the joined
     * string. Joining first protected only the first cell of the row, and a
     * formula in the second column (or further) came out raw: Excel ran it
     * when the report was opened (28/10/2026).
     */
    private fun joinedValues(cells: List<CellDiff>, value: (CellDiff) -> String?): String =
        cells.joinToString(" | ") { sanitize(value(it) ?: "") }

    /**
     * Neutralises a value that Excel would read as a formula.
     *
     * A cell whose text starts with '=', '+', '-', '@' or a tabulation is read
     * as a formula by Excel when the CSV is opened: a malicious spreadsheet
     * could then make something happen on the computer of the person who
     * reads the report. Prefixing the value with an apostrophe forces Excel to
     * read it as text.
     *
     * The rule is the same for **every** field of the CSV, the name of the
     * sheet included: a sheet named "=cmd|..." is as dangerous as a cell, and
     * the name of a sheet comes from the file that is being compared.
     *
     * The rule looks at the **first character** of the value: a text that
     * starts with a number ("42") or with a letter is left exactly as it is,
     * so the export stays faithful to the spreadsheets. A negative number
     * ("-42") starts with a sign, and is therefore neutralised too: Excel
     * would otherwise read "-2+3" as a formula.
     */
    private fun sanitize(value: String): String {
        if (value.isEmpty()) return value
        if (isDangerousStart(value[0])) return "'" + value
        val trimmed = value.trimStart()
        // Leading blanks do not hide a formula: " =1+1" is read as a formula too.
        if (trimmed.isNotEmpty() && isDangerousStart(trimmed[0])) return "'" + value
        return value
    }

    /** True when the character makes Excel read what follows as a formula. */
    private fun isDangerousStart(character: Char): Boolean =
        character == '=' || character == '+' || character == '-' || character == '@' ||
            character == '\t' || character == '\r' || character == '\n'
}
