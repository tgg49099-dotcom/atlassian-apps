package com.tgglabs.xlsxdiff.core

import org.apache.poi.EncryptedDocumentException
import org.apache.poi.openxml4j.exceptions.InvalidFormatException
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File
import java.io.IOException
import java.util.Locale
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Compares two `.xlsx` workbooks, cell by cell.
 *
 * The two files are opened read-only: they are never written to.
 *
 * How the rows are matched: the rows that hold something are aligned with a
 * longest-common-subsequence over their content, so that a row inserted in the
 * middle of a sheet does not turn every following row into a "modified" row.
 * Inside a gap between two matched rows, the remaining rows are paired by
 * position: a row that changed becomes a modified row, a row that disappeared
 * becomes a removed row and a row that appeared becomes an added row.
 *
 * The messages of this class are **in English**, like the rest of the strings
 * the user reads (the listing of the plugin is in English).
 */
class XlsxDiffEngine(private val limits: XlsxDiffLimits = XlsxDiffLimits()) {

    /**
     * The reader of the cells.
     *
     * One instance per comparison: the `DataFormatter` it holds is not safe
     * to share between threads, and two comparisons can run at the same time
     * (one per project).
     */
    private val reader = Cells()

    /**
     * Compares [left] and [right].
     *
     * @throws XlsxDiffException when a file cannot be read, is too large or
     *         holds too many cells; the message is meant for an end user and
     *         never names the underlying library.
     */
    fun diff(left: Path, right: Path): DiffResult = diff(left, right, Cancellation.NEVER)

    /**
     * Compares [left] and [right], stopping as soon as [cancellation] says so.
     *
     * @throws XlsxDiffCancelledException when the caller asked to stop.
     * @throws XlsxDiffException when a file cannot be read.
     */
    fun diff(left: Path, right: Path, cancellation: Cancellation): DiffResult {
        val leftBook = openWorkbook(left)
        // 27/10/2026 : si le second classeur ne peut pas etre ouvert, le premier
        // doit etre ferme (fuite de descripteur de fichier). Corrige ici.
        val rightBook = try {
            openWorkbook(right)
        } catch (e: Throwable) {
            closeQuietly(leftBook)
            throw e
        }
        try {
            return diffBooks(left, right, leftBook, rightBook, cancellation)
        } finally {
            closeQuietly(leftBook)
            closeQuietly(rightBook)
        }
    }

    private fun diffBooks(
        leftPath: Path,
        rightPath: Path,
        left: Workbook,
        right: Workbook,
        cancellation: Cancellation,
    ): DiffResult {
        val leftNames = sheetNames(left)
        val rightNames = sheetNames(right)
        val sheets = ArrayList<SheetDiff>()
        val budget = CellBudget(limits.maxCells)

        // Sheets that exist in only one of the two files.
        for (name in leftNames) {
            checkCanceled(cancellation)
            if (rightNames.contains(name)) continue
            val sheet = left.getSheet(name)
            sheets.add(
                SheetDiff(
                    name = name,
                    change = SheetChange.ONLY_LEFT,
                    leftColumnCount = columnCount(sheet),
                    rightColumnCount = 0,
                ),
            )
        }
        for (name in rightNames) {
            checkCanceled(cancellation)
            if (leftNames.contains(name)) continue
            val sheet = right.getSheet(name)
            sheets.add(
                SheetDiff(
                    name = name,
                    change = SheetChange.ONLY_RIGHT,
                    leftColumnCount = 0,
                    rightColumnCount = columnCount(sheet),
                ),
            )
        }

        // Sheets that exist in both files. Only the ones that differ are kept.
        for (name in leftNames) {
            checkCanceled(cancellation)
            if (!rightNames.contains(name)) continue
            val sheetDiff = diffSheet(left.getSheet(name), right.getSheet(name), cancellation, budget)
                ?: continue
            sheets.add(sheetDiff)
        }

        return DiffResult(leftPath, rightPath, sheets)
    }

    /**
     * Compares two sheets. Returns null when they are identical: a sheet with
     * no difference is not part of the result.
     */
    private fun diffSheet(
        left: Sheet,
        right: Sheet,
        cancellation: Cancellation,
        budget: CellBudget,
    ): SheetDiff? {
        val leftRows = meaningfulRows(left, budget)
        val rightRows = meaningfulRows(right, budget)
        val leftKeys = leftRows.map { rowSignature(it) }
        val rightKeys = rightRows.map { rowSignature(it) }

        val rowDiffs = ArrayList<RowDiff>()
        var leftIndex = 0
        var rightIndex = 0
        for ((l, r) in lcsPairs(leftKeys, rightKeys)) {
            checkCanceled(cancellation)
            // Rows between two matched rows: paired by position.
            emitGap(left, leftRows, leftIndex, l, right, rightRows, rightIndex, r, rowDiffs, cancellation, budget)
            val cells = compareRows(left, leftRows[l], right, rightRows[r], cancellation, budget)
            if (cells.isNotEmpty()) {
                rowDiffs.add(RowDiff(left.sheetName, leftRows[l].rowNum + 1, RowChange.MODIFIED, cells))
            }
            leftIndex = l + 1
            rightIndex = r + 1
        }
        emitGap(left, leftRows, leftIndex, leftRows.size, right, rightRows, rightIndex, rightRows.size, rowDiffs, cancellation, budget)

        if (rowDiffs.isEmpty()) return null

        return SheetDiff(
            name = left.sheetName,
            rows = rowDiffs,
            leftColumnCount = columnCount(left),
            rightColumnCount = columnCount(right),
        )
    }

    /**
     * Handles the rows that sit between two matched rows: they are paired by
     * position, so a modified row stays a modified row instead of becoming an
     * added row plus a removed row.
     */
    private fun emitGap(
        left: Sheet,
        leftRows: List<Row>,
        leftFrom: Int,
        leftTo: Int,
        right: Sheet,
        rightRows: List<Row>,
        rightFrom: Int,
        rightTo: Int,
        out: MutableList<RowDiff>,
        cancellation: Cancellation,
        budget: CellBudget,
    ) {
        val leftCount = leftTo - leftFrom
        val rightCount = rightTo - rightFrom
        val paired = minOf(leftCount, rightCount)
        for (k in 0 until paired) {
            checkCanceled(cancellation)
            val leftRow = leftRows[leftFrom + k]
            val rightRow = rightRows[rightFrom + k]
            val cells = compareRows(left, leftRow, right, rightRow, cancellation, budget)
            if (cells.isNotEmpty()) {
                out.add(RowDiff(left.sheetName, leftRow.rowNum + 1, RowChange.MODIFIED, cells))
            }
        }
        for (k in paired until leftCount) {
            val leftRow = leftRows[leftFrom + k]
            out.add(
                RowDiff(
                    sheet = left.sheetName,
                    row = leftRow.rowNum + 1,
                    change = RowChange.REMOVED,
                    cells = describeRow(left, leftRow, CellChange.REMOVED, budget),
                ),
            )
        }
        for (k in paired until rightCount) {
            val rightRow = rightRows[rightFrom + k]
            out.add(
                RowDiff(
                    sheet = right.sheetName,
                    row = rightRow.rowNum + 1,
                    change = RowChange.ADDED,
                    cells = describeRow(right, rightRow, CellChange.ADDED, budget),
                ),
            )
        }
    }

    /**
     * Compares two rows cell by cell, column by column.
     */
    private fun compareRows(
        left: Sheet,
        leftRow: Row,
        right: Sheet,
        rightRow: Row,
        cancellation: Cancellation,
        budget: CellBudget,
    ): List<CellDiff> {
        val out = ArrayList<CellDiff>()
        val columnCount = maxOf(leftRow.lastCellNum, rightRow.lastCellNum).coerceAtLeast(0)
        budget.spend(columnCount.toLong())
        for (column in 0 until columnCount) {
            checkCanceled(cancellation)
            val leftCell = leftRow.getCell(column)
            val rightCell = rightRow.getCell(column)
            val leftBlank = reader.isBlank(leftCell)
            val rightBlank = reader.isBlank(rightCell)
            if (leftBlank && rightBlank) continue

            val changes = LinkedHashSet<CellChange>()
            if (leftBlank) {
                changes.add(CellChange.ADDED)
            } else if (rightBlank) {
                changes.add(CellChange.REMOVED)
            } else {
                if (reader.displayValue(leftCell) != reader.displayValue(rightCell)) {
                    changes.add(CellChange.VALUE)
                }
                if (reader.formula(leftCell) != reader.formula(rightCell)) {
                    changes.add(CellChange.FORMULA)
                }
                if (reader.typeName(leftCell) != reader.typeName(rightCell)) {
                    changes.add(CellChange.TYPE)
                }
            }
            if (changes.isEmpty()) continue

            out.add(
                CellDiff(
                    ref = CellRef(left.sheetName, leftRow.rowNum + 1, column + 1),
                    changes = changes,
                    leftValue = if (leftBlank) null else reader.displayValue(leftCell),
                    rightValue = if (rightBlank) null else reader.displayValue(rightCell),
                    leftFormula = reader.formula(leftCell),
                    rightFormula = reader.formula(rightCell),
                    leftType = reader.typeName(leftCell),
                    rightType = reader.typeName(rightCell),
                ),
            )
        }
        return out
    }

    /**
     * Describes the content of a row that appeared or disappeared.
     */
    private fun describeRow(
        sheet: Sheet,
        row: Row,
        change: CellChange,
        budget: CellBudget,
    ): List<CellDiff> {
        val out = ArrayList<CellDiff>()
        budget.spend(row.lastCellNum.toLong())
        for (column in 0 until row.lastCellNum) {
            val cell = row.getCell(column)
            if (reader.isBlank(cell)) continue
            val value = reader.displayValue(cell)
            out.add(
                CellDiff(
                    ref = CellRef(sheet.sheetName, row.rowNum + 1, column + 1),
                    changes = setOf(change),
                    leftValue = if (change == CellChange.REMOVED) value else null,
                    rightValue = if (change == CellChange.ADDED) value else null,
                    leftFormula = if (change == CellChange.REMOVED) reader.formula(cell) else null,
                    rightFormula = if (change == CellChange.ADDED) reader.formula(cell) else null,
                    leftType = if (change == CellChange.REMOVED) reader.typeName(cell) else null,
                    rightType = if (change == CellChange.ADDED) reader.typeName(cell) else null,
                ),
            )
        }
        return out
    }

    private fun sheetNames(book: Workbook): List<String> =
        (0 until book.numberOfSheets).map { book.getSheetName(it) }

    private fun columnCount(sheet: Sheet): Int {
        var count = 0
        for (index in 0 until sheet.lastRowNum + 1) {
            val row = sheet.getRow(index) ?: continue
            if (row.lastCellNum.toInt() > count) count = row.lastCellNum.toInt()
        }
        return count
    }

    /** The rows that hold something: empty rows are ignored. */
    private fun meaningfulRows(sheet: Sheet, budget: CellBudget): List<Row> {
        val out = ArrayList<Row>()
        for (index in 0 until sheet.lastRowNum + 1) {
            val row = sheet.getRow(index) ?: continue
            budget.spend(row.lastCellNum.toLong())
            for (column in 0 until row.lastCellNum) {
                if (!reader.isBlank(row.getCell(column))) {
                    out.add(row)
                    break
                }
            }
        }
        return out
    }

    /** What the row holds, used to recognise two rows that are the same. */
    private fun rowSignature(row: Row): String {
        val sb = StringBuilder()
        for (column in 0 until row.lastCellNum) {
            val cell = row.getCell(column)
            if (reader.isBlank(cell)) continue
            sb.append(reader.displayValue(cell)).append(SEPARATOR)
        }
        return sb.toString()
    }

    /**
     * Longest common subsequence of two lists of signatures.
     *
     * Returns the matched positions, in order. Above a few million cells the
     * comparison falls back to a simple index alignment so that a very large
     * sheet cannot exhaust the memory.
     */
    private fun lcsPairs(left: List<String>, right: List<String>): List<Pair<Int, Int>> {
        val n = left.size
        val m = right.size
        if (n == 0 || m == 0) return emptyList()
        if (n.toLong() * m.toLong() > MAX_ALIGNMENT_CELLS) {
            return (0 until minOf(n, m)).map { it to it }
        }
        val width = m + 1
        val table = IntArray((n + 1) * width)
        for (i in n - 1 downTo 0) {
            val key = left[i]
            for (j in m - 1 downTo 0) {
                table[i * width + j] = if (key == right[j]) {
                    table[(i + 1) * width + (j + 1)] + 1
                } else {
                    maxOf(table[(i + 1) * width + j], table[i * width + (j + 1)])
                }
            }
        }
        val pairs = ArrayList<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (left[i] == right[j]) {
                pairs.add(i to j)
                i++
                j++
            } else if (table[(i + 1) * width + j] >= table[i * width + (j + 1)]) {
                i++
            } else {
                j++
            }
        }
        return pairs
    }

    /**
     * Opens a workbook without ever writing to it.
     *
     * The size of the file is checked **before** the workbook is opened: a
     * file that is too big is refused without being loaded in memory.
     */
    private fun openWorkbook(path: Path): Workbook {
        val file = path.toFile()
        if (!Files.exists(path)) {
            throw XlsxDiffException("File not found: $path")
        }
        if (!file.isFile) {
            throw XlsxDiffException("\"${file.name}\" is not a file.")
        }
        if (file.length() == 0L) {
            throw XlsxDiffException("\"${file.name}\" is empty: it cannot be compared.")
        }
        checkSize(file)
        return try {
            // readOnly = true : the original file is never modified.
            WorkbookFactory.create(file, null as String?, true)
        } catch (e: EncryptedDocumentException) {
            throw XlsxDiffException(
                "\"${file.name}\" is protected by a password: unlock it before comparing it.",
                e,
            )
        } catch (e: NotOfficeXmlFileException) {
            throw XlsxDiffException("\"${file.name}\" is not a valid Excel workbook (.xlsx).", e)
        } catch (e: InvalidFormatException) {
            throw XlsxDiffException("\"${file.name}\" is not a valid Excel workbook (.xlsx).", e)
        } catch (e: IOException) {
            throw XlsxDiffException("\"${file.name}\" is unreadable or damaged: it cannot be compared.", e)
        } catch (e: RuntimeException) {
            throw XlsxDiffException("\"${file.name}\" is unreadable or damaged: it cannot be compared.", e)
        }
    }

    /**
     * Refuses a file that is too big, before it is opened.
     *
     * Two sizes are measured: the size of the file on the disk, and the total
     * size of the entries of the ZIP once decompressed (a `.xlsx` is a ZIP of
     * XML files: 10 MB on the disk can hold 1 GB once decompressed, and the
     * spreadsheet library loads all of it in memory).
     */
    private fun checkSize(file: File) {
        val size = file.length()
        if (size > limits.maxFileBytes) {
            throw XlsxDiffException(
                "\"${file.name}\" is too large to be compared: ${humanSize(size)} on disk " +
                    "(the limit is ${humanSize(limits.maxFileBytes)}).",
            )
        }
        val uncompressed = uncompressedSize(file)
        if (uncompressed != null && uncompressed > limits.maxUncompressedBytes) {
            throw XlsxDiffException(
                "\"${file.name}\" is too large to be compared: ${humanSize(uncompressed)} once " +
                    "decompressed (the limit is ${humanSize(limits.maxUncompressedBytes)}).",
            )
        }
    }

    /**
     * The total size of the entries of the ZIP once decompressed, in bytes.
     *
     * Returns null when the file is not a ZIP archive: in that case the
     * spreadsheet library is the one that says what is wrong with the file.
     */
    private fun uncompressedSize(file: File): Long? = try {
        ZipFile(file).use { zip ->
            var total = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val size = entries.nextElement().size
                if (size > 0) total += size
            }
            total
        }
    } catch (e: Exception) {
        null
    }

    /** A size written the way a person writes it: "12.3 MB". */
    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f kB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0)
    }

    private fun closeQuietly(book: Workbook) {
        try {
            book.close()
        } catch (e: Exception) {
            // nothing useful to do here
        }
    }

    /**
     * Stops the comparison when the caller asked to.
     */
    private fun checkCanceled(cancellation: Cancellation) {
        if (cancellation.isCanceled()) throw XlsxDiffCancelledException()
    }

    /**
     * Counts the cells that are examined and refuses to go past the limit.
     *
     * A spreadsheet with millions of cells would take a long time to compare
     * and would keep the IDE busy: it is better to say so than to hang.
     */
    private class CellBudget(private val maxCells: Long) {
        private var examined = 0L

        fun spend(count: Long) {
            examined += count
            if (examined > maxCells) {
                throw XlsxDiffException(
                    "These spreadsheets have too many cells to be compared (more than " +
                        "$maxCells). Compare smaller files, or split them.",
                )
            }
        }
    }

    private companion object {
        /** Above this number of cells the row alignment falls back to index order. */
        const val MAX_ALIGNMENT_CELLS = 4_000_000L

        /** Separates the values of a row signature; it never appears in a cell. */
        const val SEPARATOR = '\u0001'
    }
}
