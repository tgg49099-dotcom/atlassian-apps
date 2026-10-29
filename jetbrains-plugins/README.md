# jetbrains-plugins

Public source for the JetBrains IDE plugins published by **Tgg Labs** on the
[JetBrains Marketplace](https://plugins.jetbrains.com/).

| Plugin | Marketplace listing | Source | Current version |
|---|---|---|---|
| **ML Experiment Cockpit** | [plugins.jetbrains.com/plugin/35416-ml-experiment-cockpit](https://plugins.jetbrains.com/plugin/35416-ml-experiment-cockpit) | [`ml-experiment-cockpit/`](ml-experiment-cockpit/) | 0.0.17 |
| **Git Branch Badge** | [plugins.jetbrains.com/plugin/35551-git-branch-badge](https://plugins.jetbrains.com/plugin/35551-git-branch-badge) | [`git-branch-badge/`](git-branch-badge/) | 0.0.2 |

## Layout

```
jetbrains-plugins/
├── LICENSE                     Apache License 2.0 (full text, repo files)
├── NOTICE
├── README.md                   this file
├── ml-experiment-cockpit/      one folder per plugin (Apache-2.0)
└── git-branch-badge/           one folder per plugin (MIT)
    ├── README.md               documentation (the listing's "doc" link)
    ├── PRIVACY.md              privacy policy (the listing's "privacy policy" link)
    ├── SUPPORT.md              support & FAQ
    ├── SECURITY.md             how to report a vulnerability
    ├── CHANGELOG.md
    ├── LICENSE
    ├── media/                  listing screenshot
    ├── build.gradle.kts, gradle.properties, settings.gradle, gradle/
    ├── scripts/                pre-flight + upload helpers
    └── src/main/               Kotlin sources and META-INF resources
```

Each plugin lives in its own folder and carries its own policy documents, so
that the links published on the Marketplace listing always point at the pages
that describe **that** plugin.

## Building a plugin

Each plugin folder is a standalone Gradle project:

```bash
cd ml-experiment-cockpit
export JAVA_HOME=/path/to/jdk-21
./gradlew buildPlugin verifyPlugin
./scripts/prevol.sh        # pre-flight on the BUILT jar — never publish without it
```

## Reporting a problem

For each plugin, use the channel described in its own `SUPPORT.md` — or, for a
security problem, its `SECURITY.md`. There is no single issue tracker for the
whole repository: an issue in `ml-experiment-cockpit/` is about ML Experiment
Cockpit only.

## License

Each plugin folder carries **its own** license, as declared on its Marketplace
listing:

| Folder | License | Text |
|---|---|---|
| `ml-experiment-cockpit/` | Apache License 2.0 | [LICENSE](ml-experiment-cockpit/LICENSE) — also [NOTICE](ml-experiment-cockpit/NOTICE) |
| `git-branch-badge/` | MIT License | [LICENSE](git-branch-badge/LICENSE) |

The repository's own files (this README, [NOTICE](NOTICE)) are under the
Apache License 2.0 — see [LICENSE](LICENSE).
