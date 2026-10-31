# SIGNED ORDERS — design

> **Challenge 01 — “Blast Radius Zero”** — Vultr: Agent Rush Hackathon (3–8 November 2026).
> Written on 31 October 2026, before the official build window. This document describes
> **what will be built, how, and why**.
> Rules verified against the official pages (see `RULES.md` in the working notes) ·
> demo and video plan: `DEMO.md`.
>
> **Status of this repository (31/10/2026):** the *design* (this file) and the *code
> skeleton* (`signedorders/`, interfaces and contracts) are published. The implementation
> is written during the official build window, 3–8 November 2026 — the skeleton marks
> every gap with `TODO(build-window)`. Nothing in this repository pretends to work yet.

---

## 1. Le projet en une phrase

**Un plan de contrôle qui transforme toute action d'un agent autonome en un « ordre »
écrit, signé et vérifiable — les ordres anodins passent seuls, les ordres dangereux
attendent un humain — le tout dans un bac à sable Vultr, avec quotas, journal chaîné et
rejeu.**

> « An agent that only chats is a demo; an agent that executes safely is a product. »
> — page de l'événement. C'est exactement ce que vend ce projet.

---

## 2. Ce que fait l'agent (le travail RÉEL, pas décrit)

L'agent reçoit une **mission en langage naturel** et doit **exécuter** quelque chose de
vrai, dans un bac à sable, avec une preuve vérifiable.

**Le geste central du projet : l'agent lit une documentation et un dépôt, puis exécute une
tâche vérifiable.** Il commence par lire la documentation du service cible (son `README`, son
`docs/`, son fichier de configuration) et le code du dépôt, en déduit la cause, puis agit —
chaque lecture, chaque écriture, chaque commande passant par un **ordre signé**. Il ne
termine pas sur une phrase : il termine sur des **artefacts** (diff, sortie de test, code de
sortie, `HTTP 200` du health-check, empreinte du journal) que n'importe qui peut revérifier.

**La mission de démonstration retenue (Pattern A + un morceau de Pattern B) :**

> « Le service `checkout-api` de notre plateforme de démo ne répond plus. Trouve la cause,
> corrige-la, et prouve que le service est revenu. Tu n'as pas le droit de toucher aux
> données clients. »

L'agent, en boucle, fait :

1. **Lit** les journaux du service et son code (ordres `read` : *low risk*, exécutés seuls) ;
2. **Écrit** un correctif dans le bac à sable (ordre `write` : *low risk* dans un fichier de
   code du bac à sable) ;
3. **Teste** : relance le service de test, récupère la sortie réelle (stdout/stderr, code de
   sortie) — les **erreurs sont renvoyées au modèle** pour un nouvel essai (retry loop
   visible, comme l'exige l'exemple « Self-Healing Runner ») ;
4. **Applique** le correctif au service cible via un ordre `apply` mutant (*medium risk* :
   snapshot automatique avant, rollback automatique en cas d'échec) ;
5. **Vérifie** : `GET /health` du service → **HTTP 200 + corps attendu**, et une capture
   d'écran de l'UI servie par le service (contrôle **vision** par un modèle multimodal).

**Sortie = des artefacts vérifiables**, pas une phrase : le diff du correctif, la sortie des
tests, le code de sortie, le résultat du health-check, la capture d'écran, l'empreinte du
journal.

**Ce n'est pas « l'agent dit qu'il a réparé » : la démo montre le service qui était mort
redevenir vivant à l'écran.**

---

## 3. Pourquoi son action est sandboxée — le cœur du projet

C'est le critère « Blast Radius Zero » : *« Process isolation: execution never touches the
host or app runtime. Secret hygiene: no API keys or credentials inside the sandbox.
Resource limits: apply time and memory caps to every run. Lifecycle discipline: reset or
destroy the environment after each task. »*

**Notre réponse à ces 4 points, et le cœur du système :**

| Exigence Vultr | Notre réponse | Où c'est prouvé à l'écran |
|---|---|---|
| Isolation du processus | L'agent **n'a pas de shell**. Il ne peut qu'**émettre des demandes d'ordres** à l'API du plan de contrôle. L'exécuteur est un processus séparé, dans un conteneur séparé. | Journal : chaque action de l'agent est une *demande*, jamais une exécution. |
| Hygiène des secrets | Le bac à sable est lancé **sans aucune variable d'environnement de l'agent** ; les clés (inférence Vultr, API Vultr) n'existent que dans le processus du plan de contrôle, jamais dans le bac. | `docker inspect` à l'écran : la liste d'env du conteneur ne contient aucune clé. |
| Limites de ressources | `--memory=256m --cpus=1 --pids-limit=128`, réseau coupé, système de fichiers en lecture seule, **timeout mural** ; plus des **quotas** par agent (nombre d'ordres, secondes d'exécution, octets écrits, tokens LLM, concurrence). | Compteurs de quota en direct dans l'UI ; chronomètre qui tue un éboulement. |
| Discipline du cycle de vie | Un bac **neuf** par tâche, **détruit** à la fin (`--rm`, `destroy` pour le backend microVM). Rien ne survit d'une tâche à l'autre. | « sandbox sb-42 destroyed » dans le journal. |

**Et le point qui fait la différence — la séparation des rôles :**

```
   AGENT (dans le bac)          PLAN DE CONTRÔLE (sur la VM)        EXÉCUTEUR (séparé)
   ───────────────────          ────────────────────────────        ──────────────────
   peut : POST /orders     ──▶  1. vérifie l'identité de l'agent
   ne peut PAS :                2. classifie le risque (règles)
   • exécuter du shell          3. contrôle les quotas
   • lire un secret             4. signe (Ed25519) ou met EN ATTENTE
   • écrire hors du bac   ──▶  5. exécute dans un bac neuf ──────▶  6. journalise
                                    (jamais de shell pour l'agent)     7. détruit le bac
```

**La clé privée de signature ne sort jamais de la VM et n'est jamais dans le dépôt.**
La clé **publique** (de vérification) est dans le dépôt : n'importe qui peut, depuis un
clone frais et **sans aucun secret**, vérifier hors ligne chaque ligne du journal :

```bash
python3 -m signedorders verify journal.jsonl   # RC 0 = chaîne intacte
```

**C'est le cœur de l'argument « Blast Radius Zero » :** l'agent compromis (par une
injection de prompt, un bug, ou un modèle hallucinant) peut **demander** n'importe quoi,
mais il ne peut **exécuter** que ce qui est **signé**, et il ne peut obtenir une signature
que si la politique le classe *low risk*. Tout le reste **attend un humain**.

**Le passage par le garde-fou humain est court et lisible :** un ordre à risque élevé apparaît
dans l'UI (et, en option, sur Telegram) avec **l'action exacte, la cible, la justification
et l'impact** → `APPROUVER` / `REFUSER`. En cas de non-réponse, l'ordre **expire** (pas
d'approbation implicite).

---

## 4. Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  VM VULTR (Ubuntu 24.04)  — « le plan de contrôle »  1 VM, 2 vCPU / 4 Go      │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐  │
│  │  signedorders/  (FastAPI + SQLite WAL + Ed25519)                      │  │
│  │   • API /orders   (créer, lister, approuver, refuser)                  │  │
│  │   • politique.py  (classification du risque — règles AUDITABLES)       │  │
│  │   • quotas.py     (par agent / par tâche / par heure)                  │  │
│  │   • journal.py    (JSONL append-only, chaîne de hachage)               │  │
│  │   • signataire    (Ed25519 — clé privée JAMAIS dans le dépôt)          │  │
│  │   • rejeu.py      (rejoue le journal dans un bac neuf, compare)        │  │
│  │   • ui/           (web + SSE : ordres, verdicts, quotas, journal)     │  │
│  └────────────────────────────────────────────────────────────────────────┘  │
│         ▲  POST /orders (clé d'agent, portée = « demander » seulement)      │
│         │                                                                  │
│  ┌──────┴───────────┐      ┌────────────────────┐      ┌──────────────────┐  │
│  │ agent (worker)   │      │ exécuteur          │      │ bac à sable      │  │
│  │ boucle LLM       │      │ (processus dédié)  │      │ (NOUVEAU par      │  │
│  │ → Vultr Serverless│      │ vérifie la sig.    │      │  tâche, détruit  │  │
│  │   Inference      │      │ + re-contrôle la   │      │  à la fin)        │  │
│  │ (OpenAI-compatible)│    │  politique         │      │ --network=none    │  │
│  └──────────────────┘      │ applique quotas    │      │ --memory=256m     │  │
│                            │ snapshot/rollback  │      │ --cpus=1          │  │
│                            └────────────────────┘      │ --read-only       │  │
│                                                       │ --cap-drop=ALL    │  │
│  ┌──────────────────┐      ┌────────────────────┐      │ --pids-limit=128 │  │
│  │ service cible    │      │ « checkout-api »   │      │ timeout mural    │  │
│  │ (notre démo)     │      │ CASSÉ au départ    │      └──────────────────┘  │
│  └──────────────────┘      └────────────────────┘                            │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Deux backends de bac à sable derrière une seule interface** (`SandboxBackend`) :

| Backend | Isolation | Rôle |
|---|---|---|
| `docker` | conteneur, namespace + cgroups | le quotidien (Docker est « strongly recommended » par Vultr) |
| `microsandbox` (`msb`) | **microVM** (libkrun/KVM, noyau séparé de l'hôte) | la démonstration forte : *« the same hostile order, blocked by two different isolation layers »* |

> Le guide Vultr « Agent Sandboxing » décrit exactement `msb` : microVM, `--cpus`/`--memory`
> explicites, isolation entre bacs frères par défaut, SDK Python, serveur MCP. C'est un
> excellent second backend — et il prouve la maîtrise de la pile.

---

## 5. La pile technique

| Couche | Choix | Pourquoi |
|---|---|---|
| **Hébergement** | **Vultr Cloud Compute**, 1 VM Ubuntu 24.04 | « Both challenges require a Vultr VM backend » ; le plan de contrôle EST le backend |
| **Inférence (obligatoire)** | **Vultr Serverless Inference**, `https://api.vultrinference.com/v1` (compatible OpenAI) | « Challenge 1 **must** use Vultr Serverless Inference for agent LLM calls » |
| **Modèle — agent** | `qwen3.8-flash-next` (texte+image+fichier+vidéo → texte), ou `minimax-m3.1-flash` | rapides, abordables, disponibles dans la liste publique du 31/10 |
| **Modèle — vérificateur** | `qwen3.8-27b` ou `nemotron-3-nano-omni-30b-a3b-reasoning` | **vision** : contrôle la capture d'écran du service réparé |
| **Modèle — garde-fou** | `nemotron-3.5-content-safety` | défense en profondeur : filtre l'ordre **avant** la politique |
| **Exécution** | Docker sur la VM + **Microsandbox** (`msb`) en second backend | « Sandbox boundary: containers or throwaway instances on Vultr, never inside the app process » |
| **API / UI** | FastAPI + uvicorn, Jinja2 + JS vanilla, SSE pour le direct | une seule dépendance de façade, pas de framework front lourd à déployer |
| **Stockage** | SQLite (WAL) | pas de base gérée à payer ; le journal est un fichier |
| **Signature** | Ed25519 (`cryptography`) | petite, rapide, vérifiable en 20 lignes ; clé publique dans le dépôt, clé privée sur la VM |
| **App cible de la démo** | un petit service `checkout-api` (uvicorn) **écrit par nous**, volontairement cassé | « on a target that belongs to us » : aucune cible tierce |
| **Tooling** | `pytest`, `ruff`, `uv` ou `pip`+venv, `terraform`/`cloud-init` pour la VM | reproductible, installable en une commande |
| **Vulnérabilité testée** | `git` + `gh` (dépôt public), un **faux** secret `DEMO_ONLY_NOT_A_REAL_KEY` planté dans le service | l'injection de prompt cherche à exfiltrer un secret **fictif** |

**Sur les GPU** : « Vultr GPUs are not available for this event » → **aucun GPU dans l'architecture.**

**Sur le coût** : les 200 $ de crédits couvrent largement une VM 2 vCPU / 4 Go sur 6 jours
+ quelques appels d'inférence. **Aucun euro de notre part** (le compte Vultr sera créé par
Tgg, le code promo est distribué au kick-off).

---

## 6. Les 5 étapes de la démo (résumé — le détail est dans `DEMO.md`)

| # | Étape | Ce que le spectateur voit | La preuve |
|---|---|---|---|
| 1 | **La mission** | L'agent reçoit « le service est cassé, répare-le » | La console de l'agent |
| 2 | **Les ordres** | Chaque action de l'agent apparaît comme un **ordre signé** : signature, risque, verdict | Journal en direct + signature Ed25519 vérifiable |
| 3 | **Le travail réel** | Le correctif est écrit et testé **dans le bac**, le service **redevient vivant** (200) | Diff + sortie de tests + capture d'écran + health-check |
| 4 | **LE MOMENT DE CONFINEMENT** | Une **injection de prompt** pousse l'agent à `rm -rf` et à exfiltrer → l'ordre est classé **HAUT RISQUE** → **refusé**, les données sont **intactes** ; puis, même **après approbation**, le bac **absorbe** `rm -rf /` et l'éboulement est tué par le timeout | Empreinte des données identique avant/après + ligne `DENIED` au journal + overlay du conteneur intact + chronomètre |
| 5 | **Le rejeu** | Le journal est rejoué dans un bac **neuf** → **même résultat** ; l'UI affiche ordres/refus/coût/durée | Bouton « Rejouer » + RC 0 de la vérification hors ligne |

L'étape 4 est **obligatoire** (Vultr : « one containment moment shown in the video ») et c'est
**le point le plus fort du projet** : on ne montre pas seulement que le bac à sable bloque,
on montre que **le plan de contrôle bloque l'agent *avant* que le bac à sable ait à le faire**.

---

## 7. Le plan de la vidéo (résumé — le détail est dans `DEMO.md`)

Atelier Windows : **captures d'écran + voix de synthèse** (comme demandé par la mère),
montage sans musique, sous-titres integrés, **4 min 30** (barème : « less than 5 min »),
fichier **MP4 < 300 Mo**.

| Temps | Contenu |
|---|---|
| 0:00–0:25 | **Le problème** : un agent qui fait du vrai travail peut tout casser ; « blast radius ». |
| 0:25–0:50 | **La réponse en 25 s** : plan de contrôle sur VM Vultr, ordres signés, bac à sable, quotas, journal rejouable. Schéma animé à l'écran. |
| 0:50–1:40 | **La démo, partie 1** : mission → ordres → correctif → service réparé (HTTP 200 + capture). |
| 1:40–3:00 | **LE MOMENT DE CONFINEMENT** : l'injection, l'ordre à haut risque, le refus, la preuve ; puis le `rm -rf` absorbé et l'éboulement tué. |
| 3:00–3:50 | **Rejeu + vérifiabilité** : rejeu du journal, vérification hors ligne, deux backends d'isolation. |
| 3:50–4:30 | **Valeur business + originalité + dépôt + URL de démo.** |

---

## 8. 🔒 Ligne rouge : ce qui ne sera PAS dans le dépôt public

| Interdit | Règle appliquée |
|---|---|
| **Aucune clé, aucun secret** | Les clés sont lues dans l'**environnement** de la VM (`/etc/signedorders/env`, mode 0600, hors du dépôt). Le dépôt ne contient que des **noms** de variables. |
| **Aucune donnée privée** | La cible de la démo est **notre** service `checkout-api`, écrit pour l'occasion. Le « secret » que l'injection tente d'exfiltrer est un **faux** (`DEMO_ONLY_NOT_A_REAL_KEY`). Aucune donnée réelle, aucun log client, aucun nom de personne. |
| **Aucune adresse de nos serveurs** | Aucune IP, aucun domaine interne, aucun identifiant de compte dans le dépôt ni dans la vidéo (le plan de démo prévoit un « scrub » des captures). |
| **Clé privée de signature** | Générée **sur la VM** au premier démarrage (`signedorders init-key`), jamais pushée. Seule la clé **publique** est versionnée. |
| **Code préexistant** | Le projet est **écrit de zéro pendant la fenêtre** (3–8 novembre). Aucune ligne du système interne n'est reprise : c'est la condition du « original » du règlement. |

Un contrôle automatisé (`preflight`) vérifiera avant chaque commit : absence de motif de
secret, absence de **bloc** de clé privée complet (`BEGIN … PRIVATE KEY` … `END … PRIVATE KEY`),
absence de motifs `sk-`, `vultr_`, `ghp_`, `AKIA…`, absence d'IP publique de nos serveurs.
Contrôle écrit et exécuté le 31/10/2026 : `python3 -m signedorders.preflight .` → **OK**,
et le même contrôle **refuse** un vrai bloc de clé (contre-épreuve).

---

## 9. Le format de l'ordre signé (le contrat central)

```jsonc
{
  "order_id": "so_7f3a…",              // uuid
  "issued_at": "2026-11-05T14:03:11Z",
  "issuer": "agent/checkout-fixer",     // identité de l'agent
  "task_id": "task_91b2…",             // la mission
  "action": "exec",                    // read | write | apply | exec | net
  "target": "sandbox://sb-42:/app/main.py",
  "payload": { "argv": ["pytest", "-q"] },
  "risk": "low",                       // low | medium | high  (décidé par la politique)
  "rule_id": "allow.test.exec",        // la règle qui a décidé — traçable
  "policy_hash": "sha256:…",           // empreinte de la version de la politique utilisée
  "quota_snapshot": { "orders": 7, "cpu_seconds": 12, "tokens": 1830 },
  "compensates": null,                 // action de rollback (obligatoire si l'ordre mute)
  "nonce": "…",
  "signature": "ed25519:…"            // signature de l'ordre, sur les octets canoniques
}
```

**Propriétés garanties par la conception :**

1. **Vérifiable hors ligne** — la clé publique est dans le dépôt ; `verify` recalcule la
   signature et la chaîne de hachage du journal → **RC 0** si rien n'a été modifié.
2. **Non-rejouable par l'exécuteur** — l'exécuteur refuse un `nonce` déjà vu ou une
   `policy_hash` qui ne correspond pas à la politique en vigueur.
3. **Auditable** — la règle qui a classé l'ordre est dans l'ordre lui-même.
4. **Rejouable** — `signedorders replay` rejoue les ordres en lecture seule dans un bac
   neuf et compare les sorties : la traçabilité devient une **preuve**, pas une promesse.

---

## 10. La politique de risque (règles auditables, en données)

| Catégorie | Exemples | Décision |
|---|---|---|
| `read` — lecture de fichier, `tail` de journal, `GET /health` | journaliser, inspecter | **low** → exécution seule |
| `test` — lancer la suite de tests du bac | `pytest -q` | **low** → exécution seule |
| `write` — écrire un fichier **du bac** | patch du code du bac | **low** → exécution seule (le bac est jetable) |
| `apply` — muter la **cible** | redémarrer le service cible | **medium** → exécution seule **avec snapshot + rollback automatique** |
| `restart` — redémarrer un service | systemctl restart | **medium** → exécution seule + snapshot |
| `delete` | `rm`, `rm -rf`, `truncate`, `DROP` | **high** → **approbation humaine obligatoire** |
| `network` — sortir vers l'extérieur | `curl`, `wget`, métadonnées cloud `169.254.169.254` | **high** si la cible n'est pas sur la liste blanche → **approbation** |
| `escape` — tenter de toucher l'hôte | `../..`, `/proc`, `chroot`, montage, `--privileged` | **high** + **refus technique** (la politique refuse l'ordre, pas d'approbation possible) |
| `secret` — lire une variable d'environnement | `env`, `printenv`, `/proc/self/environ` | **high** + refus technique |

**Deux niveaux de refus, à distinguer clairement (c'est un point de conception fort) :**

- **Refus par politique** (l'ordre est *dangereux par nature* : il est marqué `escape` ou
  `secret`) → **aucune approbation humaine ne peut le déverrouiller**. Le système refuse
  par construction.
- **Refus par quota** → l'ordre est refusé, le journal le dit, et si le quota de sécurité
  (temps d'exécution, octets écrits) est franchi, le **rollback automatique** se déclenche.

---

## 11. Le plan jour par jour (3 → 8 novembre)

| Jour | Objectif | Critère de fin |
|---|---|---|
| **3 nov. (mardi)** | Compte Vultr + code promo (au kick-off) · création de la VM · squelette du dépôt (README, licence, CI) · choix du modèle validé par un appel réel | 1 appel à Serverless Inference renvoie une réponse ; la VM répond en SSH |
| **4 nov. (mercredi)** | Noyau : signature Ed25519, format d'ordre, politique, quotas, journal chaîné, **exécuteur** + backend Docker | Les tests unitaires du noyau passent ; un ordre signé s'exécute, un ordre falsifié est refusé |
| **5 nov. (jeudi)** | Agent (boucle LLM + reprise sur erreur) + service cible `checkout-api` cassé + UI web (SSE) + porte d'approbation | La mission de bout en bout passe sur un bac neuf ; l'UI affiche le flux |
| **6 nov. (vendredi)** | Backend Microsandbox (`msb`) · rollback automatique · rejeu du journal · durcissement (egress, quotas stricts) | La même attaque est bloquée par les **deux** backends ; le rejeu donne le même résultat |
| **7 nov. (samedi)** | Déploiement propre sur Vultr · **URL de démo publique** · 3 passes de démo filmées · diaporama · montage vidéo | Vidéo ≤ 4 min 30 Exportée, diaporama PDF prêt, URL de démo stable |
| **8 nov. (dimanche)** | **Soumission avant 20:00 UTC** (2 h de marge) · relecture du README · scrub des captures · commit final · push du dépôt public | Formulaire soumis, dépôt public visible, vidéo en ligne |

**Le 8 novembre, la soumission se fait à la main par Tgg sur son compte lablab.ai**
(la consigne de la mère est explicite, et l'automatisation de la soumission serait
« unauthorized automation » au sens du règlement).

---

## 12. Valeur business (critère n° 2 du barème)

**Le problème, formulé comme un marché :** les entreprises déploient des agents qui
exécutent du vrai travail (code, navigateur, données). Elles découvrent ensuite qu'il leur
manque trois choses : **une limite d'explosion** (« blast radius »), **une preuve
d'audit** (qui a décidé quoi, et pourquoi), et **un garde-fou humain** sans lequel l'agent
n'est pas déployable en production.

**Ce qui existe déjà ne fait pas ce travail :** les outils de *tracing* observent l'agent
après coup ; les *evals* le testent hors production ; les *guardrails* filtrent l'entrée.
**Aucun ne fait de l'**autorisation** une opération **signée, hors ligne vérifiable et
rejouable**. C'est la combinaison distinctive que nous revendiquons (à ré-vérifier dans le
pitch : « distinct », pas « unprecedented »).

**Modèle économique** (cœur MIT, service payant) :

| Offre | Prix | Contenu |
|---|---|---|
| **Signed Orders OSS** | 0 $ | Auto-hébergé : plan de contrôle, exécuteur, journal, rejeu |
| **Signed Orders Cloud** | 29 $/mois/agent · 199 $/mois/équipe | hébergement de la clé de signature, approbations Telegram/Slack, SIEM, rétention du journal, quotas gérés |
| **Signed Orders Enterprise** | sur devis | HSM/KMS, signature conforme (eIDAS), audit multi-tenant |

**Pourquoi les jugeurs doivent y croire (3 arguments) :**
1. **Coût évité** : un incident d'agent coûte (en moyenne) bien plus qu'un abonnement
   mensuel ; le plan de contrôle est une assurance, pas un luxe.
2. **Preuve d'audit** : le journal signé est un artefact de conformité (qui, quoi, quand,
   avec quelle signature) — un argument qui parle aux équipes sécurité et conformité.
3. **Originalité** : signer l'*autorisation* plutôt que tracer l'*activité* est une
   inversion simple et frappante.

---

## 13. Risques et plans B (à traiter au jour le jour, pas la veille)

| Risque | Plan B |
|---|---|
| Le compte Vultr / le code promo ne sont pas activés le 3 nov. | Construire et tester **en local** dans le conteneur (Docker disponible ? sinon sous-rootless podman) ; le backend Docker ne change pas. |
| `msb` (Microsandbox) ne fonctionne pas sur l'Ubuntu de la VM | Le backend Docker **reste** la référence (Vultr le recommande) ; `msb` devient un bonus, pas un bloquant. |
| Le modèle multimodal est lent ou indisponible | Vérification **déterministe** (code de sortie + statut HTTP + empreinte du corps) en repli — c'est même **plus** fort comme preuve ; le contrôle vision devient un bonus. |
| La démo plante pendant le direct | **Plan de secours filmé** : la vidéo est enregistrée à l'avance (atelier Windows), donc pas de direct ; l'URL de démo reste la preuve vivante. |
| Le dépôt public est jugé « trop petit » | Le barème valorise la qualité, pas le volume : 4-5 « Github code is available & well thought off ». On montre le **rejeu** et les tests. |
| Le formulaire de soumission demande un compte lié à une équipe | Créer/joindre l'équipe **avant** le 3 nov. (à faire par Tgg : compte lablab.ai + Discord + équipe). |

---

## 14. Ce que je n'ai PAS fait (et pourquoi)

- **Aucun code du projet** : la consigne interdit d'écrire du code de concours avant le
  3 novembre (« un projet construit pendant la fenêtre officielle »). Ce cycle = conception.
- **Aucun compte, aucune clé, aucune dépense** : `0 $`. Le compte Vultr, le compte lablab.ai
  et le code promo sont du ressort de Tgg (et de la mère).
- **Aucune modification du produit JetBrains** : la consigne du 31/10 arrête ce slot.
- **Aucune soumission** : elle se fera le 8 novembre, à la main, par Tgg.
