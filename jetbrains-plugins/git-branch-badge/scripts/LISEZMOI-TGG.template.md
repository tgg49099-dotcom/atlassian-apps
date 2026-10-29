# Tgg — mettre en ligne le plugin gratuit « Git Branch Badge »

Ce dossier contient tout ce qu'il faut. Compte : **25 minutes**, une seule fois.
Tu n'as besoin d'aucune clé, d'aucune carte, d'aucun code : seulement **ta session JetBrains**.

Pourquoi c'est toi : JetBrains impose que **le premier téléversement** d'un plugin se fasse à la main
dans l'interface (l'API ne sert qu'aux versions suivantes). Donc : une connexion, un envoi de fichier,
19 champs à remplir.

---

## 1. Les fichiers de ce dossier

| Fichier | A quoi il sert |
|---|---|
| `git-branch-badge-0.0.1.zip` | **le plugin à téléverser** (env. 1,6 Mo, md5 `MD5_PLACEHOLDER`) |
| `icone/pluginIcon.svg` | icône du plugin (SVG 40x40) |
| `icone/pluginIcon_dark.svg` | variante sombre (facultative) |
| `captures/01-panneau-project-branche.png` | capture 1 (1400x1000) |
| `captures/02-libelle-branche-zoom.png` | capture 2 (1208x901) |
| `LISEZMOI-TGG.md` | ce fichier |

## 2. Contrôle avant de commencer (30 secondes)

Ouvre un terminal dans le dossier décompressé et vérifie l'empreinte du plugin :

```
md5sum git-branch-badge-0.0.1.zip
```

Le résultat doit être **exactement** `MD5_PLACEHOLDER`.
Si ce n'est pas le cas, **n'envoie rien** et dis-le-moi.

## 3. Les étapes, dans l'ordre

Départ : **https://plugins.jetbrains.com/** (connecte-toi avec le compte JetBrains qui porte le
profil vendeur **« Tgg Labs »** — c'est celui du plugin déjà en ligne, aucun profil à créer).

> ⚠️ **ATTENTION — le bouton à ne PAS cliquer.**
> Sur la page du profil / de la boutique, **« Upload plugin »** ajoute une **version** d'un plugin
> **déjà listé**. Si tu cliques dessus, Git Branch Badge partirait comme **version 0.0.1 de
> « ML Experiment Cockpit »** (notre plugin 35416 déjà en ligne) et je perdrais les 34
> téléchargements déjà faits.
> Il faut **« Add new plugin »** (la doc officielle dit : sur la page Profile, choisir
> « Add new plugin »), ou **« Create new plugin »** si c'est ainsi que le bouton est libellé chez
> nous. **Les deux creatent une NOUVELLE fiche. Jamais « Upload plugin ».**

| # | Où | Ce qu'on y met |
|---|---|---|
| 1 | Menu du compte (en haut à droite) | « Tgg Labs » doit s'afficher |
| 2 | **Add new plugin** (ou **Create new plugin**) — **jamais « Upload plugin »** | — |
| 3 | **Vendor profile** | **Tgg Labs** (déjà existant, ne pas en créer un autre) |
| 4 | *Si proposé* : **accept the JetBrains Marketplace Developer Agreement** | signer (c'est un acte juridique : toi seul) |
| 5 | **Plugin file** → Upload plugin file — ou, si la nouvelle fiche est d'abord créée (bouton « Add the plugin »), le téléversement se fait ensuite, sur la page du plugin | `git-branch-badge-0.0.1.zip` |
| 6 | **Plugin name** | `Git Branch Badge` |
| 7 | **Plugin ID** | `com.tgglabs.branchbadge` (définitif, ne plus le changer) |
| 8 | **Description** | le texte §4.1 ci-dessous |
| 9 | Bouton `</>` à côté du champ Description | basculer en HTML **avant** de coller, sinon coller tel quel |
| 10 | **Change notes** | le texte §4.2 |
| 11 | **Tags** | `VCS`, `VCS Integration`, `Navigation`, `Productivity` |
| 12 | **Categories** | `Version Control` |
| 13 | **License** | `Proprietary` + l'EULA §4.3 collé dans le champ |
| 14 | **Source code URL** | laisser vide (le plugin n'est pas open source) |
| 15 | **Privacy policy** | laisser vide (aucune donnée collectée) |
| 16 | **Icon** | `icone/pluginIcon.svg` |
| 17 | **Screenshots** (section Media) | `captures/01-panneau-project-branche.png` puis `captures/02-libelle-branche-zoom.png` |
| 18 | **Pricing** | `Free` (le plugin est gratuit) |
| 19 | **Submit / Upload** | — |

## 4. Les textes exacts à coller

### 4.1 — Description

```
See the Git branch you are on, right next to your project name.

Git Branch Badge shows the current Git branch on the project root in the Project view, as `path [branch]`: the project path is kept, the branch is added next to it. The label refreshes by itself the moment you switch branches, whether you switch inside the IDE or from a terminal, and a detached HEAD is shown as `detached @ abc1234` instead of disappearing. It only reads the local .git/HEAD file: no account, no network request, nothing to configure.

To install it, open Settings > Plugins, search for "Git Branch Badge", click Install and restart the IDE.

Built with AI assistance.
```

### 4.2 — Change notes

```
0.0.1 — First release.

- The current Git branch is shown next to the project root in the Project view, as `path [branch]`: the project path is kept, the branch is added.
- The label refreshes by itself when you switch branches, even from a terminal.
- A detached HEAD is shown as `detached @ abc1234` instead of nothing.
- The project path stays available in the node tooltip.
```

### 4.3 — EULA (champ License)

```
Git Branch Badge is proprietary software (c) Tgg Labs.

Permission is granted to install and use this plugin in any JetBrains IDE for
personal or commercial work. The software is provided "as is", without warranty
of any kind, express or implied. Redistribution, modification or resale of the
plugin binary is not permitted.

The plugin reads the local Git metadata of your project (the .git/HEAD file) to
display the current branch name. It performs no network request, collects no
personal data and sends no telemetry.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE.
```

## 4bis. Le garde-fou de publication (à connaître, mais rien à faire)

Le dossier que tu reçois ne peut pas être reconstruit par moi tant que la
publication n'est pas décidée : les scripts `scripts/prevol.sh` et
`scripts/build_soumission_bundle.py` **refusent** de produire une version
tant que ces deux choses n'existent pas :

1. une **revue du jury** pour le produit 2 et la version 0.0.1 : un fichier
   `/work/REVUE-JURY/<id>.md` contenant `revue: OK`, la version **exacte**
   `0.0.1` (pas `0.0.13` : ce serait un autre produit), et le nom du produit
   (« Git Branch Badge » ou « BranchProjectView ») ;
2. ton **approbation** : un fichier `/work/APPROBATION-TGG.md` contenant la
   version exacte `0.0.1`, le mot **APPROUVE**, et le nom du produit.

Chaque refus est écrit dans `/rapports/garde_fou_publication.log`, avec la
raison. Si un jour tu me redemandes de reconstruire le ZIP et que je réponds
« refus », c'est que l'une des deux pièces manque : dis-le-moi, je te donne la
ligne exacte du log.

## 5. Si un bouton est grisé

| Symptôme | Cause | Solution |
|---|---|---|
| « Upload plugin » grisé | pas connecté, ou profil vendeur non validé | reconnecte-toi ; vérifie https://plugins.jetbrains.com/vendor/tgg-labs |
| « Upload plugin file » grisé | mauvais fichier | c'est bien le `.zip` (1,6 Mo), pas le dossier |
| Un bouton **« Upload plugin »** est le seul visible | tu es sur la page d'un plugin déjà listé | **reviens en arrière** : il faut **« Add new plugin » / « Create new plugin »** (cf. l'avertissement en tête) |
| « Submit / Upload » grisé | un champ obligatoire est vide | dans l'ordre : **Change notes** (jamais vide), **EULA**, **icône**, **2 captures** |
| « Plugin ID » en rouge | l'id ne correspond pas au plugin | il doit être exactement `com.tgglabs.branchbadge` |
| « name is not unique » | un plugin du même nom existe | dis-le-moi, je renomme et je reconstruis |
| Les captures sont refusées | format/taille | ce sont des PNG 1400x1000 et 1208x901, elles passent |

## 6. Après le clic

- La fiche doit afficher **« en revue » / « uploaded »**. Un envoi n'est **pas** une mise en ligne :
  chaque version est relue à la main, compte **3 à 4 jours ouvrés**.
- **Ne renvoie pas de 0.0.2** tant que la 0.0.1 est en revue (deux versions en attente = deux revues).
- Réponds-moi **« fait »** + une capture de la fiche (ou de l'écran « en revue »).
- Ce que je ferai ensuite : je surveille la mise en ligne et je mesure les téléchargements.

Rappel : le plugin est gratuit, il ne demande aucun paiement, et il ne collecte aucune donnée.
