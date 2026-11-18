# LISEZMOI — dossier produit « ML Experiment Cockpit » (fiche Marketplace 35416)

Version **2026.1.5** (ZIP candidat `ml-experiment-cockpit-2026.1.5.zip`). Ce dossier contient **le code du
plugin** : sources Kotlin, `plugin.xml`, `build.gradle.kts` et les 21 fichiers
de test (20 classes de test Kotlin + 1 ressource) — **101 tests, 19 classes de test**.

**Ce que ce dossier n'est pas :** ce n'est pas un ZIP a decompresser, ni un
dossier de preuves. Il a ete **allege** pour que la revue dispose de tous les
fichiers : les annexes, les scripts, les preuves de builds et le binaire du
wrapper Gradle sont **sortis** de l'arbre.

## Ce qui est sorti (tout est conserve, rien n'est perdu)

| Deplace | Ou |
|---|---|
| `JURY-*.md`, `PALIER1-REALISE.md`, `CHANGELOG.md`, `contre_epreuve_pro.py`, `preuves-palier1/`, `scripts/`, `docs/`, `media/` | `/work/A_ENVOYER/ARBRE-ALLEGE-20261111/` |
| `gradle/`, `gradlew`, `gradlew.bat` (wrapper : binaire de 43 Ko, sans valeur de revue) | `/work/A_ENVOYER/ARBRE-ALLEGE-20261111/` |
| `PRIVACY.md`, `SECURITY.md`, `SUPPORT.md` | `/work/A_ENVOYER/ARBRE-ALLEGE-20261111/` |
| `build/`, `.gradle/`, `.intellijPlatform/` (sorties de build / cache Gradle IntelliJ) | `/work/preuves/cycle175/` |

Le lot de revue du jury est dans **`/work/jurysend-20261111-v2/`** (en
DOSSIER) : les 4 annexes au contenu integral (`ProGate.kt`,
`PanneauComparaison.kt`, `plugin.xml`, le test de la porte), la preuve de
l'essai, la note et les empreintes SHA256.

## Construire

Le wrapper Gradle est archive : la version est figee dans
`gradle/wrapper/gradle-wrapper.properties` (**Gradle 8.10.2**). Avec un
Gradle 8.10.2 installe :

    gradle buildPlugin verifyPlugin

(la commande exacte du projet est dans `README.md`, section « Build »).

## Etat

Le code de ce dossier est la version **2026.1.5**. Le Marketplace liste les versions
approuvees : au 18/11/2026, c'est la **2026.1.4** (updateId 1222361) ; la 2026.1.5 est
prete a etre televersee. Rien n'est en vente pour l'instant : **0 $**
(`purchaseInfo.buyUrl` est `null` tant que le palier n'est pas ouvert).
Le prix (29 EUR/an) et l'essai sont configures dans la fiche Marketplace, pas
dans ce code. Le texte public annonce la duree reglee par le Marketplace
(**30 jours**, `purchaseInfo.trialPeriod`) et **aucune autre**.
