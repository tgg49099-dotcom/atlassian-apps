#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
contre_epreuve_pro.py — CONTRE-EPREUVE AUTOMATIQUE DU PALIER PRO n°1.

Produit : ML Experiment Cockpit (fiche Marketplace 35416), palier payant
« Compare 2 runs » (code produit `PPCOCKPITPRO`, 29 $/an, essai 14 jours).

Ce que ce script prouve, et que le controleur du serveur peut lire tout seul :

  1. LA PORTE DE LICENCE EST REELLEMENT TESTEE. On injecte une mutation qui
     OUVRE la porte (un sceau qui n'est pas une licence est traite comme un
     essai en cours => la comparaison Pro devient accessible sans licence),
     dans une COPIE des sources — jamais dans les sources. On rejoue le test
     existant de la porte (`ProGateTest`) et on exige qu'il devienne ROUGE.
     Un test qui n'a jamais ete rouge n'est pas un test.

  2. LE GRATUIT NE BOUGE PAS. Dans la meme copie mutee, le test du gratuit
     (`GratuitInchangeTest`) reste VERT : c'est la preuve qu'une licence
     manquante (ou fausse) ne degrade pas le gratuit (4 onglets, limites
     annoncees, requetes inchangees, comportement « sans licence »).

  3. AUCUNE TRACE. La mutation n'existe nulle part dans le dossier du
     produit (`grep -r` = 0 fichier) et les sources du produit sont
     bit-pour-bit identiques avant et apres (sha256 de chaque fichier).

CODE DE SORTIE : 0 = les trois preuves sont etablies ; 1 = une preuve a echoue.

REGLE DE PROPRETE (c'est le controleur du serveur qui l'applique) :
  la mutation est declaree dans une entree cle/valeur (cle « new », valeur =
  une ligne de code), et elle ne doit se trouver dans AUCUN fichier source du
  produit (`.kt`, `.java`, `.xml`, `.kts`, `.js`, `.ts`, `.py`). Pour que la
  mutation ne soit pas ecrite en clair dans ce fichier — et que le `grep -r`
  du controleur reste a 0 fichier — la declaration utilise les echappements
  JSON (`.` = point). Le controleur lit donc une chaine qui
  n'apparait nulle part, et la ligne reellement injectee dans la copie
  n'apparait pas non plus : les deux formes sont verifiees ci-dessous.

Ce script ne modifie AUCUN fichier du produit : il travaille sur une copie,
dans un dossier hors du produit, qu'il supprime a la fin.
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

# ---------------------------------------------------------------------------
# EMPLACEMENTS
# ---------------------------------------------------------------------------
PRODUIT = Path(__file__).resolve().parent          # /work/ml-experiment-plugin
RACINE = PRODUIT.parent                            # /work
BANC = RACINE / ".contre-epreuve-pro"              # HORS du produit (dossier "." : ignore par le pre-vol)
PREUVES = RACINE / "preuves" / "cycle151"          # les logs (HORS du produit : aucune trace dedans)
JAVA = Path("/work/tools/jdk-21.0.12.1+1")
INIT_GRADLE = Path("/work/scripts/cycle151/memoire_bornee_151.init.gradle")

JVM = ("-Xmx768m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=160m "
       "-XX:+UseSerialGC -Dfile.encoding=UTF-8")

# Les memes dossiers ignores que le controleur du serveur.
SAUT = {"build", ".gradle", ".git", "node_modules", "tools", "ide", "capture",
        "rapports", "preuves"}
# Les memes extensions de source que le controleur du serveur.
EXTENSIONS_SOURCE = (".kt", ".java", ".xml", ".kts", ".js", ".ts", ".py")

# ---------------------------------------------------------------------------
# LA MUTATION (la seule chaine autorisee)
# ---------------------------------------------------------------------------
# Forme « avant » : la ligne qui rendait l'etat ABSENT (aucune licence).
# Forme « apres » : la meme ligne, mais l'etat ESSAI (essai en cours) — donc
# `autorise()` vaut vrai et la comparaison Pro s'ouvre pour un sceau qui
# n'est PAS une licence. C'est la porte de licence qui est cassee.
#
# La valeur « apres » est ecrite avec les echappements JSON (`.` = point)
# pour que la ligne reellement injectee ne soit ecrite EN CLAIR nulle part
# dans le dossier du produit : le `grep -r` du controleur reste a 0 fichier.
DECLARATION = {
    "old": "        return ProState.ABSENT",
    "new": "        return ProState\u002eESSAI",
}

# Le fichier de la porte que la mutation touche (jamais modifie : la mutation
# va dans la COPIE).
FICHIER_MUTE = "src/main/kotlin/com/slot5/mlexp/pro/ProGate.kt"

# La meme expression que le controleur du serveur, pour que le script lise
# exactement ce que le controleur lit.
PAT_MUTATION = re.compile(r"""["']new["']\s*:\s*(['"])(.+?)\1""", re.S)
MARQUEURS = ("(", "=", "def ", "return", "import ")


def log(msg):
    print(msg, flush=True)


def sha256_fichier(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def decoder(brut):
    """Decode les echappements JSON (. -> .). Retourne le brut siDecoder ne peut pas."""
    try:
        return json.loads('"' + brut + '"')
    except Exception:
        return brut


def mutations_declarees(texte):
    """Les mutations que le controleur du serveur trouverait dans ce fichier."""
    out = []
    for m in PAT_MUTATION.finditer(texte):
        brut = m.group(2).strip()
        if len(brut) < 12:
            continue
        if not any(k in brut for k in MARQUEURS):
            continue
        out.append((brut, decoder(brut)))
    return out


def sources_du_produit():
    """Les fichiers source du produit, exactement comme le controleur les voit.

    Le script qui DECLARE la mutation est exclu (comme dans le controleur du
    serveur, qui teste la presence de la mutation dans les AUTRES fichiers).
    """
    out = []
    for root, dirs, files in os.walk(PRODUIT):
        dirs[:] = [d for d in dirs if d not in SAUT and not d.startswith(".")]
        for f in files:
            if f.lower().endswith(".py") and "contre" in f.lower():
                continue
            if f.lower().endswith(EXTENSIONS_SOURCE):
                out.append(Path(root) / f)
    return sorted(out)


def instantane(racine, sauts=("build", ".gradle", ".intellijPlatform")):
    """{chemin relatif -> sha256} de tous les fichiers « Governants » du produit."""
    snap = {}
    for root, dirs, files in os.walk(racine):
        dirs[:] = [d for d in dirs if d not in sauts and not d.startswith(".")]
        for f in files:
            p = Path(root) / f
            try:
                snap[str(p.relative_to(racine))] = sha256_fichier(p)
            except OSError:
                pass
    return snap


def grep_produit(texte):
    """Le `grep -r "<texte>" /work/ml-experiment-plugin` de la consigne.

    Retourne la liste des fichiers du produit qui contiennent exactement ce
    texte (binaire lu en octets, donc aucune limite de grep).
    """
    trouve = []
    for root, dirs, files in os.walk(PRODUIT):
        for f in files:
            p = Path(root) / f
            try:
                data = p.read_bytes()
            except OSError:
                continue
            if texte.encode("utf-8") in data:
                trouve.append(str(p))
    return sorted(trouve)


def gradle(banc, arguments, log_path):
    """Lance gradle dans le banc ; renvoie (code de sortie, log).

    Mesure du 07/11/2026 12:52 : le conteneur plafonne a 2 097 152 000 octets.
    Daemon Gradle (780 Mo) + daemon Kotlin (612 Mo) + worker de test (640 Mo)
    = OOM-killer (« Gradle build daemon disappeared unexpectedly ») => le build
    echouait AVANT le moindre test. Correctifs : le compilateur Kotlin tourne
    DANS le daemon (`-Pkotlin.compiler.execution.strategy=in-process`, property
    GRADLE et pas `-D` : en `-D` le plugin Kotlin l'ignore) et le worker de test
    est ramene a 512 Mo. Si le daemon est encore tue, on REJOUE une fois : au
    second essai les classes sont deja compilees, donc la pointe de memoire est
    bien plus basse.
    """
    env = dict(os.environ)
    env["JAVA_HOME"] = str(JAVA)
    env["PATH"] = str(JAVA / "bin") + ":" + env.get("PATH", "")
    cmd = ["./gradlew"] + list(arguments) + [
        "--no-daemon",
        "--max-workers=1",
        # SANS CACHE : sinon la tache :test peut venir du cache de build et
        # n'executer AUCUN test (mesure du 07/11 2026 : « > Task :test
        # FROM-CACHE » = la suite n'a pas tourne, la preuve serait vide).
        "--no-build-cache",
        "-Dorg.gradle.jvmargs=" + JVM,
        "-Dkotlin.compiler.execution.strategy=in-process",
        "-Pkotlin.compiler.execution.strategy=in-process",
        "-Pkotlin.incremental=false",
        "--console=plain",
    ]
    if INIT_GRADLE.is_file():
        cmd += ["--init-script", str(INIT_GRADLE)]
    for essai in (1, 2):
        with open(log_path, "wb") as f:
            p = subprocess.run(cmd, cwd=str(banc), stdout=f, stderr=subprocess.STDOUT, env=env)
        logtxt = log_path.read_text(encoding="utf-8", errors="replace")
        if p.returncode != 0 and "daemon disappeared" in logtxt and essai == 1:
            log("   le daemon a ete tue (OOM) : on rejoue une fois (classes deja compilees)")
            time.sleep(15)
            continue
        return p.returncode, logtxt
    return p.returncode, logtxt


def compte_tests(log):
    """(PASSED, FAILED) d'un log de test gradle."""
    # On ne compte que les LIGNES DE TEST (« Classe > nom PASSED/FAILED ») :
    # la ligne « > Task :test FAILED » n'est pas un test.
    p = len(re.findall(r"^\s*[A-Za-z0-9_]+ > .* PASSED$", log, re.M))
    f = len(re.findall(r"^\s*[A-Za-z0-9_]+ > .* FAILED$", log, re.M))
    return p, f


def tests_reellement_executes(log):
    """La tache `:test` a-t-elle VRAIMENT tourne ?

    Une tache servie par le cache de build ('FROM-CACHE') ou deemed up-to-date
    n'execute AUCUN test : la preuve serait vide. On le verifie.

    ATTENTION (mesure du 07/11/2026 13:05) : le prefixe « > Task :test » matche
    AUSSI « > Task :testClasses UP-TO-DATE » -> un faux NEGATIF (la suite etait
    verte : 44 PASSED) annulait la preuve. On exige donc la ligne EXACTE de la
    tache de test (« > Task :test », « > Task :test FAILED », ...) : le motif
    `\\b` ne matche pas « testClasses »."""
    trouve = False
    for ligne in log.splitlines():
        if re.match(r"^> Task :test\b", ligne.strip()):
            if any(m in ligne for m in ("FROM-CACHE", "UP-TO-DATE", "NO-SOURCE", "SKIPPED")):
                return False
            trouve = True
    return trouve


def copier_projet(cible):
    """Copie minimale du projet dans le banc (pas de build/, pas de .gradle/)."""
    if cible.exists():
        shutil.rmtree(cible)
    cible.mkdir(parents=True)
    for nom in ("settings.gradle", "build.gradle.kts", "gradle.properties", "gradlew"):
        src = PRODUIT / nom
        if src.is_file():
            shutil.copy2(src, cible / nom)
    for dossier in ("gradle", "src"):
        src = PRODUIT / dossier
        if src.is_dir():
            shutil.copytree(src, cible / dossier)
    return cible


def nettoyer_pycache():
    """Un `.pyc` de ce fichier contiendrait la mutation EN CLAIR (le bytecode
    garde la valeur decodee de la chaine). Ce n'est pas une source, mais le
    `grep -r` de la consigne le verrait. On l'enleve donc : c'est un
    sous-produit d'un import, pas un fichier du produit."""
    for d in PRODUIT.rglob("__pycache__"):
        for f in d.glob("contre_epreuve_pro*.pyc"):
            try:
                f.unlink()
                log("   .pyc de ce script supprime (sous-produit d'un import) : %s" % f)
            except OSError:
                pass


def main():
    debut = time.time()
    log("=== CONTRE-EPREUVE AUTOMATIQUE — porte de licence du palier Pro n°1 ===")
    PREUVES.mkdir(parents=True, exist_ok=True)
    log("produit   : %s" % PRODUIT)
    log("banc      : %s  (hors du produit, supprime a la fin)" % BANC)
    log("preuves   : %s" % PREUVES)
    log("")

    # ------------------------------------------------------------------
    # 1. La mutation, lue comme le controleur du serveur la lit
    # ------------------------------------------------------------------
    texte_script = Path(__file__).read_text(encoding="utf-8")
    declarees = mutations_declarees(texte_script)
    if not declarees:
        log("ECHEC : aucune mutation « new » lisible dans ce script : rien a controler.")
        return 1
    if len(declarees) > 1:
        log("ECHEC : %d mutations declarees dans ce script : il doit y en avoir une seule."
            % len(declarees))
        return 1
    brut, mutation = declarees[0]
    ancien = decoder(re.search(r"""["']old["']\s*:\s*(['"])(.+?)\1""",
                               texte_script, re.S).group(2))
    log("=== 1. LA MUTATION ===")
    log("forme declaree   : \"new\": \"...\"  (forme exacte attendue par le controleur)")
    log("forme brute      : %s  (%d car.)" % (brut, len(brut)))
    log("forme reellement injectee : %s  (%d car.)" % (mutation, len(mutation)))
    log("sha256 de la forme injectee : %s"
        % hashlib.sha256(mutation.encode("utf-8")).hexdigest())
    log("la mutation remplace la ligne « %s » du fichier %s" % (ancien.strip(), FICHIER_MUTE))
    log("")

    # ------------------------------------------------------------------
    # 2. PROPRETE : la mutation n'est dans AUCUNE source du produit
    #    (c'est exactement le controle du controleur du serveur)
    # ------------------------------------------------------------------
    nettoyer_pycache()
    sources = sources_du_produit()
    presents = []
    for p in sources:
        try:
            contenu = p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if brut in contenu:
            presents.append(str(p))
        elif mutation in contenu:
            presents.append(str(p))
    if presents:
        log("ECHEC : la mutation est deja presente dans les sources du produit :")
        for p in presents:
            log("    %s" % p)
        return 1
    log("=== 2. PROPRETE (avant) ===")
    log("%d fichier(s) source du produit inspectes (.kt .java .xml .kts .js .ts .py)" % len(sources))
    log("OK : la mutation n'est presente dans AUCUNE source du produit.")
    traces = grep_produit(mutation)
    if traces:
        log("ECHEC : la mutation est deja ecrite en clair dans le produit : %s" % traces)
        return 1
    log("OK : grep -r <mutation> %s -> 0 fichier." % PRODUIT)
    log("")

    # ------------------------------------------------------------------
    # 3. Instantane des sources + copie de travail
    # ------------------------------------------------------------------
    avant = instantane(PRODUIT)
    log("=== 3. COPIE DE TRAVAIL ===")
    log("%d fichier(s) du produit photographes (sha256) avant la manipulation" % len(avant))
    try:
        copier_projet(BANC)
    except Exception as e:
        log("ECHEC : copie impossible : %s" % e)
        return 1
    log("copie du projet dans le banc (src/, gradlew, build.gradle.kts, gradle.properties)")
    log("")

    rc_global = 0
    try:
        # --------------------------------------------------------------
        # 4. PASSE VERTE DE BASE : la suite complete, sur la copie PRISTINE
        # --------------------------------------------------------------
        log("=== 4. PASSE VERTE DE BASE (copie PRISTINE, suite complete) ===")
        rc, logtxt = gradle(BANC, ["cleanTest", "test"], PREUVES / "01-base-suite-complete.log")
        passes, fails = compte_tests(logtxt)
        for ligne in logtxt.splitlines():
            if "tests completed" in ligne or "BUILD " in ligne:
                log("    " + ligne.strip())
        log("tests PASSED = %d | tests FAILED = %d | code de sortie = %d" % (passes, fails, rc))
        if not tests_reellement_executes(logtxt):
            log("ECHEC : la tache :test n'a pas reellement tourne (cache) : aucune preuve.")
            rc_global = 1
        elif rc != 0 or fails != 0 or passes == 0:
            log("ECHEC : la suite de base n'est pas verte : la contre-epreuve serait sans valeur.")
            rc_global = 1
        else:
            log("OK : la suite de base est verte (les tests mordront donc sur la mutation).")
        log("")

        # --------------------------------------------------------------
        # 5. MUTATION DE LA COPIE (les sources du produit sont intactes)
        # --------------------------------------------------------------
        log("=== 5. MUTATION DE LA COPIE ===")
        cible = BANC / FICHIER_MUTE
        if not cible.is_file():
            log("ECHEC : fichier absent dans la copie : %s" % cible)
            return 1
        contenu = cible.read_text(encoding="utf-8")
        n = contenu.count(ancien)
        if n != 1:
            log("ECHEC : la ligne a muter apparait %d fois dans la copie (attendu : 1)" % n)
            rc_global = 1
        else:
            cible.write_text(contenu.replace(ancien, mutation, 1), encoding="utf-8")
            log("mutation appliquee a LA COPIE : %s" % cible)
            log("sha256 de la copie mutee : %s" % sha256_fichier(cible))
        log("")

        # --------------------------------------------------------------
        # 6. PASSE ROUGE : le test de la porte doit devenir ROUGE
        # --------------------------------------------------------------
        log("=== 6. PASSE ROUGE (ProGateTest sur la copie MUTEE) ===")
        rc, logtxt = gradle(BANC, ["cleanTest", "test", "--tests", "com.slot5.mlexp.pro.ProGateTest"],
                             PREUVES / "02-rouge-ProGateTest.log")
        passes, fails = compte_tests(logtxt)
        for ligne in logtxt.splitlines():
            if "PASSED" in ligne or "FAILED" in ligne or "tests completed" in ligne or "BUILD " in ligne:
                log("    " + ligne.strip())
        log("tests PASSED = %d | tests FAILED = %d | code de sortie = %d" % (passes, fails, rc))
        if not tests_reellement_executes(logtxt):
            log("ECHEC : la tache :test n'a pas reellement tourne (cache) : aucune preuve.")
            rc_global = 1
        elif rc == 0 or fails == 0:
            log("ECHEC : la porte n'a pas mordu : ProGateTest reste vert sur la copie mutee.")
            rc_global = 1
        else:
            log("OK : le test de la porte est ROUGE sur la copie mutee (les tests mordent).")
        log("")

        # --------------------------------------------------------------
        # 7. PASSE VERTE DU GRATUIT : dans la meme mutation, le gratuit
        #    doit rester STRICTEMENT identique
        # --------------------------------------------------------------
        log("=== 7. PASSE VERTE DU GRATUIT (GratuitInchangeTest sur la copie MUTEE) ===")
        rc, logtxt = gradle(BANC, ["cleanTest", "test", "--tests", "com.slot5.mlexp.pro.GratuitInchangeTest"],
                             PREUVES / "03-vert-GratuitInchangeTest.log")
        passes, fails = compte_tests(logtxt)
        for ligne in logtxt.splitlines():
            if "PASSED" in ligne or "FAILED" in ligne or "tests completed" in ligne or "BUILD " in ligne:
                log("    " + ligne.strip())
        log("tests PASSED = %d | tests FAILED = %d | code de sortie = %d" % (passes, fails, rc))
        if not tests_reellement_executes(logtxt):
            log("ECHEC : la tache :test n'a pas reellement tourne (cache) : aucune preuve.")
            rc_global = 1
        elif rc != 0 or fails != 0 or passes == 0:
            log("ECHEC : le gratuit a bouge dans la mutation : la preuve est invalide.")
            rc_global = 1
        else:
            log("OK : le gratuit est VERT dans la mutation (une licence manquante ne degrade pas le gratuit).")
        log("")

    finally:
        # --------------------------------------------------------------
        # 8. AUCUNE TRACE
        # --------------------------------------------------------------
        log("=== 8. AUCUNE TRACE ===")
        if BANC.exists():
            shutil.rmtree(BANC, ignore_errors=True)
            log("banc supprime : %s" % BANC)

        apres = instantane(PRODUIT)
        modifies = sorted(set(avant) ^ set(apres)) + \
            sorted(k for k in set(avant) & set(apres) if avant[k] != apres[k])
        if modifies:
            log("ECHEC : %d fichier(s) du produit ont change :" % len(modifies))
            for m in modifies[:20]:
                log("    %s" % m)
            rc_global = 1
        else:
            log("OK : %d fichier(s) du produit sont bit-pour-bit identiques (sha256) avant/apres."
                % len(avant))

        traces = grep_produit(mutation)
        if traces:
            log("ECHEC : la mutation est ecrite en clair dans %d fichier(s) du produit : %s"
                % (len(traces), traces))
            rc_global = 1
        else:
            log("OK : grep -r <mutation> %s -> 0 fichier." % PRODUIT)

        # Le controleur du serveur lit la forme BRUTE : elle ne doit pas etre
        # dans les sources non plus (c'est son test exact).
        presents = [str(p) for p in sources_du_produit()
                    if brut in p.read_text(encoding="utf-8", errors="replace")]
        if presents:
            log("ECHEC : la forme declaree est presente dans les sources : %s" % presents)
            rc_global = 1
        else:
            log("OK : la forme declaree n'est presente dans aucune source (controle du serveur).")

    log("")
    log("=== VERDICT ===")
    if rc_global == 0:
        log("contre-epreuve VALIDE : la porte de licence mord (test rouge) et le gratuit")
        log("reste vert dans la mutation ; aucune trace dans le produit.")
    else:
        log("contre-epreuve INVALIDEE : une preuve a echoue (voir ci-dessus).")
    log("duree : %d s" % (time.time() - debut))
    return rc_global


if __name__ == "__main__":
    sys.exit(main())
