# DEPLOIEMENT VULTR — « Signed Orders » (challenge 01 « Blast Radius Zero »)

> **Ce document est la procédure exacte que Tgg suit, dans l'ordre.** Tout ce qui est vérifiable
> sans compte a été **exécuté et testé dans ce conteneur** ; ce qui ne peut pas l'être (la création
> du compte Vultr, le paiement, la VM elle-même) est **explicitement signalé comme « à faire par
> Tgg »**, avec la marche à suivre exacte.
>
> **Échéance : la soumission est le 8 novembre 2026 à 20:00 UTC.** Le compte Vultr et la VM sont
> à créer **avant le 7 novembre** pour garder une marge.

---

## 0. En résumé (le chemin court)

| Étape | Qui | Durée | Coût |
|---|---|---|---|
| 1. Créer le compte Vultr (et saisir la carte **seulement au moment du paiement**) | Tgg | 5 min | 0 $ (200 $ de crédits offerts au kick-off du 3/11 16:00 UTC) |
| 2. Créer la clé SSH et l'ajouter au compte Vultr | Tgg | 2 min | 0 $ |
| 3. Créer la VM Ubuntu 24.04 avec le cloud-init ci‑dessous | Tgg | 3 min | ~10 $/mois, couvert par les 200 $ de crédits offerts |
| 4. Vérifier que l'API répond | Tgg | 1 min | 0 $ |
| 5. (Optionnel) Brancher le modèle : `VULTRINFERENCE_API_KEY` | Tgg | 5 min | crédits Vultr |
| 6. (Optionnel) Un nom de domaine + HTTPS | Tgg | 10 min | ~12 $/an, ou un domaine gratuit |

**Le service tourne sur le port `8000`. L'URL publique de la démo est `http://<IP-DE-LA-VM>:8000/health`.**

---

## 1. Étape 1 — le compte Vultr (Tgg, action humaine)

1. <https://my.vultr.com/register> → créer le compte (e‑mail, mot de passe).
2. **Au moment du paiement**, saisir la carte. Vultr facture à la création d'une instance ;
   c'est le seul moment où la carte est demandée. (Les **200 $ de crédits offerts** sont
   annoncés au kick‑off du **3 novembre à 16:00 UTC** : ce n'est pas une réduction, c'est un
   avoir.)
3. Créer une **clé API** : <https://my.vultr.com/settings/#settingsapi> → *API Key* →
   *Generate*. Elle sert **uniquement** à la commande `vultr-cli` de l'étape 3 (facultative).
   Elle ne sert à rien d'autre et ne doit pas être commitée.

> **Aucun de ces gestes n'a été fait par moi** : la consigne du cycle interdit de créer un
> compte et de payer. Le compte Vultr est aussi ce qui rend le challenge possible (« Challenge 1
> **must use Vultr Serverless Inference** for agent LLM calls »).

## 2. Étape 2 — la clé SSH (Tgg)

Sur **le poste de Tgg** (pas sur la VM) :

```bash
ssh-keygen -t ed25519 -C "signed-orders-vultr" -f ~/.ssh/signed_orders_vultr
cat ~/.ssh/signed_orders_vultr.pub
```

→ copier la ligne qui s'affiche (`ssh-ed25519 AAAAC3… signed-orders-vultr`) et la coller dans
<https://my.vultr.com/settings/#settingsapi> (section *SSH Keys* → *Add SSH Key*).

La clé **privée** reste sur le poste de Tgg. Elle ne sert qu'à entrer dans la VM :

```bash
ssh -i ~/.ssh/signed_orders_vultr root@<IP-DE-LA-VM>
```

> `ssh-keygen` **n'existe pas dans mon conteneur** : je n'ai donc pas pu exécuter cette
> commande ici. C'est la commande OpenSSH standard, sans variante.

## 3. Étape 3 — créer la VM Ubuntu 24.04

### 3a. La méthode recommandée : la console web, en collant le cloud-init

1. <https://my.vultr.com/deploy/> → *Deploy* (le bouton « + »).
2. **Type** : *Cloud Compute*.
3. **Location** : la région la plus proche de l'Europe (`Amsterdam` / `Paris` / `Frankfurt`).
4. **Plan** : `Regular Performance` → **2 000 MB / 1 vCPU** (10 $/mois) ou le plan
   *Cloud Compute* le moins cher. **Pas de GPU** (aucun n'est disponible pour l'événement).
5. **OS** : *Ubuntu 24.04 LTS x64*.
6. **Server Hostname** : `signed-orders`.
7. **SSH Key** : la clé de l'étape 2 (cocher la case *Auto Deploy*).
8. **User Data** (le champ *cloud-init*) : **coller le contenu de
   `deploy/cloud-init.yml`** du dépôt, tel quel.
   → <https://github.com/tgg49099-dotcom/atlassian-apps/blob/signed-orders/deploy/cloud-init.yml>
   (ou, en local : `cat deploy/cloud-init.yml`).
9. *Deploy*.

Le cloud‑init fait **tout** : il installe les paquets, clone le projet, crée le virtualenv,
**génère la clé de signature et les deux clés bearer sur la VM** (aucun secret ne voyage dans
le dépôt ni dans ce document), installe l'unité systemd, démarre le service et ouvre le port.

### 3b. La méthode par l'API (facultative, pour Tgg qui préfère la CLI)

```bash
# 1. le token de l'étape 1 (NE JAMAIS dans un fichier versionné)
export VULTR_TOKEN="…"

# 2. les identifiants exacts : ils changent, on ne les invente pas
vultr-cli plan list                       # les plans (ex. vc2-1c-2gb)
vultr-cli region list                     # les régions (ex. fra, ams)
vultr-cli os list                         # les images (Ubuntu 24.04 LTS x64)

# 3. la création, avec le cloud-init en userdata
vultr-cli instance create \
  --region="ams" \
  --plan="vc2-1c-2gb" \
  --os="<l'ID d'Ubuntu 24.04 LTS x64 trouvé ci-dessus>" \
  --host="signed-orders" \
  --userdata="$(cat deploy/cloud-init.yml)"

# 4. l'IP, quand la machine est prête
vultr-cli instance list
```

> ⚠️ Les trois identifiants (`--region`, `--plan`, `--os`) **ne sont pas en dur** dans cette
> procédure : ils changent, et une valeur fausse fait échouer la commande. Les trois commandes
> `list` ci-dessus les donnent. **La commande `vultr-cli instance create` n'a pas pu être
> exécutée ici** (aucun compte Vultr dans mon environnement) : seule sa *forme* est vérifiée
> (page officielle `docs.vultr.com/reference/vultr-cli/instance/create`).

### 3c. Si le cloud-init n'est pas possible (plan B — la liste exacte, dans l'ordre)

> Ces commandes **remplacent** le cloud-init, elles ne s'y ajoutent pas. Elles ont été écrites
> pour produire **exactement le même résultat** que le cloud-init (même utilisateur, mêmes
> répertoires, même unité systemd, mêmes clés). Elles sont données **complètes** : aucune étape
> n'est laissée « à faire plus tard ». Tout se passe **en `root`** sur la VM, après
> `apt-get update`.

```bash
# ---- 1. les paquets --------------------------------------------------------
apt-get update
apt-get install -y python3 python3-venv python3-pip git curl ca-certificates docker.io

# ---- 2. l'utilisateur et les répertoires -----------------------------------
useradd --system --home-dir /opt/signed-orders --shell /bin/bash signedorders
usermod -aG docker signedorders                 # sans ça : "permission denied" sur le socket Docker
install -d -o signedorders -g signedorders -m 0750 /opt/signed-orders
install -d -o signedorders -g signedorders -m 0750 /var/lib/signedorders
install -d -o root -g signedorders -m 0750 /etc/signedorders

# ---- 3. le projet (branche `signed-orders` : le projet seul, à la racine) ---
git clone --depth 1 --branch signed-orders \
  https://github.com/tgg49099-dotcom/atlassian-apps.git /opt/signed-orders/repo
chown -R signedorders:signedorders /opt/signed-orders/repo

# ---- 4. le virtualenv ------------------------------------------------------
runuser -u signedorders -- bash -c \
  "python3 -m venv /opt/signed-orders/.venv && \
   /opt/signed-orders/.venv/bin/pip install --upgrade pip && \
   /opt/signed-orders/.venv/bin/pip install -e '/opt/signed-orders/repo[dev]'"
chown -R signedorders:signedorders /opt/signed-orders/.venv

# ---- 5. le fichier d'environnement (22 variables, aucun secret écrit ici) --
install -m 0600 -o root -g root /dev/null /etc/signedorders/env
cat >> /etc/signedorders/env <<'ENV'
VULTRINFERENCE_API_KEY=
VULTRINFERENCE_MODEL=qwen3.8-flash-next
VULTRINFERENCE_VISION_MODEL=qwen3.8-27b
SIGNEDORDERS_AGENT_KEY=__AGENT_KEY__
SIGNEDORDERS_OPERATOR_KEY=__OPERATOR_KEY__
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
ENV

# ---- 6. les clés, générées SUR LA VM --------------------------------------
/opt/signed-orders/.venv/bin/signedorders init-key /etc/signedorders/signing.key
chown root:signedorders /etc/signedorders/signing.key
chmod 0640 /etc/signedorders/signing.key
AGENT_KEY=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
OPERATOR_KEY=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
sed -i "s|__AGENT_KEY__|$AGENT_KEY|; s|__OPERATOR_KEY__|$OPERATOR_KEY|" /etc/signedorders/env
unset AGENT_KEY OPERATOR_KEY          # les clés ne restent pas dans le shell

# ---- 7. l'unité systemd (identique à celle du cloud-init) -----------------
cp /opt/signed-orders/repo/deploy/signedorders.service /etc/systemd/system/signedorders.service
systemctl daemon-reload
systemctl enable --now signedorders.service

# ---- 8. le port et la vérification ----------------------------------------
ufw allow 8000/tcp || true
sleep 5
curl -fsS http://127.0.0.1:8000/health && echo "SIGNED ORDERS IS UP"
```

> **Note :** le plan B suppose que `deploy/signedorders.service` existe dans le dépôt. C'est le
> cas **à partir du commit publié avec ce document** : l'unité y est extraite du cloud-init
> pour être réutilisable telle quelle. Si le fichier n'y est pas encore, copier le bloc
> `[Unit]/[Service]/[Install]` de la **section 5** ci-dessous dans
> `/etc/systemd/system/signedorders.service` à la main.

> **La clé d'inférence** (facultative) se colle ensuite dans `/etc/signedorders/env`, puis
> `systemctl restart signedorders` — voir la section 8.

## 4. Étape 4 — vérifier que l'API répond

```bash
# sur la VM
systemctl status signedorders --no-pager
curl -s http://127.0.0.1:8000/health
```

**Réponse attendue** (le `public_key` change à chaque installation, c'est normal) :

```json
{"decisions": 0, "key_id": "2acbe63e009e51bb", "pending_orders": 0,
 "public_key": "qSLYxc8HxUwG02l04R6iMSrHGEAdETYK6r8PL6T7JRI=", "status": "ok"}
```

## 5. Étape 5 — l'unité systemd (déjà dans le cloud-init)

L'unité installée par le cloud-init est **le fichier `deploy/signedorders.service`** du dépôt
(littéralement le même contenu, à l'octet près — vérifié le 01/11/2026) :

```ini
[Unit]
Description=Signed Orders — containment-first control plane (HTTP API)
Documentation=https://github.com/tgg49099-dotcom/atlassian-apps/tree/signed-orders
After=network-online.target docker.service
Wants=network-online.target docker.service

[Service]
Type=simple
User=signedorders
Group=signedorders
WorkingDirectory=/opt/signed-orders/repo
EnvironmentFile=/etc/signedorders/env
ExecStart=/opt/signed-orders/.venv/bin/signedorders serve --host 0.0.0.0 --port 8000 --journal /var/lib/signedorders/journal.jsonl
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/signedorders

[Install]
WantedBy=multi-user.target
```

> `WorkingDirectory=/opt/signed-orders/repo` : le clone de la **branche `signed-orders`** place
> le projet **à la racine** du dépôt (avec son `LICENSE` MIT à la racine), donc le chemin du
> projet est simplement celui du clone.

**La commande de démarrage**, donc :

```bash
signedorders serve --host 0.0.0.0 --port 8000 --journal /var/lib/signedorders/journal.jsonl
```

*Relances et journal :*

```bash
systemctl restart signedorders
journalctl -u signedorders -f
```

## 6. Étape 6 — les clés (générées par le cloud-init, à ne jamais recopier)

Le cloud-init a généré **sur la VM** :

| Fichier | Contenu | Mode | Qui peut le lire |
|---|---|---|---|
| `/etc/signedorders/signing.key` | la clé privée Ed25519 (32 octets) qui signe les ordres et le journal | `0640` | le service (groupe `signedorders`) |
| `/etc/signedorders/env` | `VULTRINFERENCE_API_KEY`, `SIGNEDORDERS_AGENT_KEY`, `SIGNEDORDERS_OPERATOR_KEY`, les quotas, le sandbox | `0600` | root seul (systemd le lit au démarrage) |

**Pour les utiliser à la main** (par exemple pour la vidéo), Tgg les lit **sur la VM** :

```bash
sudo grep SIGNEDORDERS_AGENT_KEY /etc/signedorders/env      # la clé de l'agent
sudo grep SIGNEDORDERS_OPERATOR_KEY /etc/signedorders/env    # la clé de l'opérateur
```

⚠️ **Jamais** dans un fichier du dépôt, **jamais** dans une capture d'écran, **jamais** dans la
vidéo. La clé de l'agent ne sert qu'à `POST /orders` ; la clé de l'opérateur, à approuver ou
refuser un ordre.

## 7. Étape 7 — l'URL publique

1. **Trouver l'IP** : dans la console Vultr, sur la page de l'instance ; ou
   `vultr-cli instance list` ; ou, depuis n'importe quelle machine :
   ```bash
   dig +short signed-orders.example.com        # si un domaine a été configuré
   ```
2. **Ouvrir le port dans le pare-feu Vultr** : <https://my.vultr.com/settings/#firewall> — si
   un *firewall group* est attaché à l'instance, ajouter une règle **TCP 8000** en entrée.
   (Le `ufw allow 8000/tcp` du cloud-init ne suffit pas si le pare-feu Vultr est actif.)
3. **Tester depuis le poste de Tgg** :
   ```bash
   curl -s http://<IP-DE-LA-VM>:8000/health
   bash deploy/verifier-deploiement.sh http://<IP-DE-LA-VM>:8000
   ```
   Le vérificateur affiche 6 `PASS` et sort avec le code **0**.

**Les 5 routes publiques en lecture** (elles sont la surface de la démo) :

| Route | Ce qu'elle renvoie |
|---|---|
| `GET /health` | l'état, la clé **publique**, les compteurs |
| `GET /policy` | la table des règles (14 règles nommées) |
| `GET /quotas` | les compteurs de quota d'un agent et d'une tâche |
| `GET /orders` | les ordres en attente et les décisions prises |
| `GET /journal/verify` | la chaîne de hachage vérifiée **à chaud** |

Et **2 routes en écriture**, qui demandent un *bearer* :

| Route | Qui | Effet |
|---|---|---|
| `POST /orders` | l'agent (clé `SIGNEDORDERS_AGENT_KEY`) | soumet un ordre → `201` |
| `POST /orders/<id>/approve` · `/reject` | un humain (clé `SIGNEDORDERS_OPERATOR_KEY`) | décision **signée** → `200` |

## 8. Étape 8 (optionnelle) — brancher le modèle

1. <https://my.vultr.com/settings/#api> → section *AI / Serverless Inference* → *API Keys* →
   *Generate*. La clé s'affiche **une seule fois**.
2. La mettre sur la VM **sans la mettre dans l'historique du shell** :
   ```bash
   sudo nano /etc/signedorders/env      # remplacer VULTRINFERENCE_API_KEY= par la valeur
   sudo systemctl restart signedorders
   ```
   (ou, avant de coller le cloud-init dans la console, remplacer la valeur vide de
   `VULTRINFERENCE_API_KEY=` par la clé : elle ne sort alors jamais de la console Vultr).
3. Vérifier :
   ```bash
   curl -s -H "Authorization: Bearer $VULTRINFERENCE_API_KEY" \
        https://api.vultrinference.com/v1/models | head -c 200
   ```

Sans cette clé, **tout le reste fonctionne** : la politique, les quotas, la signature, le
journal et la démonstration du moment de confinement.

## 9. Étape 9 (optionnelle) — un nom de domaine et HTTPS

```bash
# sur la VM : Caddy s'installe tout seul et obtient un certificat Let's Encrypt
sudo apt-get update && sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt-get update && sudo apt-get install -y caddy
```

`/etc/caddy/Caddyfile` :

```
demo.signed-orders.example.com {
    reverse_proxy 127.0.0.1:8000
}
```

```bash
sudo systemctl reload caddy
```

→ l'URL de la démo devient `https://demo.signed-orders.example.com/health`.

## 10. Ce que j'ai **vérifié** ici, et ce que je n'ai **pas** pu vérifier

| Élément | État | Comment |
|---|---|---|
| `signedorders serve --host … --port … --journal …` | ✅ **exécuté** | le serveur a démarré, `/health` a répondu **200**, `POST /orders` **401** sans clé et **201** avec la clé, `/journal/verify` **200** |
| `bash scripts/demo-containment.sh` | ✅ **exécuté** | la démonstration complète du moment de confinement, sortie reproduite dans `DEMO-FILMEE.md` |
| `bash deploy/verifier-deploiement.sh <url>` | ✅ **exécuté** | 6 `PASS`, code de sortie 0 |
| `deploy/cloud-init.yml` | ✅ **syntaxe vérifiée** | YAML valide (`python3` + PyYAML) ; chaque commande est standard sur Ubuntu 24.04 |
| `deploy/signedorders.service` | ✅ **syntaxe vérifiée** | l'unité systemd est **identique à l'octet près** à celle écrite par le cloud-init (`diff` sur le contenu extrait du YAML → aucune différence hors commentaires) ; `systemd-analyze verify` est indiqué ci-dessous |
| `deploy/verifier-deploiement.sh`, `scripts/demo-containment.sh` | ✅ **syntaxe vérifiée** | `bash -n` passe sur les deux |
| Les tests | ✅ **exécutés** | `pytest` → **`217 passed, 1 skipped`** (218 tests au total ; le 218ᵉ croise l'Ed25519 pur avec la bibliothèque optionnelle `cryptography` : `pip install -e ".[reference]"` → `218 passed`) |
| La création du compte Vultr, la carte, la VM, le paiement | ❌ **pas fait** | geste humain, interdit par la consigne du cycle |
| `vultr-cli instance create …` | ❌ **pas exécuté** | aucun compte Vultr ici ; seule la forme de la commande est vérifiée sur la documentation officielle |
| `ssh-keygen` | ❌ **pas exécuté** | `ssh-keygen` n'existe pas dans ce conteneur |

## 11. En cas de panne

| Symptôme | Cause probable | Correctif |
|---|---|---|
| `curl: (7) Failed to connect` | le service n'écoute pas | `journalctl -u signedorders -n 50 --no-pager` |
| `WARNING: no agent key configured` au démarrage | `/etc/signedorders/env` illisible ou clé absente | `sudo chmod 600 /etc/signedorders/env && sudo systemctl restart signedorders` |
| `Connection timed out` depuis l'extérieur | pare-feu Vultr ou `ufw` | ouvrir TCP 8000 dans les deux |
| `Signed Orders did not start` (message du cloud-init) | le `pip install` a échoué (réseau) | `sudo -u signedorders /opt/signed-orders/.venv/bin/pip install -e '/opt/signed-orders/repo[dev]'` puis `sudo systemctl restart signedorders` |
| `permission denied while trying to connect to the Docker daemon socket` | l'utilisateur du service n'est pas dans le groupe `docker` | `sudo usermod -aG docker signedorders && sudo systemctl restart signedorders` (et reconnecter la session SSH pour que le groupe soit pris en compte) |
| le port 8000 est déjà pris | un autre service | `signedorders serve --host 0.0.0.0 --port 8080` + changer le port dans l'unité systemd |
