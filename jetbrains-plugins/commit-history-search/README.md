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

**État au 29/10/2026 22:10 :** **le texte public du cycle 13 était faux** — il annonçait « the
scan stops after the 500 most recent commits ». C'est corrigé partout (descripteur livré, Gradle,
`description.md`, paquet Tgg) et **verrouillé par des tests**. **33** tests verts. ZIP reconstruit
le 29/10 à 22:07 (patch de la revue hostile du 29/10 21:38 appliqué), **pré-vol 24/24 vert**,
**plugin vérifié chargé dans un vrai IDE avec le build livré** (29/10 à 22:09), et **réglages
vérifiés persistants d'une session IDE à l'autre** (preuve par captures).

## Le livrable

| | |
|---|---|
| Fichier à téléverser | `commit-history-search-0.0.1.zip` (1 668 357 octets, reconstruit le 29/10/2026 à 22:07) |
| md5 | `1e37f7daea945040d1530623b9beedb3` |
| sha256 | `0dbea39bcdb7f4dd167c2d1bc0f1fe775590c2a1a64f4c9c959dcdab15d0ed29` |
| jar interne `commit-history-search-0.0.1.jar` | 66 170 octets — md5 `15873c1f63e894c2a4e2b8a21ef0cfbd` (vérifié **par le contenu du ZIP**, pas seulement sur disque) |
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
- **33 tests verts** (`gradle test buildPlugin --offline --rerun-tasks` : 33 PASSED, 0 échec,
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
- **Le plugin se charge** : démarrage réel d'IntelliJ IDEA Community 2024.3 le **29/10/2026 à
  22:09** avec le **ZIP livré** → log :
  `Loaded custom plugins: Commit History Search (0.0.1)`, **0** occurrence de
  `invalid plugin descriptor`, de `Unknown element`, de `PluginException` et **0 ERROR / SEVERE**
  dans le log (`preuve_ide_chargement.log`).
- **L'angle est vérifié dans l'IDE** : nativement, l'IDE cherche un commit par son **message**
  (« Search Everywhere → Commit by message », et le champ de recherche de l'onglet Log,
  « Filter by Commit Message or Hashes ») — jamais dans le contenu des diffs. Les captures sont
  dans `/work/capture/out/`.

## Reconstruire le ZIP

```
cd /work/produits/CommitHistorySearch/source
JAVA_HOME=/work/tools/jdk /work/tools/gradle-8.10.2/bin/gradle test buildPlugin --offline
```

⚠️ `gradle build` **ne produit pas** le ZIP : il faut lancer `buildPlugin`.
Le ZIP doit être **reconstruit après** toute modification des sources (le pré-vol refuse un ZIP
plus ancien que les sources).

## Preuve du chargement dans l'IDE

```
bash /work/boot_test.sh     # installe le ZIP, démarre l'IDE, lit le log
```
