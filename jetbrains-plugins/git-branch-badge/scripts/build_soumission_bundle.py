# -*- coding: utf-8 -*-
"""Construit le dossier de soumission (rejouable : le gabarit est dans scripts/) pour Tgg (plugin + guide + icone + captures).
Le md5 du ZIP est injecte dans le guide : impossible d'envoyer un guide perime.
Usage : /usr/bin/python3 -I /work/produits/BrancheProjectView/scripts/build_soumission_bundle.py

GARDE-FOU DE PUBLICATION (consigne de la mere du 27/10) :
ce script REFUSE de produire une version tant que la publication n'est pas
explicitement decidee :
  (a) une REVUE du jury pour la version courante : un fichier
      /work/REVUE-JURY/<id>.md contenant « revue: OK » ET la version ;
  (b) une APPROBATION de la mere : /work/APPROBATION-TGG.md contenant la
      version ET le mot APPROUVE.
Chaque refus est ECRIT dans /rapports/garde_fou_publication.log (avec la
raison) ; un garde-fou dont le refus n'est pas trace ne prouve rien.
Les 2 chemins sont FIGES : pas de variable d'environnement, pas de contournement.
"""
import glob, hashlib, os, re, sys, zipfile

BASE = "/work/produits/BrancheProjectView"
DIST = BASE + "/dist"
GUIDE_TEMPLATE = BASE + "/scripts/LISEZMOI-TGG.template.md"
GUIDE_FINAL = DIST + "/LISEZMOI-TGG.md"
BUNDLE = DIST + "/SOUMISSION-GIT-BRANCH-BADGE.zip"

# --- garde-fou de publication -------------------------------------------------
GUARDE_LOG = "/rapports/garde_fou_publication.log"
REVUE_DIR = "/work/REVUE-JURY"
APPROBATION_FILE = "/work/APPROBATION-TGG.md"


def garde_log(msg):
    """Ecrit une ligne de garde-fou (jamais silencieuse)."""
    try:
        import datetime
        stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        with open(GUARDE_LOG, "a", encoding="utf-8") as f:
            f.write("[%s] %s\n" % (stamp, msg))
    except OSError:
        pass


def version_courante():
    with open(BASE + "/gradle.properties", encoding="utf-8") as f:
        for ligne in f:
            m = re.match(r"^pluginVersion\s*=\s*(\S+)", ligne)
            if m:
                return m.group(1)
    raise SystemExit("ECHEC : pluginVersion introuvable dans gradle.properties")


# Le nom du produit doit etre cite : une revue « revue: OK » du produit 1
# ne vaut PAS approbation du produit 2.  Meme regle que dans prevol.sh.
PRODUIT_RE = re.compile(r"BrancheProjectView|branchbadge|Git Branch Badge", re.IGNORECASE)


def version_et_produit(contenu, version):
    """Vrai si le contenu cite la version EXACTE et le nom du produit.

    La version doit etre un jeton exact : « 0.0.1 » est accepte,
    « 0.0.13 » ne l'est pas (c'est un autre produit, et « 0.0.1 » y est
    un sous-texte).  Meme regle que dans prevol.sh.
    """
    jeton = re.compile(r"(^|[^0-9.])" + re.escape(version) + r"([^0-9]|$)")
    return bool(jeton.search(contenu)) and bool(PRODUIT_RE.search(contenu))


def garde_publication(version):
    """Renvoie la liste des motifs de refus (vide = le garde-fou passe)."""
    refus = []
    revue_ok = ""
    if os.path.isdir(REVUE_DIR):
        for f in sorted(glob.glob(REVUE_DIR + "/*.md")):
            try:
                contenu = open(f, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            if re.search(r"^\s*revue:\s*OK\s*$", contenu, re.MULTILINE) \
                    and version_et_produit(contenu, version):
                revue_ok = f
                break
    if not revue_ok:
        refus.append(
            "aucune revue du jury pour le produit 2 version %s dans %s "
            "(attendu : un fichier *.md contenant \"revue: OK\" ET la version exacte %s "
            "ET le nom du produit \"Git Branch Badge\")"
            % (version, REVUE_DIR, version)
        )
    if not os.path.isfile(APPROBATION_FILE):
        refus.append(
            "%s absent (attendu : le fichier doit contenir la version exacte %s "
            "ET le mot APPROUVE ET le nom du produit \"Git Branch Badge\")"
            % (APPROBATION_FILE, version)
        )
    else:
        contenu = open(APPROBATION_FILE, encoding="utf-8", errors="replace").read()
        if not version_et_produit(contenu, version) or not re.search(r"APPROUV[E\u00c9]", contenu):
            refus.append(
                "%s ne contient pas (version exacte %s ET mot APPROUVE ET nom du produit)"
                % (APPROBATION_FILE, version)
            )
    return refus, revue_ok


VERSION = version_courante()
MOTIFS, REVUE_OK = garde_publication(VERSION)
if MOTIFS:
    for motif in MOTIFS:
        msg = "REFUS build_soumission_bundle.py v%s : %s" % (VERSION, motif)
        garde_log(msg)
        print(msg, file=sys.stderr)
    print("\nBUNDLE REFUSE PAR LE GARDE-FOU DE PUBLICATION — ne pas publier.", file=sys.stderr)
    raise SystemExit(3)
garde_log(
    "OK build_soumission_bundle.py v%s : garde-fou passe (revue %s, %s)"
    % (VERSION, os.path.basename(REVUE_OK), APPROBATION_FILE)
)

# 1. le ZIP le plus recent du build
zips = sorted(glob.glob(BASE + "/build/distributions/*.zip"), key=os.path.getmtime)
if not zips:
    raise SystemExit("ECHEC : aucun ZIP dans build/distributions")
zip_src = zips[-1]
zip_name = os.path.basename(zip_src)
data = open(zip_src, "rb").read()
md5 = hashlib.md5(data).hexdigest()

# ATTENTION (27/10, cycle 74) : ce script NE RECOPIE PLUS le ZIP en
# dist/. Les documents de publication (PUBLISH_TGG_BRIEF.md, LISEZMOI-TGG.md)
# designent tous le meme livrable unique : /work/git-branch-badge-0.0.1.zip.
# Une copie loose dans dist/ recreait un DEUXIEME fichier du meme nom sur
# le disque — exactement l'etat que la mere a fait supprimer au cycle 73
# (« il ne reste qu'un seul fichier de ce nom »). Le ZIP est donc lu en
# memoire et embarque tel quel dans le bundle, sans copie intermediaire.
copie_loose = DIST + "/" + zip_name
if os.path.exists(copie_loose):
    os.remove(copie_loose)
    garde_log("INFO build_soumission_bundle.py v%s : copie dist/ %s supprimee "
               "(livrable unique = /work/%s)" % (VERSION, zip_name, zip_name))

# 2. le guide avec le md5 du ZIP reellement envoye
tpl = open(GUIDE_TEMPLATE, encoding="utf-8").read()
if "MD5_PLACEHOLDER" not in tpl:
    raise SystemExit("ECHEC : le guide ne contient plus MD5_PLACEHOLDER")
open(GUIDE_FINAL, "w", encoding="utf-8").write(tpl.replace("MD5_PLACEHOLDER", md5))

# 3. le bundle
files = [
    (GUIDE_FINAL, "LISEZMOI-TGG.md"),
    (BASE + "/src/main/resources/META-INF/pluginIcon.svg", "icone/pluginIcon.svg"),
    (BASE + "/src/main/resources/META-INF/pluginIcon_dark.svg", "icone/pluginIcon_dark.svg"),
    (BASE + "/media/01-panneau-project-branche.png", "captures/01-panneau-project-branche.png"),
    (BASE + "/media/02-libelle-branche-zoom.png", "captures/02-libelle-branche-zoom.png"),
]
with zipfile.ZipFile(BUNDLE, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr(zip_name, data)
    for src, arc in files:
        z.write(src, arc)

# 4. controles
with zipfile.ZipFile(BUNDLE) as z:
    names = z.namelist()
    inner = z.read(zip_name)
    guide = z.read("LISEZMOI-TGG.md").decode("utf-8")
md5_bundle = hashlib.md5(inner).hexdigest()
print("ZIP source      :", zip_src)
print("md5 ZIP         :", md5)
print("md5 dans bundle :", md5_bundle, "(doit egaler ci-dessus)")
print("bundle          :", BUNDLE, os.path.getsize(BUNDLE), "octets")
print("contenu         :", ", ".join(names))
print("guide coherent  :", md5 in guide and "MD5_PLACEHOLDER" not in guide)
print("ZIP du bundle identique au ZIP du build :", md5_bundle == md5)
print("livrable unique :", not os.path.exists(copie_loose),
      "(pas de copie loose dans dist/)")

# Garde-fous : le bundle part chez Tgg, il doit etre exact.
problemes = []
if md5_bundle != md5:
    problemes.append("le ZIP du bundle n'est pas celui du build")
if md5 not in guide or "MD5_PLACEHOLDER" in guide:
    problemes.append("le guide ne cite pas le md5 du ZIP livre")
if os.path.exists(copie_loose):
    problemes.append("une copie loose du ZIP est restee dans dist/")
if problemes:
    garde_log("REFUS build_soumission_bundle.py v%s : %s" % (VERSION, " ; ".join(problemes)))
    for p in problemes:
        print("ECHEC :", p, file=sys.stderr)
    raise SystemExit(4)
