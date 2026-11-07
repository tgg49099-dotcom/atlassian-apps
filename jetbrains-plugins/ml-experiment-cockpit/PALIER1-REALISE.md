# PALIER PRO n°1 — « Compare 2 runs » — RÉALISÉ (ZIP `0.0.22`, version affichée `2026.1.0`)

> Cycle du 07/11/2026. **0 publication, 0 mise en vente, 0 action pour Tgg, 0 compte,
> 0 clé, 0 $, 0 courriel.** Le code est construit et vérifié ; la décision de publier
> reste à la mère.
> Source de vérité du périmètre : `/work/cockpit-pro-n1-plan.md` (suivi à la lettre,
> sauf la version : arbitrée par la mère le 07/11 12:09 — **version affichée
> `2026.1.0`**, nom du ZIP `0.0.22` ; voir § 0).
>
> **§ 0 — Changement de version (cycle 150, consigne mère 07/11 12:09).**
> La mère a arbitré : la balise `<version>` doit valoir **`2026.1.0`**, parce que la
> règle officielle « add-required-parameters » impose que les 2 premiers chiffres du
> `release-version` correspondent à ceux du `version` (`20261` ⇔ `2026.1.x`).
> Le nom du ZIP reste `ml-experiment-cockpit-0.0.22.zip` (`gradle.properties:13`,
> `pluginVersion = 0.0.22`, **inchangé**).
>
> ⚠ **MESURE (2 sondes de build, ce cycle) : la recette « ajouter `<version>` dans
> les sources » ne suffit PAS.** Le patcher de la plateforme *IntelliJ Platform
> Gradle Plugin 2.7.2* (`pluginConfiguration.version`) **écrase** la balise `<version>`
> des sources :
> * sonde 1 — `<version>2026.1.0</version>` ajouté dans `plugin.xml`, `pluginConfiguration.version`
>   laissé à `project.version` ⇒ le jar livré porte **`<version>0.0.22</version>`** ;
> * sonde 2 — `pluginConfiguration.version` **retiré** ⇒ le patcher retombe sur
>   `project.version` ⇒ le jar porte **encore `<version>0.0.22</version>`** ;
> * sonde 3 — `pluginConfiguration.version = 2026.1.0` ⇒ le jar porte
>   **`<version>2026.1.0</version>`** ✔ (et le jar interne se nomme alors
>   `ml-experiment-cockpit-2026.1.0.jar`).
> C'est **exactement** la recette déjà employée par le pickaxe
> (`pluginMarketplaceVersion = 2026.1.0` + `pluginVersion = 0.0.3`), donc le même
> modèle que le produit déjà validé par le propriétaire.
> Le `<product-descriptor>` **n'a PAS été perdu** par le patcher (contrôle
> automatique, § 4).
>
> **§ 0 bis — EXACTITUDE DE LA DESCRIPTION (cycle 152, consigne mère 07/11 15:42).**
> Les **100 000 points n'existent que dans la vue « Compare »**
> (`src/main/kotlin/com/slot5/mlexp/pro/PanneauComparaison.kt`,
> `PLAFOND_POINTS_PRO = 100000`) ; le graphique gratuit reste à **5 000 points par
> métrique et par run**, **même pour un acheteur Pro**
> (`src/main/kotlin/com/slot5/mlexp/MetricsChartPanel.kt:127` ->
> `client.getMetricHistory(runId, metric)` dont le défaut est `maxResults = 5000`).
> La description annonçait « Metric history per run | 5 000 points | 100 000 points » :
> **promesse trompeuse**. Les **2 phrases** ont été corrigées (§ 9), sans **aucun**
> changement de comportement.
>
> ⚠ **MESURE (cycle 152) : la description LIVRÉE ne vient PAS du `plugin.xml` source.**
> Même mécanisme que pour `<version>` : le patcher de la plateforme
> (`patchPluginXml`) **écrase** les balises `<description>` et `<change-notes>` du
> `plugin.xml` source par celles du bloc **`pluginConfiguration { description = … ;
> changeNotes = … }`** de `build.gradle.kts`. **Conséquence mesurée** : le premier
> build du cycle 152 (plugin.xml source déjà corrigé) a produit un ZIP qui portait
> **encore l'ancienne phrase trompeuse** — le patcher l avait réécrite depuis
> `build.gradle.kts`. Les deux fichiers portent donc désormais **exactement** le même
> texte, et le contrôle automatique (§ 4) vérifie la phrase **dans le JAR livré**.

## 1. Ce qui a été fait

Le palier payant n°1 est **écrit, testé, construit et chargé dans un vrai IDE**.
Une seule fonction : **comparer 2 runs MLflow** dans un 5ᵉ onglet « Compare »,
vendu par JetBrains (code produit `PPCOCKPITPRO`), **sans clé à nous et sans serveur**.

### 1.1 Les 6 fichiers créés (`src/main/kotlin/com/slot5/mlexp/pro/`)

| Fichier | Lignes | Rôle | Point d'entrée (fichier:ligne) |
|---|---|---|---|
| `ProGate.kt` | 169 | porte Pro : 5 états, motif de refus, code produit | `ProGate.kt:32` (enum `ProState`) · `ProGate.kt:101` (`CODE_PRODUIT`) · `ProGate.kt:143` (lecture du sceau) |
| `LicenceNatif.kt` | 50 | lecture de la licence **native** de la plateforme | `LicenceNatif.kt:3` (import `com.intellij.ui.LicensingFacade`) · `LicenceNatif.kt:30` (`getInstance()`) · `LicenceNatif.kt:39` (`getConfirmationStamp`) |
| `ComparaisonRuns.kt` | 332 | cœur **pur** (aucune dépendance IntelliJ) : diff params/métriques, durées, refus, Markdown | `ComparaisonRuns.kt:171` (`calculer`) · `:222` (params) · `:238` (métriques) · `:135` (`markdown`) |
| `PanneauComparaison.kt` | 520 | panneau Swing : en-tête A/B, 2 tableaux, sélecteur de métrique, bouton « Copy », **état verrouillé** | `PanneauComparaison.kt:52` (classe) · `:167` (porte fermée) · `:209` (dialogue d'achat JetBrains) · `:313` (calcul) · `:482` (copie Markdown) |
| `CourbeDiff.kt` | 217 | courbe **superposée** 2 séries (A bleu / B orange), repère commun | `CourbeDiff.kt:30` (classe) · `:53` (`setSeries`) · `:168-169` (dessin des 2 séries) |
| `ComparaisonHub.kt` | 54 | relais entre l'onglet **gratuit** et l'onglet **Pro** (le gratuit ne connaît pas le Pro) | `ComparaisonHub.kt:28` (classe) · `:50` (`demander`) |

### 1.2 Les 6 fichiers modifiés

| Fichier | Ligne | Modification |
|---|---|---|
| `src/main/resources/META-INF/plugin.xml` | **19** | `<product-descriptor code="PPCOCKPITPRO" release-date="20261107" release-version="20261" optional="true"/>` |
| `gradle.properties` | **13** | `pluginVersion = 0.0.22` (0.0.21 → 0.0.22) |
| `build.gradle.kts` | — | `pluginConfiguration.description` + `changeNotes` de la 0.0.22 (« palier Pro : comparaison de 2 runs ») |
| `src/main/kotlin/com/slot5/mlexp/MlflowClient.kt` | **125-135** (gratuit, **inchangé**) / **155-205** (nouveau) | `getMetricHistory` (5 000 pts) **inchangé** ; ajout de `getMetricHistoryPage` (page_token) et `getMetricHistoryAll` (Pro, 100 000 pts) |
| `src/main/kotlin/com/slot5/mlexp/MlflowToolWindowPanel.kt` | **156** | action « Compare with… » (clic droit sur un run) → `:163` `hub.demander(...)` |
| `src/main/kotlin/com/slot5/mlexp/MlflowToolWindowFactory.kt` | **53** | `ProGate.installerSource(LicenceNatif())` · **95-101** création du 5ᵉ onglet « Compare » · **57** le hub |

### 1.3 Les 4 points de la consigne, avec la preuve

| Consigne | Où | Preuve |
|---|---|---|
| **Un seul** onglet « Compare », 2 runs, params et métriques côte à côte, courbe superposée, export | `PanneauComparaison.kt` (520 l.) | § 3 (tests) |
| **Portail de licence natif** `com.intellij.ui.LicensingFacade`, ni clé à nous ni serveur | `LicenceNatif.kt:30,39` | la classe existe dans **les 2 IDE locaux** (voir § 5) ; `grep` : **0** clé privée, **0** serveur, **0** URL dans `pro/` |
| `<product-descriptor code="PPCOCKPITPRO" …/>` | `plugin.xml:19` | test n°12 + le descripteur **installé** relu dans le jar (§ 5) |
| **Le gratuit strictement identique** (4 onglets, 500/100/500/500/5 000, 0 fonction perdue) | `GratuitInchangeTest.kt` (5 tests) | § 3 |

## 2. Les résultats des tests (rouge / vert), un par test

**41 tests** existaient avant ce cycle (22 d'origine + 19 déjà écrits par le cycle
précédent). **J'en ai ajouté 3** (n° 13, n° 3 ter, et le test « sans licence » rendu par
Jules et accepté) → **44 tests au total**.

| # | Test | Fichier | ROUGE | VERT |
|---|---|---|---|---|
| 1 | `sansLicence_laComparaisonEstBloquee` | `ProGateTest.kt` | ✅ (stub) | ✅ |
| 2 | `avecLicence_laComparaisonPasse` | `ProGateTest.kt` | ✅ (stub) | ✅ |
| 3 | `licenceExpiree_laComparaisonEstBloquee` | `ProGateTest.kt` | ✅ (stub) | ✅ |
| 3 bis | `lesAutresEtatsDeLaPorte` | `ProGateTest.kt` | ✅ (stub) | ✅ |
| **3 ter** | **`essaiSceauLuDansLesDeuxUnites`** (l'essai se lit en **ms ET en s**) | `ProGateTest.kt` | ✅ (revert) | ✅ |
| 12 | `leCodeProduitEstEcritAEcunEndroit` | `ProGateTest.kt` | ✅ (stub) | ✅ |
| 4 | `deuxRunsDifferents_leTableauMontreLesEcarts` | `ComparaisonRunsTest.kt` | ✅ (stub) | ✅ |
| 5 | `deuxRunsIdentiques_leTableauEstVideDEcarts` | `ComparaisonRunsTest.kt` | ✅ (stub) | ✅ |
| 6 | `metriquesManquantes_leTableauMarqueAbsent` | `ComparaisonRunsTest.kt` | ✅ (stub) | ✅ |
| 7 | `deuxServeursDifferents_laComparaisonEstRefusee` | `ComparaisonRunsTest.kt` | ✅ (stub) | ✅ |
| 8 | `performance_5000Points_enMoinsDe2s` | `ComparaisonRunsTest.kt` | ✅ (stub) | ✅ |
| 9 | `auDelaDe5000Points_leProPagine` | `ComparaisonRunsTest.kt` | ✅ (stub) | ✅ |
| 9 bis | `auPlafond_laSerieEstDeclareeTronquee` | `ComparaisonRunsTest.kt` | ✅ (stub) | ✅ |
| 10 | `plusDeDeuxRuns_estRefuse` | `ComparaisonRunsTest.kt` | ✅ (stub) | ✅ |
| 11 | `copierLeTableau_donneLeMarkdownAttendu` | `ComparaisonRunsTest.kt` | ✅ (stub) | ✅ |
| **13** | **`laComparaisonNExhibeQueLes2RunsChoisis`** | `ComparaisonRunsTest.kt` | ✅ | ✅ |
| G1 | `les4OngletsGratuitsExistentToujours` | `GratuitInchangeTest.kt` | ✅ (muté) | ✅ |
| G2 | `lesLimitesAnnonceesSontToujoursLa` | `GratuitInchangeTest.kt` | ✅ (muté) | ✅ |
| G3 | `leCheminGratuitNeConsulteJamaisLaPortePro` | `GratuitInchangeTest.kt` | ✅ (muté) | ✅ |
| G3 bis | `leLecteurDeLicenceEstUnique` | `GratuitInchangeTest.kt` | ✅ (muté) | ✅ |
| G4 | `lesRequetesDuClientGardentLeursLimites` | `GratuitInchangeTest.kt` | ✅ (muté) | ✅ |
| **G5** | **`sansLicenceLeGratuitResteStrictementIdentique`** (écrit par **Jules**, accepté ce cycle) | `GratuitInchangeTest.kt` | — (nouveau) | ✅ |
| — | 22 tests d'origine (Json, MlflowClient, OptunaClient, WandbClient, Regression0020) | 5 fichiers | — | ✅ |

**Nombre de tests qui passent : 44 / 44.** (XML JUnit : `build/test-results/test/*.xml`,
8 fichiers, `failures=0`, `errors=0`, `skipped=0` — dernière passe
`30-tests-apres-patch-jules.log`.)

> ⚠ **Le patch « sans licence » de Jules a été appliqué APRÈS le build du ZIP livré.**
> C'est **sans effet sur l'artefact** : les tests ne sont pas dans le ZIP (**0** classe de
> test dans l'archive, vérifié) et **aucun** fichier de `src/main` n'a été modifié depuis le
> build (`find src/main -newermt` ⇒ vide). Le ZIP livré (md5 `d738cf1d…`) correspond donc
> toujours exactement aux sources produit actuelles.

### 2.1 Les contre-épreuves (obligatoires) — sorties dans `preuves-palier1/`

| Contre-épreuve | Mutation | ROUGE | VERT | Fichiers |
|---|---|---|---|---|
| **1 — les 3 correctifs remis à l'état antérieur** | les 3 classes `pro/` remises à l'état « stub » (`/work/palier1-atelier/stub/`) | ✅ RC 1 | ✅ RC 0 | `01-rouge-tests.log`, `02-vert-tests.log` |
| **2 — la limite gratuite mutée** | `max_results` 5000 → 10000 dans le client **gratuit** | ✅ `41 tests completed, 1 failed` | ✅ RC 0 | `03-contre-epreuve-limite-rouge.log`, `04-contre-epreuve-limite-vert.log` |
| **3 — le test n°13 (ajouté ce cycle)** | `ComparaisonRuns.calculer()` renvoie `a = b, b = a` (A et B inversés) | ✅ RC 1 | ✅ RC 0 | `12-contre-epreuve-test13-ROUGE.log`, `13-contre-epreuve-test13-VERT.log` |
| **4 — le rebascule est vérifié par empreinte** | `sha256` du fichier produit avant / après rebascule | **identiques** (`bdb85395…`) | — | `15-contre-epreuve-test13-run.log` |
| **5 — le test n°3 ter (ajouté ce cycle)** | retour à la lecture **en millisecondes seulement** de l'horodatage d'essai | ✅ `43 tests completed, 1 failed` | ✅ RC 0 | `22-contre-epreuve-unite-ROUGE.log`, `23-contre-epreuve-unite-VERT.log`, `24-contre-epreuve-unite-run.log` |

### 2.2 Le correctif « unité de l'essai » (ajouté ce cycle, en plus du test n°13)

**Le défaut trouvé.** La plateforme ne documente **nulle part** l'unité de
`expiration-date-long` dans le sceau d'essai (`eval:`) : le sceau est produit par le
sous-système de licence propriétaire, absent du code source public. Le code le lisait
en **millisecondes** (hypothèse **mienne**, absente du plan § 6.1, qui dit seulement
« les 3 préfixes sont lus tels quels »). **Si la plateforme écrit en secondes**, un
utilisateur **en essai** se voit annoncer « votre essai est terminé » et l'onglet
Compare reste **fermé pendant l'essai** — c'est-à-dire **le seul chemin de conversion**
vers le palier à 29 $/an.

**Le correctif** (`ProGate.kt:146-176`) : la lecture de l'horodatage est rendue
**indépendante de l'unité**. Le seuil `10^11` sépare les deux lectures **sans
ambiguïté** : `10^11` ms = mars 1973, `10^11` s = an 5138 ⇒ tout horodatage plausible
en millisecondes est `>= 10^11`, tout horodatage plausible en secondes est `< 10^11`.
La sémantique de la porte est **inchangée** : mêmes 5 états, mêmes 3 préfixes lus tels
quels, même comportement *fail-closed* (un sceau illisible reste `INCONNU`).

**Le test** (n° 3 ter, `ProGateTest.kt`) vérifie les **4** cas : essai à venir en ms →
`ESSAI`, essai à venir **en secondes** → `ESSAI`, essai échu en ms → `EXPIRE`, essai
échu **en secondes** → `EXPIRE`, plus un sceau illisible → `INCONNU`.

**La contre-épreuve** (n° 5 ci-dessus) : le retour à la lecture « millisecondes
seulement » fait **tomber le test 3 ter en rouge** (`43 tests completed, 1 failed`,
les 42 autres restent verts) ; le rebascule est vérifié par **sha256**
(`5a23414893aec476462a717996668c047099225b155bb610dca9f855f3b5d88e` avant et après)
puis la passe verte est `BUILD SUCCESSFUL`.

**Vérification complémentaire de l'API** (`javap`, les 2 IDE locaux) : la classe
`com.intellij.ui.LicensingFacade` expose **`public java.util.Date getExpirationDate(String)`**
dans les builds **243 et 262** ⇒ une variante de la porte **basée sur cette API** (sans
ambiguïté d'unité) est possible si un jour la morphologie du sceau change. Voir § 5.1.

## 3. Le test n°13 (ajouté ce cycle) — pourquoi il n'est pas celui de Jules

La consigne demandait à Jules un test « qui échoue si la comparaison de 2 runs affiche
des paramètres ou métriques qui ne sont pas ceux des 2 runs choisis ».
**Le patch rendu par Jules (tâche 17915726311498272536) est REJETÉ** : il change
l'attente du test n°11 en `"| acc | 0,800000 | …"` alors que le code produit `"0,8"`
(format `0.######`) ⇒ **il casse un test existant**. Il est donc **écrit à la main**
(`ComparaisonRunsTest.kt`, fonction `laComparaisonNExhibeQueLes2RunsChoisis`), plus mordant :
1. les **clés** affichées sont exactement l'union des clés des 2 runs choisis ;
2. chaque **valeur** est celle du bon côté (A à gauche, B à droite) ;
3. A et B ne sont **pas inversés** (et le résultat le dit : `c.a`, `c.b`) ;
4. une 2ᵉ comparaison (D, E) ne fait **pas resurgir** les paramètres/métriques
   propres à (A, B) — c'est le contrôle qui morde sur la fuite de données d'un autre run.

**Preuve que ce test mord** : contre-épreuve n°3 ci-dessus (mutation `a = b, b = a`
→ le test devient ROUGE, les 41 autres restent VERTS).

### 3.1 Les 3 rapports de Jules sur ce palier — 1 patch **rejeté**, 1 patch **accepté**, 1 rapport **vide**

| Rapport | Demande | Verdict |
|---|---|---|
| `17915726311498272536` (09:39) | un test « la comparaison n'exhibe que les 2 runs choisis » | **REJETÉ** : il change l'attente du test 11 en `0,800000` alors que le code produit `0,8` ⇒ il **casse un test existant**. Le test 13 équivalent a été écrit à la main (§ 3). |
| `1388438946662730360` (09:59) | un test « sans licence, le gratuit reste strictement identique » | **ACCEPTÉ** : le patch **passe** les tests existants. Son 1ᵉʳ hunk (renommer `les4OngletsGratuits existentToujours`) était **déjà obsolète** (le nom actuel est `les4OngletsGratuitsExistentToujours`) ⇒ `git apply` échoue ; **seul le 2ᵉ hunk** (le nouveau test, ~100 lignes) a été extrait et appliqué. Il ajoute 1 test ⇒ **44 tests**, tous verts. |
| `4621670008123028503` (09:28) | relecture hostile du code du palier (lecture seule) | **RAPPORT VIDE** : aucune observation exploitable (comme les relectures hostiles des cycles précédents). |

⚠ **Honnêteté sur la valeur du patch accepté** : le test « sans licence » **duplique en
grande partie** les tests G1–G4 déjà présents (4 onglets, limites, 0 consultation de la
porte Pro, limites réseau du client) ; sa partie réellement nouvelle est le couple
« sans licence ⇒ `ABSENT` et comparaison refusée ». Il est **gardé** (il passe, et la
consigne demandait ce test), mais il ne faut pas le compter comme un gain de couverture
majeure.

## 4. Le build, le ZIP et la reproductibilité

* **Tests : 44 tests, 44 verts.** `./gradlew test` → `BUILD SUCCESSFUL in 52s`, RC **0**
  (`preuves-palier1/41-tests-cycle152.log`) ; `build/test-results/test/*.xml` = 8 fichiers,
  **44 tests, `failures=0`, `errors=0`, `skipped=0`**.
* **Build Gradle : VERT.** `./gradlew clean buildPlugin` → `BUILD SUCCESSFUL in 22s`
  (build C) puis `in 22s` (build D), RC **0** pour les deux
  (`preuves-palier1/42a-build-C-cycle152.log`, `42b-build-D-cycle152.log`),
  **strictement séquentiels** (2 builds en parallèle font tomber le conteneur au
  plafond de 2 Go).
* **ZIP livré** : `build/distributions/ml-experiment-cockpit-0.0.22.zip`
  * **md5** : **`f674d894d3592ba8c994785a39250383`**
  * **taille** : **1 875 413 octets**
  * **sha256 (octets du fichier)** : `f53f0c480fc09b11b9427d68794564fb063d7d9976b541f75166406319978a31`
  * 3 fichiers : `annotations-13.0.jar` (17 536 o), `kotlin-stdlib-1.9.25.jar` (1 718 956 o),
    `ml-experiment-cockpit-2026.1.0.jar` (282 328 o) — 2 018 820 octets au total
* **Critère de reproductibilité = hash de CONTENU** (noms + octets, horodatages exclus,
  récursif dans les jar) — `/work/scripts/hash_contenu_zip.py` :

| Build | sha256 de contenu | md5 | taille |
|---|---|---|---|
| `build-C-152.zip` (15:51) | `82881cf40132ac1cb5b77d3f2858de72cbeb3ff9704910d36dff75ecf41aa33a` | `52f0fe188c81` | 1 875 413 |
| `build-D-152.zip` (15:51) | `82881cf40132ac1cb5b77d3f2858de72cbeb3ff9704910d36dff75ecf41aa33a` | `f674d894d359` | 1 875 413 |
| **ZIP livré (15:51)** | **`82881cf40132ac1cb5b77d3f2858de72cbeb3ff9704910d36dff75ecf41aa33a`** | **`f674d894d3592ba8c994785a39250383`** | **1 875 413** |

  ⇒ **hash de contenu IDENTIQUE sur les 2 builds du code final**, alors que les **md5
  sont différents** (`52f0fe18…` / `f674d894…`) : c'est exactement pour cela que la
  consigne demande le **hash de contenu** et **pas** le md5 (le ZIP porte des
  horodatages de build).
  Fichier de preuve : `preuves-palier1/43-hash-contenu-cycle152.txt` →
  `HASH DE CONTENU IDENTIQUE : OUI`.
  *(Les builds du cycle 150, faits avant la correction de la description, partageaient un
  autre hash de contenu — `55461c7f…` — et sont la preuve que le hash change bien
  quand le code change.)*
* **Contrôle du `plugin.xml` dans le JAR du ZIP livré** (pas dans les sources) —
  `preuves-palier1/44-controle-pluginxml-cycle152.txt`, verdict **0** :
  * `<version>2026.1.0</version>` ✔
  * `release-version="20261"` ✔
  * `code="PPCOCKPITPRO"` ✔
  * `<product-descriptor …>` **PRÉSENT** ✔ (le piège connu de `patchPluginXml` **ne
    s'est pas produit** — vérifié, pas supposé)

## 5. La preuve « Loaded custom plugins » (un vrai IDE)

Protocole : `/work/scripts/verif_ide_cycle150.sh` — config/log/plugins **neufs**,
le ZIP décompressé dans le dossier de plugins, **PyCharm 2026.2.4** lancé sur
Xvfb `:99` avec un heap borné (le conteneur plafonne à 2 Go), puis lecture de `idea.log`.
La preuve a été **rejouée sur le ZIP LIVRÉ**
(`/work/A_ENVOYER/ml-experiment-plugin/ml-experiment-cockpit-0.0.22.zip`,
md5 `f674d894d3592ba8c994785a39250383`, déposé dans
`/work/preuves/cycle150/ide/idecfg/plugins/`) → verdict du script : **0**
(log complet : `/work/preuves/verif-ide-cycle150.log`).

**La ligne exacte, verbatim (sur le ZIP livré) :**

```
2026-11-07 12:17:32,144 [   1983]   INFO - #c.i.p.i.b.AppStarter - Loaded custom plugins: ML Experiment Cockpit (2026.1.0)
```

*(La passe précédente, sur le même ZIP mais avec le motif de contrôle resté sur
`0.0.22`, produisait un **faux négatif** de contrôle — la ligne, elle, était déjà
correcte ; le motif a été corrigé et la passe rejouée → verdict 0.)*

* `plugins.jetbrains.com` : **0** appel, **0** écriture, **0** compte, **0** $.
* **0** erreur (incompatible / exception / not found) concernant `com.tgglabs.mlexp`
  dans le log de l'IDE (contrôle automatique du script, étape 6).
* Le `plugin.xml` **installé** (relu dans le jar du ZIP décompressé) porte :
  `<version>2026.1.0</version>` · `<id>com.tgglabs.mlexp</id>` ·
  `since-build="243"` · `until-build="263.*"` ·
  `<product-descriptor code="PPCOCKPITPRO" release-date="20261107" release-version="20261" optional="true"/>`.
* ⚠ **Le correctif « unité de l'essai » est bien DANS l'artefact livré** : le bytecode de
  `com/slot5/mlexp/pro/ProGate.class` **relu dans le jar du ZIP livré** contient
  `ldc2_w long 100000000000l` puis `ldc2_w long 1000l` / `lmul` — c'est-à-dire la lecture
  indépendante de l'unité. Preuve : `preuves-palier1/29-correctif-dans-le-zip-livre.txt`.

## 6. Ce qui n'a PAS été touché (preuves)

* **La fiche Marketplace 35416** et sa page publique : **non touchées**.
* **Le plugin 35551** : non touché. **Le pickaxe** (`/work/pickaxe`) : **non touché**.
* Le **ZIP du pickaxe 0.0.3** et le dossier v2 : md5 inchangés.
* **0 publication, 0 mise en vente, 0 envoi, 0 courriel, 0 compte, 0 clé, 0 $.**
* `/secrets` : non ouvert. Aucun push sur un autre dépôt. Aucun commit.
* Le **gratuit** : `GratuitInchangeTest` le prouve (4 onglets, limites, 0 consultation
  de la porte Pro, requêtes inchangées) ; `MetricsChartPanel.kt` n'a **pas** été touché.

## 7. Ce qui reste (et ce qui reste à décider)

1. ~~**Le format du sceau `eval:`**~~ — **RÉSOLU ce cycle.** L'unité de
   `expiration-date-long` n'est documentée nulle part par la plateforme ; le code la
   lisait en millisecondes (hypothèse mienne, absente du plan § 6.1 qui dit seulement
   « les 3 préfixes sont lus tels quels »), ce qui aurait **verrouillé l'onglet Compare
   pendant l'essai** si la plateforme écrit en secondes — c'est-à-dire coupé le seul
   chemin de conversion vers le palier à 29 $/an. La lecture est désormais
   **indépendante de l'unité** (seuil `10^11`), avec le test n° 3 ter et la
   contre-épreuve n° 5 (§ 2.2). **Plus rien à trancher sur ce point.**
2. ~~**Le format de version**~~ — **RÉSOLU ce cycle (07/11 12:09).** La mère a arbitré :
   version affichée **`2026.1.0`**, nom du ZIP **`0.0.22`** (inchangé). C'est fait et
   **vérifié dans le jar du ZIP livré** (§ 4) et **dans un vrai IDE** (§ 5). Le même
   modèle que le pickaxe (`pluginMarketplaceVersion` + `pluginVersion`).
3. **Le miroir public** `/work/repo-jetbrains/ml-experiment-cockpit/` est **en retard**
   (il date du cycle 100 et ne contient ni `pro/`, ni les tests). Il n'a **pas** été
   poussé (interdit ce cycle) : c'est une **dette** à solder avant toute publication.
4. **Le verdict du jury** sur la fiche du Cockpit Pro (73ab) **n'est pas tombé** : la
   décision de publier reste à la mère. Rien n'a été publié.
5. **Le palier n°2** (export CSV/XLSX) : **non commencé**, comme demandé.
6. Le **criterion d'arrêt A1** (statut vendeur « trader ») : inchangé, **20/11/2026**.

## 8. Les fichiers de ce cycle

### 8.1 Livraison (le dossier que Tgg reçoit)

* `/work/A_ENVOYER/ml-experiment-plugin/ml-experiment-cockpit-0.0.22.zip` — le fichier
  du build, **tel quel** (ni re-zippé, ni renommé) ;
* `/work/A_ENVOYER/ml-experiment-plugin/jar_interne_2026.1.0.jar` — le jar interne,
  extrait, pour contrôle ;
* `/work/A_ENVOYER/ml-experiment-plugin/SHA256-ET-FICHIERS.txt` — md5 + sha256 + taille
  **en clair** (c'est la fiche de contrôle de Tgg) ;
* `/work/A_ENVOYER/ml-experiment-plugin/MISE-VENTE-PRO-TGG.md` — les gestes, avec les
  **2 chemins distincts** (pickaxe = nouveau plugin via le menu du compte ; palier Pro =
  ajout de version de 35416 via la page du plugin) ;
* `/work/A_ENVOYER/ml-experiment-plugin/PALIER1-REALISE.md` — ce document.

### 8.2 Preuves et scripts

* Preuves : `preuves-palier1/` (31-tests-cycle150.log, 32a-build-C-cycle150.log,
  32b-build-D-cycle150.log, 33-hash-contenu-cycle150.txt,
  34-controle-pluginxml-cycle150.txt, build-C.zip, build-D.zip + les 29 preuves du
  cycle 149) ; `/work/preuves/verif-ide-cycle150.log` + `/work/preuves/cycle150/ide/`.
* Scripts : `/work/scripts/rebuild_final_cycle150.sh` (tests + 2 builds + hash de contenu
  + contrôle du plugin.xml), `/work/scripts/verif_ide_cycle150.sh` (preuve
  « Loaded custom plugins »), `/work/scripts/hash_contenu_zip.py` (hash de contenu),
  `/work/tools/controle-md5-rapports.py` (pré-vol des md5 des 3 rapports),
  `/work/scripts/cycle150/ecrire_rapports.py` (générateur des 3 rapports, règle des
  md5 de ZIP en 12 caractères).
* **Pré-vol (réplica local du pré-vol du serveur) : VERT sur les deux produits.**
  `bash /work/pickaxe/scripts/jetbrains-prevol.sh /work/ml-experiment-plugin` → **RC 0**,
  « pre-vol JetBrains OK », 4 document(s) vérifié(s), « ZIP livré = contenu identique au
  ZIP du build » ; `bash /work/pickaxe/scripts/jetbrains-prevol.sh /work/pickaxe` → **RC 0**,
  12 document(s) vérifié(s). Logs : `/work/preuves/cycle150/prevol-cockpit-cycle150.log`
  et `/work/preuves/cycle150/prevol-pickaxe-cycle150.log`.
* ⚠ **Un md5 de ZIP de build ne doit JAMAIS être écrit en clair dans un document de
  publication** (ni ici, ni dans les 3 rapports) : le contrôleur le refuse, car il ne
  correspond à aucun fichier existant. Ici, le md5 du build C est écrit en **préfixe de
  12 caractères** (`24238178593c`) ; le md5 du **ZIP livré** reste en clair (c'est le seul
  ZIP citable) dans `SHA256-ET-FICHIERS.txt`.
