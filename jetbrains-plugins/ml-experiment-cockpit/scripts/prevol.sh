#!/usr/bin/env bash
#
# prevol.sh — pre-flight checks for the ML Experiment Cockpit plugin (slot 5).
#
# Run it from the plugin root, AFTER a build:
#     ./gradlew buildPlugin && ./scripts/prevol.sh
#
# It exists because a check that only lives in a report does not repeat itself.
# Every rule below is enforced here, on the BUILT artefact where it matters:
# what JetBrains will actually read is the plugin.xml inside the JAR inside the
# ZIP, not the sources we edited.
#
# Exit code 0 = all hard checks pass. Non-zero = at least one hard check failed.
#
# NOTE ON THE ZIP GLOB: build/distributions/ intentionally holds more than one
# ZIP (ml-experiment-cockpit-0.0.11.zip is the protected Sunday safety net and
# must never be deleted). `unzip -p build/distributions/*.zip` would therefore
# read the wrong archive, so this script pins the archive and the inner JAR to
# the version declared in gradle.properties.
#
# Last significant change: 2026-10-22 — added the built-JAR control (jury
# finding #4 of 2026-10-19): the plugin.xml inside the built JAR is scanned for
# placeholder / withdrawn-feature residue, not the sources.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

FAILED=0
WARNED=0

ok()    { printf '  \033[32mOK\033[0m    %s\n' "$1"; }
bad()   { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILED=$((FAILED + 1)); }
warn()  { printf '  \033[33mWARN\033[0m  %s\n' "$1"; WARNED=$((WARNED + 1)); }
head_() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# `grep -c` prints 0 AND exits 1 when nothing matches. We want the number and do
# not care about the code, so every count below ends with `|| true` — using
# `|| echo 0` instead would append a second "0" and break the comparison.
count_file() { grep -a -c -iE "$FORBIDDEN_RE" "$1" 2>/dev/null || true; }
count_text() { grep -c -iE "$FORBIDDEN_RE" <<< "$1" 2>/dev/null || true; }

# Public identifier, frozen by the owner's instruction of 2026-10-22.
EXPECTED_ID="com.tgglabs.mlexp"
EXPECTED_VENDOR="Tgg Labs"
# Residue that must never reach a published plugin.xml.
FORBIDDEN_RE='slot5 vendor|example\.invalid|placeholder|replicate'

head_ "0. Environment"
VERSION="$(grep -oP '^pluginVersion\s*=\s*\K\S+' gradle.properties 2>/dev/null)"
if [ -n "$VERSION" ]; then
  ok "gradle.properties declares pluginVersion = $VERSION"
else
  bad "cannot read pluginVersion from gradle.properties"
  echo "cannot continue without a version" >&2
  exit 2
fi

# The IDE ceiling is read from gradle.properties — the SAME file the build
# reads — so this check can never drift from what is actually shipped.
# It was hard-coded to 262.* until 24/10/2026 (cycle 51): the stable IDE was
# 2026.2 (build 262) and 2026.3 (build 263) was still EAP.
UNTIL_BUILD="$(grep -oP '^pluginUntilBuild\s*=\s*\K\S+' gradle.properties 2>/dev/null)"
if [ -z "$UNTIL_BUILD" ]; then
  bad "cannot read pluginUntilBuild from gradle.properties"
  exit 2
fi
# 263.* -> 263\.\*  (the dot and the star are regexp metacharacters)
# (no extra "OK" line here: the number of hard controls is checked against the
#  hors-ligne bench — 33 as of the 2026-10-24 cycle 54, which added the 6 hard
#  icon controls of section 4.)
UNTIL_RE="$(printf '%s' "$UNTIL_BUILD" | sed 's/[.[\*^$]/\\&/g')"

ZIP="build/distributions/ml-experiment-cockpit-${VERSION}.zip"
INNER_JAR_ENTRY="ml-experiment-cockpit/lib/ml-experiment-cockpit-${VERSION}.jar"

head_ "1. Built artefacts present"
if [ -f "$ZIP" ]; then
  ok "$ZIP exists ($(stat -c%s "$ZIP") bytes)"
else
  bad "$ZIP is missing — run ./gradlew buildPlugin first"
  exit 2
fi

# Listings are captured into variables on purpose: piping into `grep -q` makes
# grep exit early, the upstream process dies of SIGPIPE, and `set -o pipefail`
# would then report failure for a check that actually passed.
ZIP_ENTRIES="$(unzip -Z1 "$ZIP" 2>/dev/null)"
# Exactly one inner plugin JAR must match, or `unzip -p` would concatenate
# several archives into one stream and the greps below would read binary noise.
INNER_COUNT="$(grep -c "^${INNER_JAR_ENTRY}\$" <<< "$ZIP_ENTRIES" || true)"
if [ "$INNER_COUNT" = "1" ]; then
  ok "inner plugin JAR resolves uniquely: $INNER_JAR_ENTRY"
else
  bad "expected exactly 1 entry '$INNER_JAR_ENTRY', found $INNER_COUNT"
  exit 2
fi

JAR_SHA="$(sha256sum "$ZIP" | cut -d' ' -f1)"
ok "sha256($ZIP) = $JAR_SHA"

head_ "2. CONTROL ON THE BUILT JAR (jury #4, 2026-10-19)"
# Unzip the plugin JAR out of the distribution ZIP, then read the plugin.xml
# that JetBrains will load. The sources are not evidence of what ships.
TMP_JAR="$(mktemp /tmp/p.XXXXXX.jar)"
trap 'rm -f "$TMP_JAR"' EXIT
if unzip -p "$ZIP" "$INNER_JAR_ENTRY" > "$TMP_JAR" 2>/dev/null && [ -s "$TMP_JAR" ]; then
  ok "extracted ${TMP_JAR##*/} ($(stat -c%s "$TMP_JAR") bytes)"
else
  bad "could not extract $INNER_JAR_ENTRY from $ZIP"
  exit 2
fi

BUILT_XML="$(unzip -p "$TMP_JAR" META-INF/plugin.xml 2>/dev/null)"
if [ -z "$BUILT_XML" ]; then
  bad "no META-INF/plugin.xml inside the built JAR"
  exit 2
fi

# The control itself: must print 0.
HITS="$(count_text "$BUILT_XML")"
echo "      \$ unzip -p $ZIP '$INNER_JAR_ENTRY' > /tmp/p.jar"
echo "      \$ unzip -p /tmp/p.jar META-INF/plugin.xml | grep -ciE 'slot5 vendor|example.invalid|placeholder|replicate'"
echo "      $HITS"
if [ "$HITS" = "0" ]; then
  ok "built plugin.xml is free of placeholder/withdrawn-feature residue"
else
  bad "built plugin.xml contains $HITS forbidden pattern(s):"
  grep -inE "$FORBIDDEN_RE" <<< "$BUILT_XML" | sed 's/^/        /'
fi

# Same scan across the rest of the JAR's TEXTUAL resources, to catch residue in
# a file nobody thought to check. Two deliberate choices:
#   - the JAR entries are DEFLATE-compressed, so grepping the raw .jar bytes
#     finds nothing and would report a clean bill of health it never earned;
#     we extract first, then scan the real files.
#   - compiled .class files are excluded on purpose. The constant pool keeps
#     every private field name, so `placeholderColor` (the grey used when a
#     chart has no data to draw) would read as a "placeholder" forever without
#     being anything a user can see on the listing page.
JAR_DIR="$(mktemp -d /tmp/pj.XXXXXX)"
trap 'rm -f "$TMP_JAR"; rm -rf "$JAR_DIR"' EXIT
unzip -q -o "$TMP_JAR" -d "$JAR_DIR" 2>/dev/null
TEXT_INCLUDES=(--include='*.xml' --include='*.properties' --include='*.html'
               --include='*.txt' --include='*.svg' --include='*.MF')
JAR_HITS="$(grep -r -a -i -o -h -E "$FORBIDDEN_RE" "${TEXT_INCLUDES[@]}" "$JAR_DIR" 2>/dev/null | wc -l | tr -d ' ')"
echo "      whole-JAR scan (extracted text resources, .class excluded): $JAR_HITS occurrence(s)"
if [ "$JAR_HITS" = "0" ]; then
  ok "no forbidden pattern in any textual resource of the built JAR"
else
  bad "built JAR contains $JAR_HITS forbidden occurrence(s) outside plugin.xml"
  grep -r -a -i -o -h -E "$FORBIDDEN_RE" "${TEXT_INCLUDES[@]}" "$JAR_DIR" 2>/dev/null | sort | uniq -c | sed 's/^/        /'
fi

head_ "3. Built plugin.xml identity"
grep -q "<id>${EXPECTED_ID}</id>" <<< "$BUILT_XML" \
  && ok "id = $EXPECTED_ID" \
  || bad "id is not $EXPECTED_ID"
grep -q "<version>${VERSION}</version>" <<< "$BUILT_XML" \
  && ok "version = $VERSION" \
  || bad "version is not $VERSION"
# Gradle rewrites <vendor> with the email/url attributes, so match the text node
# only: the real tag looks like <vendor email="…" url="…">Tgg Labs</vendor>.
grep -qE ">[[:space:]]*${EXPECTED_VENDOR}[[:space:]]*</vendor>" <<< "$BUILT_XML" \
  && ok "vendor = $EXPECTED_VENDOR" \
  || bad "vendor is not $EXPECTED_VENDOR"
grep -qE "until-build=\"${UNTIL_RE}\"" <<< "$BUILT_XML" \
  && ok "until-build = $UNTIL_BUILD" \
  || bad "until-build is not $UNTIL_BUILD"

# The change-notes must describe the release that actually ships, once.
#
# 2026-10-23 (cycle 42): the rule used to hard-code the 0.0.12 title
# "— first public release". That made the check fail for any later version
# whose notes legitimately say something else, i.e. it tested the wording of
# one release instead of the invariant. The invariant is:
#   - exactly one <h3> entry (the IDE shows one block),
#   - whose text starts with the version that actually ships,
#   - and which lists at least one <li> (an empty block tells the user nothing).
# The title is therefore free-form; the coupling to $VERSION is not.
CN_ENTRIES="$(grep -coE "<h3>[^<]*</h3>" <<< "$BUILT_XML" || true)"
CN_TITLE="$(grep -oE "<h3>[^<]*</h3>" <<< "$BUILT_XML" | head -1 || true)"
CN_BODY="$(sed -n 's/.*<change-notes>.*//p' <<< "$BUILT_XML" || true)"
CN_LI="$(grep -coE "<li>[^<]" <<< "$BUILT_XML" || true)"
if [ "$CN_ENTRIES" = "1" ] \
   && grep -qE "^<h3>${VERSION} — .+</h3>$" <<< "$CN_TITLE" \
   && [ "$CN_LI" -ge 1 ]; then
  ok "change-notes: single entry '${CN_TITLE}' with $CN_LI bullet(s)"
else
  bad "change-notes must hold exactly 1 <h3> titled '${VERSION} — <texte>' with at least one <li> (found $CN_ENTRIES <h3>, $CN_LI <li>)"
fi
grep -q "0\.0\.11" <<< "$BUILT_XML" \
  && bad "built plugin.xml still mentions 0.0.11, which was never published" \
  || ok "no mention of the never-published 0.0.11"

head_ "4. Icon shipped in the JAR"
# Regle JetBrains « Plugin Icon File » (doc officielle) :
#   - le SVG est dans META-INF/ du JAR du plugin, en 40x40 ;
#   - pas d'image bitmap embarquee (donc ni <image ...>, ni base64) ;
#   - la variante sombre se nomme <icone>_dark.svg.
#
# Ces controles portent sur le FICHIER DU JAR (deja extrait dans $JAR_DIR),
# pas sur la source : c'est celui que JetBrains lit.
#
# 24/10/2026 (cycle 54) : le jury 6868 a reproche a ce pre-vol de ne
# kontroler que la PRESENCE des SVG, et de tolérer n'importe quelle taille.
# Les deux controles sont maintenant DURS : un SVG de 32x32 fait echouer le
# pre-vol (contre-epreuve demandee par la mere, rejouee au cycle 54).
JAR_ENTRIES="$(unzip -Z1 "$TMP_JAR" 2>/dev/null)"
for ICON in pluginIcon.svg pluginIcon_dark.svg; do
  if grep -qx "META-INF/$ICON" <<< "$JAR_ENTRIES"; then
    ok "META-INF/$ICON is inside the built JAR"
  else
    bad "META-INF/$ICON is MISSING from the built JAR"
    continue
  fi
  if [ -f "src/main/resources/META-INF/$ICON" ]; then
    DIM="$(grep -oE 'width="[0-9]+" height="[0-9]+"' "src/main/resources/META-INF/$ICON" | head -1)"
    ok "src/main/resources/META-INF/$ICON present ($DIM)"
  else
    bad "src/main/resources/META-INF/$ICON is missing"
  fi

  # --- preuve que le SVG est bien DANS le JAR ------------------------------
  JAR_ICON="$JAR_DIR/META-INF/$ICON"
  if [ -f "$JAR_ICON" ]; then
    ok "META-INF/$ICON extracted from the built JAR ($(stat -c%s "$JAR_ICON") bytes, sha256=$(sha256sum "$JAR_ICON" | cut -c1-12))"
  else
    bad "META-INF/$ICON is listed in the JAR but cannot be extracted from it"
    continue
  fi

  # --- dimension 40x40 (DUR) ----------------------------------------------
  # On lit la racine <svg …> et ELLE SEULE : un attribut width="40" posé sur
  # une forme interieure ne dit rien de la taille de la toile. Le regex doit
  # porter sur la racine, sinon un 32x32 contenant un rectangle 40x40
  # passerait.
  SVG_ROOT="$(grep -oE '<svg[^>]*>' "$JAR_ICON" | head -1)"
  if [ -z "$SVG_ROOT" ]; then
    bad "META-INF/$ICON has no <svg> root element"
  elif grep -qE 'width="40"[[:space:]]+height="40"' <<< "$SVG_ROOT" \
     || grep -qE 'viewBox="[[:space:]]*0[[:space:]]+0[[:space:]]+40[[:space:]]+40"' <<< "$SVG_ROOT"; then
    ok "META-INF/$ICON is 40x40 (root <svg> width/height or viewBox)"
  else
    bad "META-INF/$ICON is NOT 40x40 — JetBrains requires a 40x40 icon (root: ${SVG_ROOT})"
  fi

  # --- pas de bitmap embarque (DUR) ---------------------------------------
  if grep -qiE '<image|base64' "$JAR_ICON"; then
    bad "META-INF/$ICON embeds a bitmap (<image> or base64) — JetBrains forbids it"
    grep -oiE '.{0,40}(<image|base64).{0,40}' "$JAR_ICON" | head -2 | sed 's/^/        /'
  else
    ok "META-INF/$ICON embeds no bitmap (no <image>, no base64)"
  fi
done

head_ "5. Code surface (regression guards from cycles 15-18)"
# 82 -> 89 au cycle 41 : le correctif MLflow a ajoute Json.kt (Json, JsonParser,
# JsonParser$P, JsonException et les 6 sous-classes fermees de Json$*). Le
# compte exact reste un garde-fou dur : une classe qui disparaitrait par
# megarde doit faire echouer le pre-vol, pas passer inapercue.
#
# 89 -> 82 au cycle 43 : le port d'`OptunaClient` et de `WandbClient` sur le
# parseur JSON strict a supprime exactement les 7 classes qui ne servaient
# qu'aux regex, et rien d'autre :
#   OptunaClient$Companion          (les 9 regex etaient dans le companion)
#   OptunaClient$lastDatetime$matches$1
#   OptunaClient$parseTrials$completes$1 / $completes$2
#   OptunaClient$parseTrials$starts$1   / $starts$2
#   WandbClient$Companion          (idem pour W&B)
# Le port n'a ajoute AUCUNE classe (verifie par diff des deux JAR).
#
# 82 -> 83 au cycle 48 : le correctif d'affichage ajoute EXACTEMENT une
# classe, `NodeTreeCellRenderer` (le rendu de l'arbre), et rien d'autre.
# Verifie par diff des listes d'entrees des JAR 0.0.14 et 0.0.15 :
#   + com/slot5/mlexp/NodeTreeCellRenderer.class
#   (aucune suppression, aucune autre addition). Le toString() lisible est
#   porte par des methodes de `MlflowToolWindowPanel`, pas par une classe.
#
# Le compte seul peut etre trompe par une compensation (une classe disparue,
# une ajoutee ailleurs) : le controle "classes obligatoires" ci-dessous a donc
# ete ajoute. Une classe qui disparait par megarde fait toujours echouer le
# pre-vol.
CLASSES="$(grep -c '\.class$' <<< "$JAR_ENTRIES" || true)"
[ "$CLASSES" = "83" ] && ok "83 .class files in the JAR" || bad "expected 83 .class files, found $CLASSES"

MANDATORY_CLASSES="com/slot5/mlexp/Json.class
com/slot5/mlexp/JsonParser.class
com/slot5/mlexp/JsonException.class
com/slot5/mlexp/MlflowClient.class
com/slot5/mlexp/MlflowException.class
com/slot5/mlexp/OptunaClient.class
com/slot5/mlexp/OptunaException.class
com/slot5/mlexp/WandbClient.class
com/slot5/mlexp/WandbException.class
com/slot5/mlexp/Study.class
com/slot5/mlexp/StudySummary.class
com/slot5/mlexp/Trial.class
com/slot5/mlexp/Sweep.class
com/slot5/mlexp/SweepSummary.class
com/slot5/mlexp/SweepBestRun.class
com/slot5/mlexp/MlflowToolWindowPanel.class
com/slot5/mlexp/NodeTreeCellRenderer.class
com/slot5/mlexp/OptunaToolWindowPanel.class
com/slot5/mlexp/SweepToolWindowPanel.class
com/slot5/mlexp/ModelRegistryToolWindowPanel.class
com/slot5/mlexp/MetricsChartPanel.class
com/slot5/mlexp/MlflowSettingsImpl.class"
MISSING=0
while read -r C; do
  [ -z "$C" ] && continue
  if ! grep -qx "$C" <<< "$JAR_ENTRIES"; then
    bad "missing mandatory class in the built JAR: $C"
    MISSING=$((MISSING + 1))
  fi
done <<< "$MANDATORY_CLASSES"
[ "$MISSING" = "0" ] && ok "all 22 mandatory classes present in the JAR"
if grep -iE 'Deploy|Replicate' <<< "$JAR_ENTRIES" >/dev/null; then
  bad "withdrawn Deploy/Replicate classes are back in the JAR"
  grep -iE 'Deploy|Replicate' <<< "$JAR_ENTRIES" | sed 's/^/        /'
else
  ok "0 Deploy/Replicate class (feature withdrawn in 0.0.8)"
fi

head_ "6. Public metadata sources"
for F in src/main/resources/META-INF/plugin.xml build.gradle.kts gradle.properties LICENSE; do
  N="$(count_file "$F")"
  [ "$N" = "0" ] && ok "$F clean" || bad "$F has $N forbidden pattern(s)"
done
grep -q "id = \"${EXPECTED_ID}\"" build.gradle.kts \
  && ok "build.gradle.kts declares id = $EXPECTED_ID" \
  || bad "build.gradle.kts does not declare id = $EXPECTED_ID"
grep -q "pluginUntilBuild = ${UNTIL_RE}" gradle.properties \
  && ok "gradle.properties keeps the $UNTIL_BUILD ceiling" \
  || bad "gradle.properties no longer declares a $UNTIL_BUILD ceiling"

head_ "7. Informational — repo docs (not shipped in the ZIP)"
for F in CHANGELOG.md README.md; do
  N="$(count_file "$F")"
  [ "$N" = "0" ] && ok "$F clean" || warn "$F mentions withdrawn/historical terms ($N) — expected, they are internal history and are NOT in the ZIP"
done
if grep -qiE 'CHANGELOG|README|\.md$' <<< "$ZIP_ENTRIES"; then
  bad "the ZIP ships a markdown file — internal paths would become public"
else
  ok "the ZIP ships no markdown: only lib/*.jar"
fi

head_ "8. Report"
echo "      ZIP       : $ZIP"
echo "      sha256    : $JAR_SHA"
echo "      JAR scan  : plugin.xml=$HITS, whole JAR=$JAR_HITS (must both be 0)"
echo
if [ "$FAILED" -eq 0 ]; then
  printf '\033[32mPRE-VOL GREEN\033[0m — %d failed check(s), %d warning(s).\n' "$FAILED" "$WARNED"
  exit 0
fi
printf '\033[31mPRE-VOL RED\033[0m — %d failed check(s), %d warning(s). Do not publish.\n' "$FAILED" "$WARNED"
exit 1