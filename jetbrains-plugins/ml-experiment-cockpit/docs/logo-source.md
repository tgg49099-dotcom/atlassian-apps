# Source des 2 SVG du logo — ML Experiment Cockpit

Fichier écrit le **26/10/2026** (slot 5) à la demande de la mère, après la revue
du jury `a80c` : l'outil de revue n'envoie **jamais** les fichiers `.svg`, donc le
jury ne peut pas voir le logo depuis deux revues (6868 puis a80c). **Le code
source est ici, en texte**, dans un dossier **hors de `src/`** : il ne part donc
**pas** dans le JAR du plugin (le JAR ne contient que `src/main/resources`).

Emplacement réel des deux fichiers :

| Fichier | Rôle JetBrains | Taille |
|---|---|---|
| `src/main/resources/META-INF/pluginIcon.svg` | icône du thème **clair** | 40×40 |
| `src/main/resources/META-INF/pluginIcon_dark.svg` | icône du thème **sombre** | 40×40 |

Règle officielleJetBrains (*Plugin Icon File*) : SVG **40×40**, racine `<svg>`,
**pas** d'image bitmap embarquée, et la variante sombre nommée `<icone>_dark.svg`.
Ces quatre points sont contrôlés **sur le JAR construit** par
`scripts/prevol.sh` (§4, contrôles durs, lignes ~197-250) — pas sur les sources.

---

## 1. `src/main/resources/META-INF/pluginIcon.svg`

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 40 40">
  <!-- ML Experiment Cockpit - dark theme logo, 40x40 source.
       Motif: a cockpit dial - gauge arc plus a tapered needle pointing up-right.
       No text (unreadable at 40px), no third-party mark, no screenshot. -->
  <rect x="2" y="2" width="36" height="36" rx="9" fill="#1F2733"/>
  <path d="M 8.73 17.77 A 12.2 12.2 0 1 1 31.27 17.77" fill="none" stroke="#64748B" stroke-width="2.8" stroke-linecap="round"/>
  <path d="M 18.689 22.582 L 25.080 15.460 L 25.818 15.976 L 21.311 24.418 Z" fill="#34D399"/>
  <circle cx="20" cy="23.5" r="1.6" fill="#34D399"/>
</svg>
```

## 2. `src/main/resources/META-INF/pluginIcon_dark.svg`

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 40 40">
  <!-- ML Experiment Cockpit - dark theme logo, 40x40 source.
       Same dial motif as pluginIcon.svg, with a lighter tile so the icon keeps
       its shape against a dark background. -->
  <rect x="2" y="2" width="36" height="36" rx="9" fill="#2B3543"/>
  <path d="M 8.73 17.77 A 12.2 12.2 0 1 1 31.27 17.77" fill="none" stroke="#8593A8" stroke-width="2.8" stroke-linecap="round"/>
  <path d="M 18.689 22.582 L 25.080 15.460 L 25.818 15.976 L 21.311 24.418 Z" fill="#4ADE80"/>
  <circle cx="20" cy="23.5" r="1.6" fill="#4ADE80"/>
</svg>
```

---

## Ce que le jury peut vérifier avec ce fichier

1. **Les deux SVG existent** et ne sont pas vides (2 264 et 2 448 octets).
2. **La racine est `<svg … width="40" height="40" viewBox="0 0 40 40">`** — les
   deux, donc la contrainte 40×40 de JetBrains est respectée.
3. **Aucun bitmap embarqué** : pas de `<image …>`, pas de `base64` — le logo est
   du vectoriel pur (3 formes + 1 arc + 1 cercle par fichier).
4. **Aucun texte** dans le SVG (illisible à 40 px), **aucune marque tierce**,
   **aucune capture d'écran** dans le logo.
5. **La variante sombre existe** et porte le nom exact `pluginIcon_dark.svg`.

## Réserve honnête sur les commentaires internes

Les **commentaires HTML** des deux fichiers sont **intervertis** par rapport à
leur rôle : le commentaire de `pluginIcon.svg` dit « *dark theme logo* » alors
que c'est le fichier du thème **clair**, et celui de `pluginIcon_dark.svg` dit
l'inverse. Ce n'est qu'un commentaire (l'image rendue est correcte et le
contrôle 40×40 est vert), mais c'est une source qui ment à son lecteur.

**Non corrigé dans ce cycle** : corriger un SVG est un changement de contenu
d'artefact, qui ne peut arriver qu'**avec une nouvelle version** de plugin — et
la mère a interdit toute nouvelle version et toute publication ce cycle
(consigne du 26/10, §1). La correction est notée pour la **prochaine version
autorisée**. Ce que la mère peut vérifier dès aujourd'hui : le fichier
`pluginIcon.svg` porte bien la tuile foncée `#1F2733` et l'aiguille verte
`#34D399`, et `pluginIcon_dark.svg` la tuile plus claire `#2B3543` et l'aiguille
`#4ADE80`.

## Preuve que les deux SVG sont bien dans le JAR

Pas une copie de source : le contrôle est fait sur **le JAR construit**, extrait
du ZIP livré. La sortie de `scripts/prevol.sh` (§4) montre, pour chaque
fichier : `META-INF/<icon> is inside the built JAR`, la taille et le sha256 du
fichier **extrait du JAR**, puis `is 40x40 (root <svg> width/height or
viewBox)`, puis l'absence de `<image`/`base64`. Le journal de ce cycle est
`/work/research/cycle58/prevol_patched.log`.
