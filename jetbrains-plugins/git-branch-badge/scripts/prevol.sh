#!/usr/bin/env bash
# =============================================================================
# prevol.sh — PRE-VOL du produit 2 « Git Branch Badge » (slot 5 JetBrains).
#
# A quoi ca sert : le serveur de la mere refuse de lancer la revue
# Marketplace quand la fiche du produit est incomplete. Au cycle 70, le
# controle du serveur a echoue sur « change-notes manquantes ou vides dans
# plugin.xml » : les notes de version n'etaient que dans build.gradle.kts
# (donc absentes du SOURCE), et la revue les lit dans le source.
#
# Ce script rejoue, en local, les controles que le serveur fait :
#   0. le GARDE-FOU DE PUBLICATION (voir plus bas) — il peut REFUSER le
#      pre-vol entier ;
#   1. sur le SOURCE  src/main/resources/META-INF/plugin.xml (nom + change-notes)
#   2. sur l'ARTEFACT  le plugin.xml reellement embarque dans le ZIP
#   3. sur le ZIP     structure, icones, classes, absence de markdown
#
# Un controle qui ne peut pas echouer ne prouve rien : ce script a donc ete
# teste en NEGATIF (cf. prevol_negatif_c70.sh) — ZIP saboté => sortie 1.
#
# USAGE :  ./scripts/prevol.sh [RACINE]     (defaut : la racine du produit)
# SORTIE : code 0 = pret pour la revue, code 1 = au moins un controle dur
#          a echoue, code 2 = impossible de continuer (artefact absent),
#          code 3 = REFUS DU GARDE-FOU DE PUBLICATION.
# La DERNIERE ligne affichee est toujours :
#     nom OK (<nom>, <n> car.) | change-notes : <n> car.
# =============================================================================
set -uo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$ROOT" || exit 2

# -----------------------------------------------------------------------------
# 0. GARDE-FOU DE PUBLICATION (priorité de la mere, consigne du 27/10)
# -----------------------------------------------------------------------------
# JETBRAINS_MARKETPLACE_TOKEN est dans l'environnement du slot : il pourrait
# donc publier seul. Ce garde-fou l'en empeche tant que la publication n'est
# pas explicitement decidée par la mere :
#
#   (a) il faut une REVUE du jury pour CE produit et CETTE version : un
#       fichier /work/REVUE-JURY/<id>.md contenant « revue: OK », la version
#       exacte, ET le nom du produit ;
#   (b) il faut une APPROBATION de la mere : /work/APPROBATION-TGG.md
#       contenant la version exacte, le mot APPROUVE, ET le nom du produit.
#
# DEUX PRECAUTIONS (mesurees : /work/REVUE-JURY contient les revues de TOUS
# les produits du slot) :
#   - la version doit apparaitre comme un JETON EXACT : « 0.0.1 » est accepte,
#     « 0.0.13 » ne l'est pas (c'est un autre produit, et 0.0.1 y est un
#     sous-texte) ;
#   - le fichier doit nommer LE PRODUIT : une revue « revue: OK » du produit 1
#     ne vaut PAS approbation du produit 2.
#
# Chaque refus est ECRIT dans /rapports/garde_fou_publication.log (avec la
# raison) : un garde-fou dont le refus n'est pas trace ne prouve rien.
# Les 2 chemins sont FIGES ici : pas de variable d'environnement, pas de
# contournement.
GUARDE_LOG="/rapports/garde_fou_publication.log"
REVUE_DIR="/work/REVUE-JURY"
APPROBATION_FILE="/work/APPROBATION-TGG.md"
PRODUIT_RE='BrancheProjectView|branchbadge|Git Branch Badge'

garde_log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$1" >> "$GUARDE_LOG" 2>/dev/null; }

VERSION_G="$(grep -oP '^pluginVersion\s*=\s*\K\S+' gradle.properties 2>/dev/null | tr -d '\r')"
[ -n "$VERSION_G" ] || { echo "gradle.properties : pluginVersion introuvable" >&2; exit 2; }
# La version doit etre un jeton EXACT : « 0.0.1 » OK, « 0.0.13 » NON.
VER_RE="$(printf '%s' "$VERSION_G" | sed 's/\./\\./g')"

# Vrai si le fichier contient la version exacte ET le nom du produit.
version_et_produit() {
  grep -qE "(^|[^0-9.])${VER_RE}([^0-9]|$)" "$1" && grep -qiE "$PRODUIT_RE" "$1"
}

GARDE_KO=0
REVUE_OK_FILE=""
if [ -d "$REVUE_DIR" ]; then
  for F in "$REVUE_DIR"/*.md; do
    [ -f "$F" ] || continue
    if grep -qE '^[[:space:]]*revue:[[:space:]]*OK[[:space:]]*$' "$F" && version_et_produit "$F"; then
      REVUE_OK_FILE="$F"; break
    fi
  done
fi
if [ -z "$REVUE_OK_FILE" ]; then
  MSG="REFUS prevol.sh v${VERSION_G} : aucune revue du jury pour le produit 2 version ${VERSION_G} dans ${REVUE_DIR} (attendu : un *.md contenant \"revue: OK\", la version exacte ${VERSION_G}, ET le nom du produit « Git Branch Badge »)"
  garde_log "$MSG"
  printf '\033[31m%s\033[0m\n' "$MSG"
  GARDE_KO=1
fi
if [ ! -f "$APPROBATION_FILE" ]; then
  MSG="REFUS prevol.sh v${VERSION_G} : ${APPROBATION_FILE} absent (attendu : le fichier doit contenir la version exacte ${VERSION_G}, le mot APPROUVE, ET le nom du produit « Git Branch Badge »)"
  garde_log "$MSG"
  printf '\033[31m%s\033[0m\n' "$MSG"
  GARDE_KO=1
elif ! version_et_produit "$APPROBATION_FILE" || ! grep -qE 'APPROUV[EÉ]' "$APPROBATION_FILE"; then
  MSG="REFUS prevol.sh v${VERSION_G} : ${APPROBATION_FILE} ne contient pas (version exacte ${VERSION_G} ET mot APPROUVE ET nom du produit « Git Branch Badge »)"
  garde_log "$MSG"
  printf '\033[31m%s\033[0m\n' "$MSG"
  GARDE_KO=1
fi
if [ "$GARDE_KO" -ne 0 ]; then
  printf '\033[31mPRE-VOL REFUSE PAR LE GARDE-FOU DE PUBLICATION\033[0m — ne pas publier.\n'
  exit 3
fi
garde_log "OK prevol.sh v${VERSION_G} : garde-fou passe (revue $(basename "$REVUE_OK_FILE"), ${APPROBATION_FILE})"

FAILED=0
ok()    { printf '  \033[32mOK\033[0m    %s\n' "$1"; }
bad()   { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILED=$((FAILED + 1)); }
warn()  { printf '  \033[33mWARN\033[0m  %s\n' "$1"; }
head_() { printf '\n\033[1m%s\033[0m\n' "$1"; }

SRC_XML="src/main/resources/META-INF/plugin.xml"
EXPECTED_ID="com.tgglabs.branchbadge"
EXPECTED_NAME="Git Branch Badge"
EXPECTED_VENDOR="Tgg Labs"
# Residue qui ne doit jamais atteindre une fiche publiee.
FORBIDDEN_RE='placeholder|example\.invalid|lorem ipsum|TODO|TBD|bug fixes and improvements'

VERSION="$(grep -oP '^pluginVersion\s*=\s*\K\S+' gradle.properties 2>/dev/null)"
NAME_DECL="$(grep -oP '^pluginName\s*=\s*\K.*' gradle.properties 2>/dev/null | tr -d '\r')"
UNTIL_BUILD="$(grep -oP '^pluginUntilBuild\s*=\s*\K\S+' gradle.properties 2>/dev/null)"
[ -n "$VERSION" ] || { echo "gradle.properties : pluginVersion introuvable" >&2; exit 2; }
UNTIL_RE="$(printf '%s' "$UNTIL_BUILD" | sed 's/[.[\*^$]/\\&/g')"

# -----------------------------------------------------------------------------
head_ "0. Source : presence des fichiers attendus"
for F in "$SRC_XML" gradle.properties build.gradle.kts settings.gradle.kts description_en.md; do
  if [ -f "$F" ]; then ok "$F present"; else bad "$F MANQUANT"; fi
done
[ -d src/main/kotlin/com/tgglabs/branchbadge ] \
  && ok "sources Kotlin presentes" || bad "sources Kotlin MANQUANTES"

# -----------------------------------------------------------------------------
head_ "1. Source : nom public"
# La revue Marketplace refuse un nom qui contient « plugin » ou « IntelliJ ».
# ATTENTION : on rogne les espaces EN BOUT, on ne les supprime pas (un `tr -d`
# ici rendait « Git Branch Badge » -> « GitBranchBadge » : le premier controle
# du pre-vol a ete fausse negatif a cause de ca).
XML_NAME="$(sed -n 's:.*<name>\(.*\)</name>.*:\1:p' "$SRC_XML" | head -1 \
            | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
if [ -z "$XML_NAME" ]; then
  bad "<name> absent de $SRC_XML"
elif printf '%s' "$XML_NAME" | grep -qiE 'plugin|intellij|jetbrains'; then
  bad "nom '$XML_NAME' contient un mot interdit (plugin / intellij / jetbrains)"
elif [ "${#XML_NAME}" -gt 30 ]; then
  bad "nom '$XML_NAME' : ${#XML_NAME} caracteres, la limite Marketplace est 30"
else
  ok "nom du source = '$XML_NAME' (${#XML_NAME} car.)"
fi
[ "$XML_NAME" = "$EXPECTED_NAME" ] \
  && ok "nom conforme a la decision de la mere ($EXPECTED_NAME)" \
  || bad "nom '$XML_NAME' != '$EXPECTED_NAME' (decision de la mere)"
[ "$NAME_DECL" = "$EXPECTED_NAME" ] \
  && ok "gradle.properties : pluginName = $NAME_DECL" \
  || bad "gradle.properties : pluginName = '$NAME_DECL' != '$EXPECTED_NAME'"

# -----------------------------------------------------------------------------
head_ "2. Source : change-notes (le controle qui bloquait au cycle 70)"
CN_RAW="$(awk '/<change-notes>/{f=1} f{print} /<\/change-notes>/{if(f)exit}' "$SRC_XML")"
CN_TEXT="$(printf '%s' "$CN_RAW" \
            | sed 's/.*<!\[CDATA\[//; s/\]\]>.*//' \
            | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
CN_CHARS="$(printf '%s' "$CN_TEXT" | wc -m | tr -d ' ')"
CN_COUNT="$(grep -c '<change-notes>' "$SRC_XML")"
CN_LI="$(printf '%s' "$CN_TEXT" | grep -c '<li>')"

if [ "$CN_COUNT" != "1" ]; then
  bad "<change-notes> : $CN_COUNT element(s) dans $SRC_XML (il en faut exactement 1)"
elif [ "$CN_CHARS" -lt 60 ]; then
  bad "change-notes : $CN_CHARS caracteres — trop court pour une vraie note de version"
else
  ok "change-notes : 1 element, $CN_CHARS caracteres, $CN_LI bullet(s)"
fi
printf '%s' "$CN_TEXT" | grep -qiE 'bug fixes and improvements' \
  && bad "change-notes : gabarit interdit (« bug fixes and improvements »)" \
  || ok "change-notes : pas de gabarit générique"
printf '%s' "$CN_TEXT" | grep -qE '<h3>0\.0\.1' \
  && ok "change-notes : titre « 0.0.1 » (la version reellement livree)" \
  || bad "change-notes : le titre ne mentionne pas la version livree (0.0.1)"

# -----------------------------------------------------------------------------
head_ "3. Source : residu interdit"
N_HITS="$(grep -a -c -iE "$FORBIDDEN_RE" "$SRC_XML" 2>/dev/null || true)"
[ "${N_HITS:-0}" = "0" ] \
  && ok "$SRC_XML : aucun residu interdit" \
  || { bad "$SRC_XML : $N_HITS occurrence(s) interdite(s)"; grep -inE "$FORBIDDEN_RE" "$SRC_XML" | sed 's/^/        /'; }

# -----------------------------------------------------------------------------
head_ "4. Artefact : le ZIP livre"
# Le ZIP du BUILD est dans build/distributions/ : c'est lui que le script de
# construction du dossier de soumission copie dans dist/ (donc lui qui sera
# televerse). C'est donc lui qu'on controle EN PREMIER.
# dist/ n'est qu'une copie de sauvegarde : elle peut etre PERIMEE (c'est le
# cas tant que le garde-fou de publication bloque la construction du dossier
# de soumission) ; on le signale alors en WARN, sans bloquer.
ZIP=""
for CAND in "build/distributions/git-branch-badge-${VERSION}.zip" \
            "dist/git-branch-badge-${VERSION}.zip"; do
  if [ -f "$CAND" ]; then ZIP="$CAND"; break; fi
done
if [ -z "$ZIP" ]; then
  bad "ZIP introuvable (ni build/distributions/ ni dist/ en version $VERSION)"
  exit 2
fi
ZIP_SIZE="$(stat -c%s "$ZIP")"
ZIP_SHA="$(sha256sum "$ZIP" | cut -d' ' -f1)"
ZIP_MD5="$(md5sum "$ZIP" | cut -d' ' -f1)"
ok "$ZIP ($ZIP_SIZE octets)"
ok "sha256 = $ZIP_SHA"
ok "md5    = $ZIP_MD5"

# La copie de sauvegarde dans dist/ doit etre le MEME fichier que celui du
# build ; sinon elle est perimee (elle sera remplacee a la prochaine
# construction du dossier de soumission). Warn, pas blocage.
DIST_ZIP="dist/git-branch-badge-${VERSION}.zip"
if [ -f "$DIST_ZIP" ] && [ "$DIST_ZIP" != "$ZIP" ]; then
  DIST_MD5="$(md5sum "$DIST_ZIP" | cut -d' ' -f1)"
  if [ "$DIST_MD5" != "$ZIP_MD5" ]; then
    warn "la copie dist/ est PERIMEE (md5 $DIST_MD5) : elle sera remplacee par le ZIP du build (md5 $ZIP_MD5) a la prochaine construction du dossier de soumission"
  else
    ok "la copie dist/ est identique au ZIP du build"
  fi
fi

ZIP_ENTRIES="$(unzip -Z1 "$ZIP" 2>/dev/null)"
INNER_JAR_ENTRY="git-branch-badge/lib/git-branch-badge-${VERSION}.jar"
INNER_COUNT="$(grep -c "^${INNER_JAR_ENTRY}\$" <<< "$ZIP_ENTRIES" || true)"
if [ "$INNER_COUNT" = "1" ]; then
  ok "JAR interne unique : $INNER_JAR_ENTRY"
else
  bad "attendu 1 entree '$INNER_JAR_ENTRY', trouve $INNER_COUNT"
  exit 2
fi

TMP_JAR="$(mktemp /tmp/pvb.XXXXXX.jar)"
JAR_DIR="$(mktemp -d /tmp/pvb.XXXXXX)"
trap 'rm -f "$TMP_JAR"; rm -rf "$JAR_DIR"' EXIT
unzip -p "$ZIP" "$INNER_JAR_ENTRY" > "$TMP_JAR" 2>/dev/null
unzip -q -o "$TMP_JAR" -d "$JAR_DIR" 2>/dev/null
BUILT_XML="$(unzip -p "$TMP_JAR" META-INF/plugin.xml 2>/dev/null)"
if [ -z "$BUILT_XML" ]; then
  bad "pas de META-INF/plugin.xml dans le JAR construit"
  exit 2
fi
ok "plugin.xml du JAR construit : ${#BUILT_XML} octets"

# -----------------------------------------------------------------------------
head_ "5. Artefact : identite du plugin"
grep -q "<id>${EXPECTED_ID}</id>" <<< "$BUILT_XML" \
  && ok "id = $EXPECTED_ID" || bad "id != $EXPECTED_ID"
grep -q "<version>${VERSION}</version>" <<< "$BUILT_XML" \
  && ok "version = $VERSION" || bad "version != $VERSION"
grep -qE ">[[:space:]]*${EXPECTED_VENDOR}[[:space:]]*</vendor>" <<< "$BUILT_XML" \
  && ok "vendor = $EXPECTED_VENDOR" || bad "vendor != $EXPECTED_VENDOR"
grep -q "until-build=\"${UNTIL_RE}\"" <<< "$BUILT_XML" \
  && ok "until-build = $UNTIL_BUILD" || bad "until-build != $UNTIL_BUILD"
grep -q "since-build=" <<< "$BUILT_XML" \
  && ok "since-build present" || bad "since-build ABSENT"

# -----------------------------------------------------------------------------
head_ "6. Artefact : change-notes (ce que la revue Marketplace lira)"
B_CN_COUNT="$(grep -c '<change-notes>' <<< "$BUILT_XML" || true)"
B_CN_RAW="$(awk '/<change-notes>/{f=1} f{print} /<\/change-notes>/{if(f)exit}' <<< "$BUILT_XML")"
B_CN_TEXT="$(printf '%s' "$B_CN_RAW" | sed 's/.*<!\[CDATA\[//; s/\]\]>.*//' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
B_CN_CHARS="$(printf '%s' "$B_CN_TEXT" | wc -m | tr -d ' ')"
if [ "$B_CN_COUNT" = "1" ] && [ "${B_CN_CHARS:-0}" -ge 60 ]; then
  ok "change-notes dans le JAR : 1 element, $B_CN_CHARS caracteres"
else
  bad "change-notes dans le JAR : $B_CN_COUNT element(s), ${B_CN_CHARS:-0} caracteres"
fi
# Le source et l'artefact doivent dire la MEME chose : c'est le but de la
# correction du cycle 70 (le build n'injecte plus les notes).
# On compare des textes NORMALISES (espaces multiples et sauts de ligne
# collationnes en un seul) : le JAR reecrit l'indentation du CDATA, comparer
# les octets bruts donnerait un faux « DIVERGE ».
norm() { printf '%s' "$1" | tr '\n\t' '  ' | sed 's/  */ /g; s/^ //; s/ $//'; }
if [ "$(norm "$CN_TEXT")" = "$(norm "$B_CN_TEXT")" ]; then
  ok "le change-notes du JAR est identique a celui du source"
else
  bad "le change-notes du JAR DIVERGE de celui du source"
  diff <(printf '%s\n' "$(norm "$CN_TEXT")") <(printf '%s\n' "$(norm "$B_CN_TEXT")") | head -12 | sed 's/^/        /'
fi

# -----------------------------------------------------------------------------
head_ "6b. Artefact : description (la fiche Marketplace lit le SOURCE)"
# Meme risque que pour les change-notes : la description est ecrite a la main
# dans plugin.xml ET dans build.gradle.kts (le build.gradle_kts ecrase le
# source au build). Si les deux divergent, la fiche publiee ne sera pas celle
# qu'on relit -> ce controle rend la divergence IMPOSSIBLE.
D_RAW="$(awk '/<description>/{f=1} f{print} /<\/description>/{if(f)exit}' "$SRC_XML")"
D_TEXT="$(printf '%s' "$D_RAW" | sed 's/.*<!\[CDATA\[//; s/\]\]>.*//' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
D_CHARS="$(printf '%s' "$D_TEXT" | wc -m | tr -d ' ')"
B_D_RAW="$(awk '/<description>/{f=1} f{print} /<\/description>/{if(f)exit}' <<< "$BUILT_XML")"
B_D_TEXT="$(printf '%s' "$B_D_RAW" | sed 's/.*<!\[CDATA\[//; s/\]\]>.*//' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
if [ "${D_CHARS:-0}" -lt 120 ] || [ "${D_CHARS:-0}" -gt 900 ]; then
  bad "description du source : ${D_CHARS} caracteres (la Marketplace en exige 120 a 900)"
else
  ok "description du source : $D_CHARS caracteres (120-900)"
fi
if [ "$(norm "$D_TEXT")" = "$(norm "$B_D_TEXT")" ]; then
  ok "la description du JAR est identique a celle du source"
else
  bad "la description du JAR DIVERGE de celle du source"
  diff <(printf '%s\n' "$(norm "$D_TEXT")") <(printf '%s\n' "$(norm "$B_D_TEXT")") | head -12 | sed 's/^/        /'
fi
# La description doit annoncer le format « chemin [branche] » (revue du 27/10).
if grep -qE '\[branch\]|\[branche\]' <<< "$D_TEXT"; then
  ok "la description annonce le format « path [branch] »"
else
  bad "la description n'annonce pas le format « path [branch] »"
fi

# -----------------------------------------------------------------------------
head_ "7. Artefact : icones 40x40 (regle JetBrains « Plugin Icon File »)"
for ICON in pluginIcon.svg pluginIcon_dark.svg; do
  JAR_ICON="$JAR_DIR/META-INF/$ICON"
  if [ ! -f "$JAR_ICON" ]; then bad "META-INF/$ICON absent du JAR"; continue; fi
  SVG_ROOT="$(grep -oE '<svg[^>]*>' "$JAR_ICON" | head -1)"
  if grep -qE 'width="40"[[:space:]]+height="40"' <<< "$SVG_ROOT" \
     || grep -qE 'viewBox="[[:space:]]*0[[:space:]]+0[[:space:]]+40[[:space:]]+40"' <<< "$SVG_ROOT"; then
    ok "$ICON est 40x40"
  else
    bad "$ICON n'est pas 40x40 (racine : ${SVG_ROOT})"
  fi
  grep -qiE '<image|base64' "$JAR_ICON" \
    && bad "$ICON embarque un bitmap (interdit)" \
    || ok "$ICON n'embarque aucun bitmap"
done

# -----------------------------------------------------------------------------
head_ "8. Artefact : code et contenu du ZIP"
# ATTENTION : les .class sont dans le JAR interne, pas dans le ZIP. Chercher
# « .class » dans la liste du ZIP ne trouve RIEN : c'est la liste du JAR qu'il
# faut interroger.
JAR_ENTRIES="$(unzip -Z1 "$TMP_JAR" 2>/dev/null)"
CLASSES="$(grep -c '\.class$' <<< "$JAR_ENTRIES" || true)"
[ "${CLASSES:-0}" -ge 6 ] \
  && ok "$CLASSES classes dans le JAR" \
  || bad "$CLASSES classes dans le JAR (attendu >= 6)"
for C in com/tgglabs/branchbadge/GitHead.class \
         com/tgglabs/branchbadge/BranchDisplay.class \
         com/tgglabs/branchbadge/BranchWatchState.class \
         com/tgglabs/branchbadge/BranchProjectViewDecorator.class \
         com/tgglabs/branchbadge/BranchRefreshService.class \
         com/tgglabs/branchbadge/GitBranchChangeListener.class; do
  grep -qx "$C" <<< "$JAR_ENTRIES" \
    && ok "classe presente : $C" \
    || bad "classe MANQUANTE : $C"
done
grep -qiE 'CHANGELOG|README|\.md$' <<< "$ZIP_ENTRIES" \
  && bad "le ZIP embarque un markdown (interdit)" \
  || ok "le ZIP n'embarque aucun markdown"
JAR_HITS="$(grep -r -a -i -o -h -E "$FORBIDDEN_RE" \
             --include='*.xml' --include='*.properties' --include='*.svg' \
             --include='*.txt' --include='*.MF' "$JAR_DIR" 2>/dev/null | wc -l | tr -d ' ')"
[ "${JAR_HITS:-0}" = "0" ] \
  && ok "aucun residu interdit dans les ressources textuelles du JAR" \
  || bad "$JAR_HITS occurrence(s) interdite(s) dans le JAR"

# -----------------------------------------------------------------------------
head_ "8b. Artefact : le descripteur optionnel git4idea.xml (le defaut de la revue du 27/10)"
# La revue du 27/10 a refuse la version 0.0.1 parce que le descripteur
# declarait topic="git4idea.repo.GitRepository.GIT_REPO_CHANGE" : le nom d'un
# CHAMP, pas d'une classe. Le listener ne pouvait donc pas s'enregistrer.
# Ce controle regarde le descripteur REELLEMENT embarque dans le ZIP et exige
# le topic qui est une interface existante de l'IDE.
EXPECTED_TOPIC="git4idea.repo.GitRepositoryChangeListener"
EXPECTED_LISTENER="com.tgglabs.branchbadge.GitBranchChangeListener"
BUILT_DESC="$JAR_DIR/META-INF/git4idea.xml"
if [ ! -f "$BUILT_DESC" ]; then
  bad "META-INF/git4idea.xml ABSENT du JAR livre (le listener Git ne sera pas enregistre)"
else
  ok "META-INF/git4idea.xml present dans le JAR livre"
  BUILT_TOPIC="$(grep -o 'topic="[^"]*"' "$BUILT_DESC" | head -1 | sed 's/topic="//; s/"$//')"
  BUILT_LISTENER="$(grep -o 'class="[^"]*"' "$BUILT_DESC" | head -1 | sed 's/class="//; s/"$//')"
  [ "$BUILT_TOPIC" = "$EXPECTED_TOPIC" ] \
    && ok "topic = $BUILT_TOPIC" \
    || bad "topic = « $BUILT_TOPIC » (attendu « $EXPECTED_TOPIC » : c'est le defaut exact de la revue du 27/10)"
  [ "$BUILT_LISTENER" = "$EXPECTED_LISTENER" ] \
    && ok "listener = $BUILT_LISTENER" \
    || bad "listener = « $BUILT_LISTENER » (attendu « $EXPECTED_LISTENER »)"
fi
# ATTENTION : "$BUILT_XML" est le CONTENU du descripteur, pas un fichier.
# Ecrit comme "$BUILT_XML", grep le prenait pour un NOM DE FICHIER : le
# controle echouait TOUJOURS (code 2, « No such file or directory »), meme
# sur un artefact parfait — le pre-vol annoncait donc un echec qui n'existait
# pas. La forme correcte est le here-string <<< "$BUILT_XML", comme aux
# lignes de controle de l'identite plus haut.
grep -q '<depends optional="true" config-file="git4idea.xml">Git4Idea</depends>' <<< "$BUILT_XML" \
  && ok "plugin.xml du JAR enregistre bien le descripteur optionnel" \
  || bad "plugin.xml du JAR n'enregistre pas le descripteur optionnel (depends optional / config-file=git4idea.xml)"

# -----------------------------------------------------------------------------
head_ "9. Rapport"
echo "      ZIP     : $ZIP"
echo "      sha256  : $ZIP_SHA"
echo "      md5     : $ZIP_MD5"
if [ "$FAILED" -eq 0 ]; then
  printf '\033[32mPRE-VOL VERT\033[0m — 0 controle en echec.\n'
else
  printf '\033[31mPRE-VOL ROUGE\033[0m — %d controle(s) en echec. Ne pas publier.\n' "$FAILED"
fi
# La derniere ligne est le resume que le serveur attend.
printf 'nom OK (%s, %s car.) | change-notes : %s car.\n' \
  "$XML_NAME" "${#XML_NAME}" "$CN_CHARS"
exit $([ "$FAILED" -eq 0 ] && echo 0 || echo 1)
