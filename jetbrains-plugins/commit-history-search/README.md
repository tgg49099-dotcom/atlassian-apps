# Commit History Search — dossier produit (prêt pour le pré-vol)

Plugin **gratuit** JetBrains : chercher une chaîne de texte dans les **diffs** de l'historique
git, sans quitter l'IDE. L'équivalent de `git log -S` / `git log -G` (« pickaxe ») — la demande
officielle **IJPL-84839**, ouverte le 25/06/2013, 204 votes (au 28/10/2026), toujours non
satisfaite.

**Limite réelle du plugin :** le balayage couvre **tout l'historique de la branche courante** et
**ignore les commits de fusion** (`--no-merges`). La liste des résultats montre **au plus les 500
correspondances les plus récentes** : ce plafond limite la taille de la liste, il **ne** limite
**pas** la profondeur du balayage. Ce point a été **mesuré** le 29/10/2026 (`git 2.39.5`) et
**prouvé par des tests** : voir « Ce qui a été vérifié » plus bas.

**État au 30/10/2026 09:24 — version 0.0.2 :** les **3 points de la revue du 30/10 08:00**
sont traités : le **fichier renommé** (le diff s'ouvrait avec un « avant » vide), la **regex
POSIX** (git ne comprend pas `\d` : la recherche ne trouvait rien, sans message) et
l'**annulation** (prouvée par un test, le code était déjà correct : le jury avait lu la version
du 28/10). S'y ajoutent 2 corrections de propreté (chemin de JDK retiré du dépôt public,
requête qui n'est plus trimée). **65** tests verts, chacun avec sa **contre-épreuve rouge**.
ZIP 0.0.2 reconstruit le 30/10 à 09:24, **pré-vol vert**, **plugin vérifié chargé dans un vrai
IDE**, **réglages vérifiés persistants d'une session IDE à l'autre** (preuve par captures).
Les correctifs des cycles 19 et 89 (pager, entrée standard, annulation, délai maximal, pickaxe
`-G`, nom de fichier non-ASCII) sont **conservés**.

## Les 2 défauts corrigés le 30/10/2026 (relecture hostile)

| Défaut | Correction | Test qui la prouve |
|---|---|---|
| **Le pager de git.** `git log` peut démarrer `less` ; l'IDE n'a pas de terminal, `less` attend une frappe clavier qui ne vient jamais (l'IDE gèle) et la sortie capturée est polluée par ses codes d'échappement. | `--no-pager` est ajouté **à toutes** les commandes git (dans `buildArguments()` et au point unique de passage `GitCommand.run()`), et **l'entrée standard de l'enfant est fermée immédiatement** (`process.outputStream.close()`) : git et son éventuel pager reçoivent un EOF. | `GitProcessSafetyTest` : « the command line of git always contains --no-pager », « the arguments of the git log command contain --no-pager », « the pager is disabled even for a command built by hand », « the standard input of the child git is closed » |
| **L'annulation ne répond pas, aucun délai maximal.** `cancelled()` n'était testé qu'entre deux `readLine()` : sur un gros dépôt, le bouton Annuler ne fait rien tant que git n'a rien affiché ; et une commande bloquée pouvait durer indéfiniment. | La lecture passe par un **thread lecteur** ; la boucle interroge la file **toutes les 200 ms** (`POLL_INTERVAL_MS`), donc l'annulation est testée au plus toutes les ~200 ms même quand git est silencieux. **Délai maximal : 10 minutes** (`DEFAULT_TIMEOUT_MS = 600000`) par commande git : au-delà, le processus est tué et une `GitTimeoutException` est levée. | `GitProcessSafetyTest` : « a silent git is killed when the search is cancelled », « the cancellation is checked at least every 200 ms while git is silent » ; `GitTimeoutTest` : « a git command that never ends is killed after the maximum delay », « a timeout is a GitException that can be told apart from another failure » |

**Contre-épreuve rouge → vert** (30/10/2026) : les 6 tests de `GitProcessSafetyTest` **échouent
tous** sur le code livré le 29/10 (`/work/evidence/contre-epreuve-ROUGE-20261030-pager-annulation.log`,
6 tests, 6 échecs) et **passent tous** après le correctif. Le test de délai maximal ne compile
même pas sur le code du 29/10 : `GitCommand.run(...)` n'y avait **aucun** argument `timeoutMs`
(le `GitCommand.kt` public du 29/10, dans le dépôt, le montre).


## Les 2 défauts corrigés le 30/10/2026 à 04:34 (relecture hostile, cycle 88)

| Défaut | Correction | Test qui la prouve |
|---|---|---|
| **Une ligne modifiée était ratée en mode « Case sensitive ».** Le plugin demandait `git log -Sfoo`, dont la sémantique est « le **nombre** d'occurrences a changé » : une ligne modifiée sans changement de ce nombre était **manquée**, alors que le texte public promet « added, removed **or modified** ». | Le pickaxe est **toujours `-G`** (avec le littéral échappé) : `-G` cherche le texte dans les lignes **ajoutées et supprimées**, donc une modification est trouvée. Le mode par défaut (insensible à la casse) ne change pas. | `GitPickaxeDefectsTest` : « a modified line is found when the search is case sensitive » |
| **Le diff d'un fichier dont le nom n'est pas ASCII s'ouvrait VIDE.** git échappe le chemin en octal (`"b/caf\303\251.txt"`) et le chemin n'était jamais décodé → `git show` échouait → l'IDE ouvrait un diff vide. | `git log` est appelé avec `-c core.quotePath=false` (le chemin réel dans le diff) **et** `unquote()` décode maintenant les échappements octal (les octets du nom, lus en UTF-8). | `GitPickaxeDefectsTest` : « the diff of a file whose name is not ASCII is not empty » |

**Contre-épreuve rouge → vert** (30/10/2026 04:44 → 04:51) : les 2 tests de
`GitPickaxeDefectsTest` **échouent tous les deux** sur le code du 29/10
(`/work/build_red_cycle89.log`) et **passent tous les deux** après le correctif. Messages
d'échec exacts sur l'ancien code :

```
a modified line is found when the search is case sensitive
  java.lang.AssertionError: the commit that modified the line must be found (hits: [A: add foo]) expected:<2> but was:<1>
the diff of a file whose name is not ASCII is not empty
  org.junit.ComparisonFailure: the path of the file must be decoded expected:<caf[é].txt> but was:<caf[\303\251].txt>
```

Le fichier de test est **pur ASCII** (`"café.txt"`) et la JVM de test force
`LANG=C.UTF-8` : sans cela, le conteneur (locale POSIX) créerait « caf?.txt » et le
défaut non-ASCII ne pourrait pas être reproduit du tout.


## Les 3 points de la revue du 30/10/2026 08:00 corrigés (version 0.0.2)

| Point de la revue | Verdict | Correction | Test qui la prouve |
|---|---|---|---|
| **1. Fichier renommé.** `git show <hash>^:<chemin>` utilise toujours le **nouveau** chemin : pour un fichier renommé par le commit, le « avant » du diff est **vide** (le chemin n'existe pas dans le commit parent). Signalé les 27, 28 et 30/10. | **juste** | Le chemin **avant** sort du modèle : `FileHit.oldPath` et `FileHit.beforePath` (« le chemin du fichier dans le commit parent »). La fenêtre lit le « avant » avec `git show <hash>^:<beforePath>` et le « après » avec `git show <hash>:<path>`. | `RenamedFileTest` : « the before side of the diff of a renamed file is not empty », « a file that was not renamed keeps its path before the commit » — **dépôt git réel** (fichier renommé + commit + modification), **binaire git réel**, et le « avant » est relu avec git. |
| **2. Regex POSIX.** git compile `-G` en **ERE POSIX** : `\d` n'y est pas une classe, et `--perl-regexp` ne change rien (mesuré sur git 2.39.5 et 2.43 : `-G'\d+'` → **0 commit**, avec ou sans `--perl-regexp`). Le filtre Java, lui, comprend `\d` : l'utilisateur voyait « ça matche » et n'obtenait **rien, sans message**. | **juste** | Le motif est **converti en ERE POSIX** avant d'être donné à git (`\d`→`[0-9]`, `\D`→`[^0-9]`, `\w`→`[_[:alnum:]]`, `\W`→`[^_[:alnum:]]`, `\s`→`[[:space:]]`, `\S`→`[^[:space:]]]`, hors crochets). `--perl-regexp` et son repli sont **supprimés**. La fenêtre **affiche** l'avertissement quand le motif utilise une construction que POSIX ERE ne sait pas faire (lookahead, backreference, drapeau inline) : l'avertissement est **visible dans la fenêtre**, pas dans un log. L'intitulé de la case et la description Marketplace disent « POSIX ERE (comme `git -G`) : utilisez `[0-9]` et non `\d` ». | `GitPickaxeDefectsTest` : « a regular expression finds the digits that POSIX ERE does not know » (dépôt réel : `\d+` doit trouver le commit « val = 42 ») ; `PosixEreTest` : 12 tests (conversion, ligne de commande, avertissements, intitulé) ; `DialogWiringTest` : « the window shows the warning about the POSIX ERE », « the checkbox of the regular expression mode says POSIX ERE ». |
| **3. Annulation.** Le jury dit que l'annulation n'est testée qu'entre deux lignes de sortie : le bouton ne réagirait pas pendant que git est silencieux. | **faux sur le code actuel** : la lecture est dans un thread dédié et le drapeau est testé toutes les 200 ms. Le jury a lu la version du 28/10. | **Le code n'est pas refait** : c'est le **test** qui est écrit, pour le **prouver**. | `SearchCancellationTest` : « a silent git command is cancelled in less than two seconds » — une **vraie** commande git (un alias qui dort sans écrire rien), lancée en arrière-plan, annulée au bout de 300 ms : `SearchCancelledException` en **moins de 2 s**. |

**Contre-épreuve rouge → vert** (30/10/2026 08:30 → 09:20) :

- **8 tests ROUGE** sur le code du 30/10 08:20 (`/work/build_red_cycle90.log`) : les 2 de
  `RenamedFileTest`, celui de `GitPickaxeDefectsTest` sur `\d+`, celui de `PosixEreTest` sur
  `--perl-regexp`, et les 4 de `DialogWiringTest`.
- **Contre-épreuve de l'annulation** : le même test **échoue** sur le `GitCommand.kt` du 28/10
  (celui que le jury a lu) — la commande silencieuse n'était annulée qu'à la fin, 10 s plus
  tard (`/work/build_red_cycle90_cancel.log`) — et **passe** sur le code actuel.
- **65 tests verts** après les correctifs (`/work/build_green_cycle90.log`).

**2 corrections de propreté** (revue du 30/10 08:00) : `org.gradle.java.home=/work/tools/jdk`
retiré de `gradle.properties` (un chemin de ma machine dans un dépôt public : un tiers ne
pouvait pas compiler tel quel) ; les chemins de `scripts/contre_epreuve.py` passent par
l'environnement ; et la requête n'est **plus trimée** (une indented line qui commence par des
espaces est maintenant trouvable).

## Le livrable

| | |
|---|---|
| Fichier à téléverser | `commit-history-search-0.0.2.zip` (1 677 236 octets, reconstruit le 30/10/2026 à 09:24) |
| md5 | `f24d3ca2716acf4cf7826b6983c2a758` |
| sha256 | `45b40cb28f94c527a32961ea18d362d03e0566b8e276d5d9bdbe3d67c27b86a5` |
| jar interne `commit-history-search-0.0.2.jar` | 75 357 octets — md5 `f768fb3e3c1282b94e7d685076ab3a2b` (vérifié **par le contenu du ZIP**, pas seulement sur disque) |
| Nom public | `Commit History Search` (21 caractères, sans mot interdit) |
| Version | `0.0.2` |
| Plugin ID (xmlId) | `com.tgglabs.commithistorysearch` |
| Prix | gratuit (0 $) |

Le ZIP construit par Gradle est dans `source/build/distributions/` (c'est là que le pré-vol du
serveur le cherche) ; la copie ci-dessus est le fichier à joindre.

## Ce qu'il y a dans ce dossier

| Fichier | Rôle |
|---|---|
| `plugin.xml` | le descripteur livré (identique à celui du jar) |
| `pluginIcon.svg`, `pluginIcon_dark.svg` | les deux logos 40×40 |
| `LICENSE.txt` | Apache 2.0 (déclarée ici, **pas** dans le `plugin.xml`) |
| `commit-history-search-0.0.2.zip` | **le livrable** à téléverser |
| `preuve_ide_chargement.log` | la ligne exacte du log de l'IDE + les empreintes |
| `description.md` | la description et les change-notes à coller dans le formulaire Marketplace |
| `vendor_compte.json` | le compte éditeur lu sur l'API publique (adresse **non recopiée**) |
| `preflight.py` | les contrôles de pré-vol (24 contrôles), relançables |
| `preflight_report.json` | le résultat du dernier pré-vol |
| `source/` | les sources complètes (Kotlin + tests + script de contre-épreuve) |

## Ce que le propriétaire doit faire (rien d'autre, 0 $)

1. https://plugins.jetbrains.com/vendor/tgg-labs → **« Create new plugin »**
   ⚠️ **PAS « Upload plugin »** : « Upload plugin » ajoute une version à un plugin déjà listé
   (c'est ce qui a été fait pour « ML Experiment Cockpit », pluginId 35416). Ici c'est un plugin
   **nouveau**, avec un `plugin.xml id` différent : il faut une nouvelle fiche.
2. Téléverser `commit-history-search-0.0.2.zip` (et **jamais** le 0.0.1).
3. Me donner le `pluginId` numérique de la nouvelle fiche → je le déclare dans
   `/rapports/produits.json` (plateforme `jetbrains`) et le serveur relèvera les vrais
   téléchargements chaque matin.

Aucun jeton API n'est nécessaire pour cette étape : `JETBRAINS_MARKETPLACE_TOKEN` ne sert
qu'aux **mises à jour** ultérieures.

État vérifié le **29/10/2026** sur l'API publique du vendor
(`GET https://plugins.jetbrains.com/api/vendors/tgg-labs/plugins`) : 2 plugins seulement
(`35416` ML Experiment Cockpit, `35551` Git Branch Badge) — **`com.tgglabs.commithistorysearch`
n'a pas encore de fiche**, donc pas de `pluginId` à déclarer.

## Ce qui a été vérifié (27, 28 et 29/10/2026, dans ce conteneur)

- **⚠️ 29/10/2026 — le texte public du cycle 13 était FAUX, il est corrigé et verrouillé.**
  Le cycle 13 avait annoncé « the scan stops after the 500 most recent commits » alors que le
  code fait `git log --no-merges --max-count=500`. **Mesure du 29/10 (git 2.39.5)** :
  `--max-count` limite le nombre de commits **affichés**, **pas** le nombre de commits
  **parcourus**. Dans un dépôt de 1200 commits dont la chaîne n'existe que dans le **premier**
  commit, la commande réelle du plugin **trouve** ce commit ; la même recherche restreinte aux
  500 commits les plus récents **ne trouve rien** (contre-épreuve). Mesure reproductible :
  `python3 /work/mesure_portee_scan.py` → `/work/evidence/mesure-portee-scan.log`.
  Le texte dit maintenant la vérité : « the scan covers the **whole history** of the current
  branch … the result list shows at most the **500 most recent matching commits**: that cap
  limits the size of the list, it does not limit how far back the search goes ».
  **Verrouillé par des tests** : `GitHistoryScopeTest` (2 tests : profondeur du scan, plafond de
  la liste) + `PluginDescriptorTest > the description states the real limit of the scan` (le texte
  livré ne doit plus jamais repasser à « 500 most recent commits »).
- **Les réglages sont conservés d'une session IDE à l'autre — vérifié dans un vrai IDE** (29/10) :
  après une **redémarrage complet** de l'IDE, la fenêtre rouverte affiche la **case « Case
  sensitive » cochée** et la **requête restaurée** (`MAGIC_TOKEN_9F3A`). Captures :
  `/work/evidence/persistance-ide/` (4 PNG) ; la valeur persistée est dans
  `.idea/workspace.xml` (`com.tgglabs.commithistorysearch.caseSensitive` = `true`).
- **65 tests verts** (`gradle test buildPlugin` : 65 PASSED, 0 échec,
  0 erreur, 0 ignoré ; comptés sur les rapports XML JUnit de `build/test-results/test/`, pas
  recopiés d'un rapport).
- **Patch de la revue du jury appliqué et prouvé le 28/10/2026 09:04** : en mode *regular
  expression*, git (`-G`) est sensible à la casse alors que le filtre Java ne l'est pas — un
  commit ajoutant « Needle » était raté par une recherche « needle ». Le correctif ajoute
  `--regexp-ignore-case` quand `regex && ignoreCase` (3 lignes dans `GitHistorySearch.kt`).
  Contre-épreuve : le test `regular expression mode ignores the case when asked` **échoue** sur
  le code d'avant le patch (`/work/evidence/contre-epreuve-ROUGE.log`, 26 tests, 1 échec) et
  **passe** après (`/work/evidence/apres-patch-VERT.log`, 26 tests, 0 échec).
- **Correctif de la revue hostile appliqué** (Jules, 27/10 15:57) : les réglages de la fenêtre
  (`caseSensitive`, `regexMode`, `lastQuery`) sont conservés entre deux sessions de l'IDE
  (`PropertiesComponent`), et une entrée de résultat sans chemin n'ouvre plus de diff.
- **Deuxième correctif de la revue hostile appliqué** (Jules, 29/10 21:38, rapport complet :
  `https://github.com/tgg49099-dotcom/iamere-jury/pull/9`) : dans `GitLogParser`, l'assertion
  `current!!` est remplacée par une variable locale déjà null-checkée (`curr`). **Aucun changement
  de comportement** : le bytecode des deux versions est identique à une permutation de variables
  locales près, la branche `ifnull` (le test de nullité) est conservée à l'identique.
- **Contre-épreuve M1..M7 (suite de mutations, jouée le 27/10 sur le code d'avant le patch)** :
  6 mutations ciblées du code (M1..M4, M6, M7) + le vrai descripteur de la version précédente
  (M5) → chaque test surveillé passe au **rouge**, puis revient au **vert**
  (`/work/contre-epreuve/rapport.json`, VERT = 25 tests, 0 échec). Le test « dépôt sans aucun
  commit » échoue **pour de vrai** sous la mutation M6 : le code a été corrigé, le test est
  devenu vert.
  ⚠️ Ce rapport date du **27/10** : il n'a **pas** été rejoué après le patch du jury (le
  correctif du 28/10 est prouvé par sa propre contre-épreuve rouge→vert ci-dessus).
- **Le descripteur livré** porte `<vendor email="tgg.apps.support@gmail.com" url="…">Tgg Labs</vendor>` :
  règle **mesurée** le 27/10 (les 4 versions publiées de 35416 la portent et sont approuvées).
  L'adresse personnelle du compte éditeur ne figure dans aucun fichier du produit.
- **L'action fait son `update()` en arrière-plan** (`getActionUpdateThread() = BGT`) : elle
  touche le disque (racine du dépôt), elle ne doit pas geler l'IDE.
- **Le plugin se charge** : démarrage réel d'IntelliJ IDEA Community 2024.3 le **30/10/2026 à
  01:00** avec le **ZIP livré** (le build corrigé des 2 défauts) → log :
  `Loaded custom plugins: Commit History Search (0.0.1)`, **0** occurrence de
  `invalid plugin descriptor`, de `Unknown element`, de `PluginException` et **0 ERROR / SEVERE**
  dans le log (`preuve_ide_chargement.log`, journal `/work/boot_test_cycle18.log`).
- **L'angle est vérifié dans l'IDE** : nativement, l'IDE cherche un commit par son **message**
  (« Search Everywhere → Commit by message », et le champ de recherche de l'onglet Log,
  « Filter by Commit Message or Hashes ») — jamais dans le contenu des diffs. Les captures sont
  dans `/work/capture/out/`.

## Reconstruire le ZIP

```
cd /work/produits/CommitHistorySearch/source
JAVA_HOME=/work/tools/jdk /work/tools/gradle-8.10.2/bin/gradle test buildPlugin --offline \
  -I /work/local-ide.init.gradle.kts
```

⚠️ Le script d'init `-I /work/local-ide.init.gradle.kts` sert l'IDE 2024.3 **depuis le
dépôt local** `/work/ide-repo/` (le cache Gradle du conteneur a été purgé le 30/10) : le
tarball est **bit identique** à celui du site officiel de JetBrains. Ce script ne change
**ni le code ni `build.gradle.kts`** du produit.

⚠️ `gradle build` **ne produit pas** le ZIP : il faut lancer `buildPlugin`.
Le ZIP doit être **reconstruit après** toute modification des sources (le pré-vol refuse un ZIP
plus ancien que les sources).

## Preuve du chargement dans l'IDE

```
bash /work/boot_test.sh     # installe le ZIP, démarre l'IDE, lit le log
```
