# XLSX Diff

Compare two `.xlsx` spreadsheets **inside your JetBrains IDE**, cell by cell, and export the
result.

> Select **two** `.xlsx` files in the Project view → right-click → **Compare XLSX Files** →
> the differences open in a **read-only editor tab**.

## What it does

| | |
|---|---|
| **Compares** | two `.xlsx` files, cell by cell: sheet, cell address, row, left value, right value, formula |
| **Detects** | modified cells, added rows, removed rows |
| **Shows** | the differences as a readable text report, in a **read-only** editor tab |
| **Exports** | the diff to **CSV** (UTF-8 with BOM, `,` separator, formula-injection safe) |
| **Never writes** | your files are opened **read-only** — the plugin never modifies a spreadsheet |
| **Cancellable** | the background comparison can be cancelled; nothing is displayed if it is |

There is **no** built-in way to diff two `.xlsx` files in IntelliJ IDEA or its siblings: the IDE
diffs VCS text files, not spreadsheet cells
([JetBrains YouTrack IJPL-158150](https://youtrack.jetbrains.com/issue/IJPL-158150)).
This plugin fills that gap.

## Requirements

* Any JetBrains IDE with the platform plugin API (IntelliJ IDEA, PyCharm, WebStorm, …) —
  builds 243 and later.
* Java 17 or later at runtime (the IDE provides it).
* The plugin bundles Apache POI, so no extra dependency is needed on your side.

## Usage

1. In the **Project** view, select **exactly two** `.xlsx` files (Ctrl-click the second one).
2. Right-click and choose **Compare XLSX Files**. The action only shows up when two
   spreadsheets are selected — with one or with three, there is nothing to compare.
3. The comparison runs in the background (the progress bar can be cancelled) and the report
   opens in a **read-only editor tab**: every difference is listed with its sheet, cell
   address, row, left value, right value and formula.
4. To save it, use **Export CSV…** (in the menu of the report tab, or in the Project view
   menu once a comparison has been made).

## The CSV export

The exported CSV is meant to be opened in Excel, LibreOffice or any script:

| | |
|---|---|
| **Encoding** | UTF-8, **with** a byte order mark (`BOM`) — without it, Excel on Windows reads the file with the local code page and every accent is broken |
| **Separator** | `,` (the CSV standard) — Excel, LibreOffice, Google Sheets and every script read it as is |
| **Columns** | `type,sheet,cell,row,column,left_value,right_value,left_formula,right_formula` |
| **Quoting** | a value that holds a comma, a quote or a line break is quoted, so the columns are always cut in the right place |

### Excel in another language (Swiss, French, German)

The separator is `,` (the CSV standard). Excel in Swiss, French or German
expects `;` when a file is opened by double-click: it then puts the whole line
in a single column. In that case open the file with **Data ▸ From Text/CSV**
and choose `,` as the separator — the exported file is correct, only the
default of that Excel is not. (A `sep=,` line is deliberately **not** added:
Excel then ignores the byte order mark and the accents are broken.)

### Formula injection (why some values start with an apostrophe)

A cell whose text starts with `=`, `+`, `-`, `@` or a tabulation is read as a **formula** by Excel
when the CSV is opened: a malicious spreadsheet could then make something happen on the computer of
the person who reads the report.

**A cell whose text starts with = + - @ or a tabulation is prefixed with an apostrophe, for Excel
safety.** That is the only change the export makes to your data: a plain text and a positive
number (`42`, `3.5`) are exported exactly as they are, so the report stays faithful to the
spreadsheets. A negative number (`-42`) is prefixed too, because Excel would otherwise read
`-2+3` as a formula.

The rule is applied to **every** cell of the report. It matters for the added and removed rows,
whose cells are written in a single column: each of them is neutralised on its own, so a formula
in the second column of such a row is neutralised too.

## Limits

A `.xlsx` file is a ZIP archive of XML files: 20 MB on the disk can hold 2 GB once decompressed,
and the spreadsheet library loads the whole workbook in memory. To keep the IDE responsive, the
plugin **refuses** a comparison and shows a clear message (not a crash) when:

| Limit | Value |
|---|---|
| Size of a file on the disk | **20 MB** |
| Total size of the ZIP entries once decompressed | **50 MB** |
| Number of cells compared | **2 000 000** |

## Building from the sources

```bash
# JDK 17+, Gradle 8.10.2 (8.5+ is enough for the IntelliJ Platform Gradle Plugin 2.x)
export JAVA_HOME=/path/to/jdk-17
./gradlew buildPlugin        # -> build/distributions/xlsx-diff-plugin-<version>.zip
./gradlew :core:test         # unit tests (JUnit 5)
```

Build recipe used for the released artifact:

| Piece | Version |
|---|---|
| IntelliJ Platform Gradle Plugin | 2.7.2 |
| Kotlin | 1.9.25 |
| Java (compile target) | 17 |
| IntelliJ IDEA Community | 2024.3 |
| Gradle | 8.10.2 |

Project layout:

```
xlsx-diff-plugin/
├── core/          # engine + exporter (pure Kotlin, no IDE dependency -> unit-testable)
│   └── src/       # XlsxDiffEngine, Cells, DiffExporter, DiffRows, Cancellation
└── src/           # IDE layer: action, session, CSV export action, notifications, self-test
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).

The plugin is **free** and open source. It collects nothing and never modifies your files.

## Links

* JetBrains Marketplace: <https://plugins.jetbrains.com/plugin/> (search for **XLSX Diff**)
* Vendor: **Tgg Labs** — <https://plugins.jetbrains.com/vendor/tgg-labs>
* Issues and feedback: the Marketplace page of the plugin.
* User support: `tgg.apps.support@gmail.com` — this address is **only** in the documentation.
  It is never written in the plugin descriptor, and it is not the account that publishes.
