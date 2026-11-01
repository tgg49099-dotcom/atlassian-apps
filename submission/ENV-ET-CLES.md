# ENV ET CLÉS — la liste exacte, sans aucune valeur

> **Ce document ne contient aucun secret et aucune valeur** : seulement des **noms** de
> variables, l'endroit exact où Tgg doit aller, et la ligne exacte à taper ou l'écran exact à
> suivre. Les valeurs vivent **uniquement sur la VM Vultr**, dans `/etc/signedorders/env`
> (mode `0600`, propriétaire `root`), et nulle part ailleurs.
>
> **Règle du projet :** une clé dans un fichier du dépôt, dans une capture d'écran ou dans la
> vidéo = faute. Le scanneur `python3 -m signedorders preflight .` échoue si un motif de secret
> apparaît dans l'arbre ; il est exécuté avant chaque commit.

---

## 1. Les **3 secrets** du projet (ceux qu'il ne faut jamais lost)

| # | Variable | Où Tgg va la chercher | La ligne exacte | Où elle atterrit |
|---|---|---|---|---|
| 1 | `VULTRINFERENCE_API_KEY` | Console Vultr → **Settings → API** (<https://my.vultr.com/settings/#api>) → section **Serverless Inference** → **Create API Key** → *nommer* la clé (ex. `signed-orders`) → **Create**. La clé **s'affiche une seule fois** : la copier immédiatement. | `sudo nano /etc/signedorders/env` → remplacer la ligne `VULTRINFERENCE_API_KEY=` par `VULTRINFERENCE_API_KEY=<la clé>` → `sudo systemctl restart signedorders` | `/etc/signedorders/env`, mode `0600` |
| 2 | `SIGNEDORDERS_AGENT_KEY` | **Générée automatiquement sur la VM** par le cloud-init (`deploy/cloud-init.yml`), au premier démarrage. Tgg n'a rien à créer. | `sudo grep SIGNEDORDERS_AGENT_KEY /etc/signedorders/env` | idem (générée, jamais affichée ailleurs) |
| 3 | `SIGNEDORDERS_OPERATOR_KEY` | **Générée automatiquement sur la VM** par le cloud-init, comme la précédente. | `sudo grep SIGNEDORDERS_OPERATOR_KEY /etc/signedorders/env` | idem |

> **À quoi elles servent**
> - `VULTRINFERENCE_API_KEY` → l'appel au modèle (Vultr Serverless Inference, `https://api.vultrinference.com/v1`). C'est la clé que le challenge impose pour la boucle agent.
> - `SIGNEDORDERS_AGENT_KEY` → le **bearer** que l'agent doit présenter pour `POST /orders`. Sans elle : **HTTP 401**.
> - `SIGNEDORDERS_OPERATOR_KEY` → le **bearer** qu'un **humain** doit présenter pour approuver ou refuser un ordre (`POST /orders/<id>/approve` ou `/reject`). Sans elle : **HTTP 401**.
>
> ⚠️ La clé de l'agent **ne doit jamais** être donnée au navigateur : le plan de contrôle est
> une porte, pas une page web. Dans la vidéo, on montre la **réponse** de l'API, pas la clé.

## 2. La clé de signature (un secret, mais **local** à la VM)

| Variable | Où | La ligne exacte |
|---|---|---|
| `SIGNEDORDERS_SIGNING_KEY_PATH` | le chemin du fichier, pas une valeur | `/etc/signedorders/signing.key` |

```bash
# sur la VM — la clé privée Ed25519 (32 octets), mode 0640 root:signedorders
sudo stat -c '%a %U:%G %n' /etc/signedorders/signing.key
# si elle n'existe pas encore :
sudo /opt/signed-orders/.venv/bin/signedorders init-key /etc/signedorders/signing.key
sudo chown root:signedorders /etc/signedorders/signing.key && sudo chmod 0640 /etc/signedorders/signing.key
```

La **clé publique** est **publique par choix** : c'est elle qui permet de vérifier le journal
hors ligne, sans aucun secret. Elle est affichée par `GET /health` et peut être committée.

## 3. Les variables **sans secret** (noms et valeurs par défaut)

> **Précision importante (colonne « dans `/etc/signedorders/env` ? »).** Le code lit **27**
> variables d'environnement. Le cloud-init en écrit **22** dans `/etc/signedorders/env`, avec
> les valeurs par défaut. Les **5 restantes** ne sont **pas** écrites par le cloud-init : le code
> lit directement son **défaut interne**, ce qui est le comportement voulu — il n'y a donc
> **rien à ajouter** sur la VM. (Vérifié le 01/11/2026 :
> `grep -rno 'SIGNEDORDERS_[A-Z_]*\|VULTRINFERENCE_[A-Z_]*' signedorders/*.py` → 27 noms.)

### 3.1 Inférence

| Variable | Défaut | Rôle | Écrite par le cloud-init ? |
|---|---|---|---|
| `VULTRINFERENCE_BASE_URL` | `https://api.vultrinference.com/v1` | la base de l'API (compatible OpenAI) | **non** — défaut du code |
| `VULTRINFERENCE_MODEL` | `qwen3.8-flash-next` | le modèle **texte** de la boucle agent | oui |
| `VULTRINFERENCE_VISION_MODEL` | `qwen3.8-27b` | le modèle **vision** qui relit la capture d'écran du service réparé | oui |

> Les noms de modèles **changent** (la liste est publique et bouge) :
> `curl -s https://api.vultrinference.com/v1/models` **sans clé** renvoie la liste à jour.
> Vérifier avant le 7 novembre, et corriger si le modèle n'existe plus.

### 3.2 La porte HTTP

| Variable | Défaut | Rôle | Écrite par le cloud-init ? |
|---|---|---|---|
| `SIGNEDORDERS_HOST` | `127.0.0.1` | l'interface d'écoute (`0.0.0.0` pour être public) | **non** — défaut du code |
| `SIGNEDORDERS_PORT` | `8000` | le port | **non** — défaut du code |
| `SIGNEDORDERS_JOURNAL_PATH` | `/var/lib/signedorders/journal.jsonl` | le fichier du journal chaîné | **non** — défaut du code |

> ⚠️ Sur la VM, l'unité systemd force déjà `--host 0.0.0.0 --port 8000 --journal
> /var/lib/signedorders/journal.jsonl` : **la ligne de commande l'emporte** sur ces variables.

### 3.3 Sandbox (l'isolation où les ordres s'exécutent)

| Variable | Défaut | Rôle | Écrite par le cloud-init ? |
|---|---|---|---|
| `SIGNEDORDERS_SANDBOX_BACKEND` | `docker` | `docker` ou `msb` (Microsandbox, microVM) | oui |
| `SIGNEDORDERS_SANDBOX_IMAGE` | `debian:bookworm-slim` | l'image du bac | oui |
| `SIGNEDORDERS_SANDBOX_MEMORY` | `256m` | la limite mémoire (cgroup) | oui |
| `SIGNEDORDERS_SANDBOX_CPUS` | `1` | la limite CPU | oui |
| `SIGNEDORDERS_SANDBOX_PIDS_LIMIT` | `128` | le nombre maximal de processus | oui |
| `SIGNEDORDERS_SANDBOX_TIMEOUT_SECONDS` | `30` | le mur de l'horloge (`timeout --signal=KILL`) | oui |
| `SIGNEDORDERS_SANDBOX_EGRESS_ALLOWLIST` | *(vide)* | les hôtes que le bac a le droit de joindre | oui |

### 3.4 Quotas

| Variable | Défaut | Rôle | Écrite par le cloud-init ? |
|---|---|---|---|
| `SIGNEDORDERS_QUOTA_ORDERS_PER_WINDOW` | `10` | ordres par agent, fenêtre glissante de 60 s | oui |
| `SIGNEDORDERS_QUOTA_WINDOW_SECONDS` | `60` | la longueur de la fenêtre | oui |
| `SIGNEDORDERS_QUOTA_ORDERS_PER_TASK` | `40` | ordres par tâche | oui |
| `SIGNEDORDERS_QUOTA_CPU_SECONDS_PER_TASK` | `120` | secondes CPU par tâche | oui |
| `SIGNEDORDERS_QUOTA_BYTES_WRITTEN_PER_TASK` | `1048576` | octets écrits par tâche | oui |
| `SIGNEDORDERS_QUOTA_CONCURRENT_SANDBOXES` | `2` | bacs simultanés par tâche (limite de **sécurité**) | oui |
| `SIGNEDORDERS_QUOTA_TOKENS_PER_TASK` | `200000` | jetons de modèle par tâche (le coût LLM est plafonné) | oui |

### 3.5 Approbations

| Variable | Défaut | Rôle | Écrite par le cloud-init ? |
|---|---|---|---|
| `SIGNEDORDERS_APPROVAL_CHANNEL` | `web` | le canal d'approbation | oui |
| `SIGNEDORDERS_APPROVAL_TTL_SECONDS` | `900` | la durée de vie d'un ordre en attente (au-delà : **il expire**, le silence n'est pas une approbation) | oui |
| `SIGNEDORDERS_APPROVAL_KEY_PATH` | `/etc/signedorders/approval.key` | le chemin d'une éventuelle clé d'approbation séparée (non utilisée par le code actuel) | **non** — défaut du code |

## 4. Le fichier, en entier, **sans les valeurs**

```bash
# sur la VM : la liste des NOMS, avec les valeurs VIDÉES
sudo sed -E 's/=.*/=/' /etc/signedorders/env
```

Le résultat attendu (l'ordre peut varier) :

```
VULTRINFERENCE_API_KEY=
VULTRINFERENCE_MODEL=qwen3.8-flash-next
VULTRINFERENCE_VISION_MODEL=qwen3.8-27b
SIGNEDORDERS_AGENT_KEY=
SIGNEDORDERS_OPERATOR_KEY=
SIGNEDORDERS_SIGNING_KEY_PATH=/etc/signedorders/signing.key
SIGNEDORDERS_QUOTA_ORDERS_PER_WINDOW=10
SIGNEDORDERS_QUOTA_WINDOW_SECONDS=60
SIGNEDORDERS_QUOTA_ORDERS_PER_TASK=40
SIGNEDORDERS_QUOTA_CPU_SECONDS_PER_TASK=120
SIGNEDORDERS_QUOTA_BYTES_WRITTEN_PER_TASK=1048576
SIGNEDORDERS_QUOTA_CONCURRENT_SANDBOXES=2
SIGNEDORDERS_QUOTA_TOKENS_PER_TASK=200000
SIGNEDORDERS_SANDBOX_BACKEND=docker
SIGNEDORDERS_SANDBOX_IMAGE=debian:bookworm-slim
SIGNEDORDERS_SANDBOX_MEMORY=256m
SIGNEDORDERS_SANDBOX_CPUS=1
SIGNEDORDERS_SANDBOX_PIDS_LIMIT=128
SIGNEDORDERS_SANDBOX_TIMEOUT_SECONDS=30
SIGNEDORDERS_SANDBOX_EGRESS_ALLOWLIST=
SIGNEDORDERS_APPROVAL_CHANNEL=web
SIGNEDORDERS_APPROVAL_TTL_SECONDS=900
```

> Les **3 lignes sensibles** (`VULTRINFERENCE_API_KEY`, `SIGNEDORDERS_AGENT_KEY`,
> `SIGNEDORDERS_OPERATOR_KEY`) apparaissent **vides** : c'est le contrôle à faire avant de
> filmer une capture d'écran de ce fichier.

## 5. Les autres secrets du système (hors projet) — pour mémoire

| Secret | Où il vit | Pourquoi il n'est pas dans la liste ci-dessus |
|---|---|---|
| `VULTR_TOKEN` (clé API Vultr) | la machine de Tgg, en variable d'environnement | elle ne sert qu'à `vultr-cli` pour créer la VM ; **le service n'en a pas besoin** |
| clé SSH `~/.ssh/signed_orders_vultr` | le poste de Tgg | elle n'entre jamais sur la VM ni dans le dépôt |
| carte bancaire | chez l'opérateur | Tgg |

## 6. Le contrôle automatique (à passer avant l'export de la vidéo et avant chaque commit)

```bash
cd atlassian-apps/agent-rush/signed-orders
python3 -m signedorders preflight .        # → doit sortir avec le code 0
```

Le scanneur cherche : un motif de clé, un bloc `-----BEGIN … PRIVATE KEY-----` complet, une
adresse interne, un secret nommé. Il **échoue** si le dépôt contient une valeur.

Et, sur la VM, la preuve que le service ne fuit rien :

```bash
curl -s http://127.0.0.1:8000/health | grep -i private   # → aucune sortie
bash deploy/verifier-deploiement.sh http://127.0.0.1:8000   # → 6 PASS
```

---

## 7. En résumé, la checklist de Tgg

1. ☐ Créer le compte Vultr et **la clé d'inférence** (écran : *Settings → API → Serverless Inference → Create API Key*).
2. ☐ Créer la VM Ubuntu 24.04 avec le cloud-init → la clé de signature et les 2 clés bearer sont **générées sur la VM**.
3. ☐ (Optionnel) coller la clé d'inférence dans `/etc/signedorders/env` et redémarrer.
4. ☐ Vérifier : `curl -s http://<IP>:8000/health` et `bash deploy/verifier-deploiement.sh http://<IP>:8000`.
5. ☐ Remplir l'URL de la démo et l'URL de la vidéo dans `SOUMISSION-EN.md`.
6. ☐ Soumettre sur lablab.ai **avant le 8 novembre 2026 à 20:00 UTC**.
