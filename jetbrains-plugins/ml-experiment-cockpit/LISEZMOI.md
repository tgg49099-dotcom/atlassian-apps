# LISEZMOI — dossier produit « ML Experiment Cockpit » (fiche Marketplace 35416)

Version **2026.1.1** (ZIP candidat `0.0.23`). Ce dossier contient **le code du
plugin** : sources Kotlin, `plugin.xml`, `build.gradle.kts` et les 13 fichiers
de test.

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

Le candidat **n'a jamais ete televerse** : rien n'est en vente, 0 $.
Le prix (29 USD/an) et l'essai sont configures dans la fiche Marketplace, pas
dans ce code. Le texte public ne promet **aucune duree d'essai** : c'est le
reglage de vente du Marketplace qui l'accorde.
