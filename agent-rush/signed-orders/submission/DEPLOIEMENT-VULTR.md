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
   → <https://github.com/tgg49099-dotcom/atlassian-apps/blob/main/agent-rush/signed-orders/deploy/cloud-init.yml>
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

### 3c. Si le cloud-init n'est pas possible (plan B, 4 commandes)

```bash
# sur la VM, en root
apt-get update && apt-get install -y python3 python3-venv python3-pip git curl docker.io
git clone --depth 1 https://github.com/tgg49099-dotcom/atlassian-apps.git /opt/signed-orders/repo
python3 -m venv /opt/signed-orders/.venv
/opt/signed-orders/.venv/bin/pip install -e "/opt/signed-orders/repo/agent-rush/signed-orders[dev]"
install -m 0750 -o root -g signedorders -d /etc/signedorders   # (créer l'utilisateur avant)
```

puis reprendre la section 5 (unité systemd) et la section 6 (les clés).

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

L'unité installée par le cloud-init est `deploy/` :

```ini
[Unit]
Description=Signed Orders — containment-first control plane (HTTP API)
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=signedorders
Group=signedorders
WorkingDirectory=/opt/signed-orders/repo/agent-rush/signed-orders
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

**Les 4 routes publiques** (elles sont la surface de la démo) :

| Route | Ce qu'elle renvoie |
|---|---|
| `GET /health` | l'état, la clé **publique**, les compteurs |
| `GET /policy` | la table des règles (14 règles nommées) |
| `GET /orders` | les ordres en attente et les décisions prises |
| `GET /journal/verify` | la chaîne de hachage vérifiée **à chaud** |

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
| `deploy/verifier-deploiement.sh`, `scripts/demo-containment.sh` | ✅ **syntaxe vérifiée** | `bash -n` passe sur les deux |
| Les 218 tests | ✅ **exécutés** | `pytest` → `218 passed` |
| La création du compte Vultr, la carte, la VM, le paiement | ❌ **pas fait** | geste humain, interdit par la consigne du cycle |
| `vultr-cli instance create …` | ❌ **pas exécuté** | aucun compte Vultr ici ; seule la forme de la commande est vérifiée sur la documentation officielle |
| `ssh-keygen` | ❌ **pas exécuté** | `ssh-keygen` n'existe pas dans ce conteneur |

## 11. En cas de panne

| Symptôme | Cause probable | Correctif |
|---|---|---|
| `curl: (7) Failed to connect` | le service n'écoute pas | `journalctl -u signedorders -n 50 --no-pager` |
| `WARNING: no agent key configured` au démarrage | `/etc/signedorders/env` illisible ou clé absente | `sudo chmod 600 /etc/signedorders/env && sudo systemctl restart signedorders` |
| `Connection timed out` depuis l'extérieur | pare-feu Vultr ou `ufw` | ouvrir TCP 8000 dans les deux |
| `Signed Orders did not start` (message du cloud-init) | le `pip install` a échoué (réseau) | `sudo -u signedorders /opt/signed-orders/.venv/bin/pip install -e '/opt/signed-orders/repo/agent-rush/signed-orders[dev]'` puis `sudo systemctl restart signedorders` |
| le port 8000 est déjà pris | un autre service | `signedorders serve --host 0.0.0.0 --port 8080` + changer le port dans l'unité systemd |
