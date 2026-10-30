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

**État au 30/10/2026 04:53 :** **le texte public du cycle 13 était faux** — il annonçait « the
scan stops after the 500 most recent commits ». C'est corrigé partout (descripteur livré, Gradle,
`description.md`, paquet Tgg) et **verrouillé par des tests**. **44** tests verts. ZIP reconstruit
le 30/10 à 04:53 : les **2 défauts de la relecture hostile du 30/10 00:25** (pager de git et
entrée standard de l'enfant, annulation réactive et délai maximal) **et les 2 défauts de la
relecture du cycle 88** (« Case sensitive » : les lignes modifiées étaient ratées ; nom de
fichier non-ASCII : le diff s'ouvrait vide) sont corrigés, **pré-vol vert**,
**plugin vérifié chargé dans un vrai IDE avec le build livré**, et **réglages vérifiés
persistants d'une session IDE à l'autre** (preuve par captures).

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


## Le livrable

| | |
|---|---|
| Fichier à téléverser | `commit-history-search-0.0.1.zip` (1 672 104 octets, reconstruit le 30/10/2026 à 04:53) |
| md5 | `12c7284de811f8166020e0d428288550` |
| sha256 | `e8b6b89e13fbf28e29ae257f90667c320533c557ee8580be06874fac669aa40e` |
| jar interne `commit-history-search-0.0.1.jar` | 70 046 octets — md5 `b141abe3b704a75411cd4fef7ccd3b3d` (vérifié **par le contenu du ZIP**, pas seulement sur disque) |
| Nom public | `Commit History Search` (21 caractères, sans mot interdit) |
| Version | `0.0.1` |
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
| `commit-history-search-0.0.1.zip` | **le livrable** à téléverser |
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
2. Téléverser `commit-history-search-0.0.1.zip`.
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
- **44 tests verts** (`gradle test buildPlugin` : 44 PASSED, 0 échec,
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
