# Git Branch Badge

A free JetBrains plugin with **one** function: it shows the **current Git branch**
right next to the project root in the **Project** view.

```
gitdemo   feature/branche-demo
▲ project root   ▲ branch name (grey text)
```

* YouTrack: [IJPL-73102 — "Show current Git branch in Project View"](https://youtrack.jetbrains.com/issue/IJPL-73102) — 281 votes, open since 2011.
* Nothing else: no menu, no side panel, no history, no network access, no account.

---

## What it does exactly

| | |
|---|---|
| Where | The project root node in the left-hand **Project** panel (`ProjectViewNodeDecorator`, extension point `com.intellij.projectViewNodeDecorator`) |
| What | The name of the branch `HEAD` points at, in grey next to the project name |
| How | Reads the local `.git/HEAD` file — the exact file Git itself reads. No network, no Git binary, no process spawned |
| Refresh | The label **updates by itself** when the branch changes — from the IDE *or* from a terminal. It is re-read every 2 s and the panel is redrawn only when the name actually changes |
| Tooltip | The project path is preserved in the node tooltip, plus the line `Git branch: <name>` |
| Detached HEAD | No branch is shown (there is none) — the node keeps its normal appearance |

The branch is resolved **purely in Kotlin**, with no IntelliJ API in the core
(`GitHead.kt`), which is what makes it unit-testable outside the IDE.

### How the refresh works (and why it is not just the Git plugin)

The `projectViewNodeDecorator` extension point is only called when the tree is
(re)built, so something has to ask for that rebuild. Three mechanisms, all
present in this plugin:

| Mechanism | Triggers on | Depends on |
|---|---|---|
| `GitBranchChangeListener` (optional, topic `git4idea.repo.GitRepositoryChangeListener`) | a branch change made **inside** the IDE — instant | the IDE's Git plugin |
| `BranchRefreshService` (self-contained) | **any** branch change — the file `.git/HEAD` is re-read every 2 s | nothing |

The self-contained watcher exists because the Git plugin's own notification
travels through the IDE's VFS watcher, which does not deliver changes in every
environment (containers, virtual/remote file systems, WSL, Docker). A user who
runs `git checkout` in a terminal must still see the right branch. The watcher
reads the same file Git reads, so it needs no network, no Git binary and no
plugin, and it works with the IDE's Git plugin disabled.

## Compatibility

| | |
|---|---|
| IDEs | IntelliJ IDEA, PyCharm, WebStorm, GoLand, CLion, RubyMine, PhpStorm, Rider — any JetBrains IDE |
| Build range | `243` (2024.3) → `263.*` (2026.3) |
| Compiled against | PyCharm Professional 2024.3, Java 17 bytecode, Kotlin 1.9.25 |
| Git plugin | **Optional**. The badge works even with the IDE's Git plugin disabled; the Git plugin is only used to *refresh* the label when the branch changes. |

## Repository layout

```
BrancheProjectView/
├── build.gradle.kts                 # IntelliJ Platform Gradle Plugin 2.7.2
├── gradle.properties                # id / name / version / vendor / build range
├── settings.gradle.kts
├── src/main/kotlin/com/tgglabs/branchbadge/
│   ├── GitHead.kt                   # pure Kotlin: reads .git/HEAD  (no IntelliJ import)
│   ├── BranchWatchState.kt          # pure Kotlin: detects a branch change (no IntelliJ import)
│   ├── BranchProjectViewDecorator.kt# the decorator (shows the branch on the project root)
│   ├── BranchRefreshService.kt      # re-reads .git/HEAD every 2 s, redraws the panel on change
│   └── GitBranchChangeListener.kt   # optional: instant refresh on a change made inside the IDE
├── src/main/resources/META-INF/
│   ├── plugin.xml                   # id, name, description, EP + service declarations
│   ├── git4idea.xml                 # optional listener declaration
│   ├── pluginIcon.svg               # 40x40 icon (light)
│   └── pluginIcon_dark.svg          # 40x40 icon (dark)
├── src/test/kotlin/com/tgglabs/branchbadge/
│   ├── GitHeadTest.kt               # 8 JUnit 5 tests (branch reading)
│   └── BranchWatchStateTest.kt      # 7 JUnit 5 tests (change detection)
└── build/distributions/git-branch-badge-0.0.1.zip          # ready-to-review artifact
```

## Build

```bash
cd /work/produits/BrancheProjectView
./gradlew buildPlugin
# -> build/distributions/git-branch-badge-0.0.1.zip
```

In this container the build must stay inside the 1.5 GB memory limit, so use
the bounded wrapper:

```bash
/work/scripts/build_branche_cycle62.sh
# Gradle --no-daemon --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process
# + init script /work/scripts/memoire_bornee.init.gradle (caps the forked IDE JVM at 256 MB)
```

Expected output: `BUILD SUCCESSFUL`.

## Tests

The IDE `test` task needs the full IntelliJ platform test framework, which does
not fit in the container's memory. The core is therefore tested directly with
the JUnit 5 console launcher — the same test file, the same assertions, no IDE:

```bash
# branch reading (8 tests)
/work/scripts/tests_branche_cycle62.sh \
    src/main/kotlin/com/tgglabs/branchbadge/GitHead.kt VERT_reel

# change detection (7 tests)
/work/scripts/tests_branche_cycle63.sh \
    src/main/kotlin/com/tgglabs/branchbadge/BranchWatchState.kt VERT_reel
```

Covered cases — branch reading: local branch read, slashes kept in the name,
detached HEAD, no repository, repository found in a parent directory, `.git`
**file** (worktree), remote ref is not a local branch, empty branch name rejected.

Covered cases — change detection: first observation does not refresh, an
unchanged branch does not refresh, a changed branch refreshes, a branch that
disappears (detached HEAD) refreshes, a branch that comes back refreshes,
consecutive changes are all detected, two open projects stay independent.

**Counter-proof (RED → GREEN).** Each suite was first run against a deliberately
naive implementation; the same test code then runs against the real one:

| Suite | Naive implementation | Real implementation |
|---|---|---|
| Branch reading (cycle 62) | 6 of 8 failed — `tests_ROUGE_naif.log` | 8/8 — `tests_VERT_reel.log` |
| Change detection (cycle 63) | 5 of 7 failed — `tests_ROUGE_naif.log` | 7/7 — `tests_VERT_reel.log` |

The cycle-63 naive implementation reproduces exactly the behaviour shipped at
cycle 62: the panel is built once and the branch label is **never** updated
afterwards. No green suite without a counter-proof.

## Proof in a real IDE

PyCharm **2026.2** was launched headlessly on a real Git repository
(`feature/branche-demo`), once without the plugin and once with it:

| | Project root node |
|---|---|
| Native IDE | `gitdemo  /work/research/cycle62/gitdemo` (path, no branch) |
| With the plugin | `gitdemo  feature/branche-demo` (branch) |

⚠️ Les captures d'écran de ce cycle ont été **supprimées le 27/10/2026** au nettoyage du
disque (`/work/research/` est vide). Les **deux captures livrées** dans `media/` (1400 × 1000
et 1208 × 901) sont, elles, toujours là — ce sont celles qui partent sur la fiche.

**Hot refresh (verified again on 27/10/2026, cycle 69).** The IDE was left
running, the branch was switched **from outside the IDE**
(`git checkout -b nouvelle-branche` in a shell, exactly what a user does in a
terminal), and the panel was captured again. The label changed by itself:

| | Project root node |
|---|---|
| Before the external checkout | `gitdemo  feature/branche-demo` |
| After the external checkout | `gitdemo  nouvelle-branche` |

The two captures are compared **pixel by pixel** (`compare -metric AE` →
139 111 differing pixels), not byte by byte — the byte comparison used in
cycle 63 gave a false positive (the two PNGs differed only by a header chunk).

The IDE log records the whole chain:

```
Loaded custom plugins: Git Branch Badge (0.0.1)
Git Branch Badge : surveillance de la branche demarree pour gitdemo
Git Branch Badge : branche changee -> nouvelle-branche
```

**Preuve conservée après le nettoyage du 27/10/2026 (cycle 73) :**
`/work/preuves/git-branch-badge/ide-verif-2026-10-27.log` (le plugin est **chargé** dans
PyCharm 2024.3 et le **listener Git est enregistré** avec le bon topic),
`/work/preuves/git-branch-badge/idea-2026-10-27.log` (le log de l'IDE),
`/work/preuves/git-branch-badge/ide-controle-2026-10-27.png` (capture de contrôle).
Les captures d'avant/après du cycle 69 (cycle-62 et cycle-69) ont été **supprimées avec
`/work/research/`** : elles ne sont plus disponibles sur le disque.

## Screenshots

| File | Size | What it shows |
|---|---|---|
| `media/01-panneau-project-branche.png` | 1400 × 1000 | the PyCharm 2026.2 window, Project panel with the branch badge |
| `media/02-libelle-branche-zoom.png` | 1208 × 901 | zoom on the project root node and its branch label |

## Licence

MIT — see [LICENSE](LICENSE). Copyright © 2026 Tgg Labs.

The plugin is also published on the JetBrains Marketplace:
<https://plugins.jetbrains.com/plugin/35551-git-branch-badge>.
