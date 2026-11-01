# DÉMO FILMÉE — le plan de tournage, minute par minute

> **Format :** MP4 (H.264), **4 min 30**, < 300 Mo, **sous-titres intégrés** (le jury peut
> regarder la vidéo sans son). **Narration en anglais**, voix neutre, ~135 mots/min, pas de
> musique.
>
> **La scène 4 est le « containment moment »** exigé par le challenge 01
> (« One containment moment shown in the video »). **C'est la scène qui compte** : si le temps
> manque, on coupe la scène 3, jamais la scène 4.
>
> ⚠️ **Honnêteté avant tout** : la scène 3 suppose la **boucle agent** (le modèle qui conduit
> la mission). Cette boucle **n'est pas encore dans le dépôt** (elle est écrite pendant la
> fenêtre du concours, du 3 au 8 novembre). La scène 3 est donc **conditionnelle** : soit le
> jury la voit tournée après branchement de la boucle, **soit elle est coupée** et la vidéo
> commence à la scène 4. **Ne jamais filmer une interface qui n'existe pas.** Tout le reste
> de ce plan tourne **aujourd'hui**, avec le code publié.

---

## 1. Les 5 captures à faire (la liste exacte)

| # | Capture | Ce qu'on voit | Où elle est prise |
|---|---|---|---|
| **A** | Le dépôt public | la page GitHub du projet, le `LICENSE` MIT, le compteur de tests | navigateur, `github.com/tgg49099-dotcom/atlassian-apps/tree/main/agent-rush/signed-orders` |
| **B** | La preuve en une commande | un terminal qui lance `bash scripts/demo-containment.sh` et affiche le verdict `refused` | le poste de Tgg (ou la VM) |
| **C** | ⭐ **Le moment de confinement** | la sortie de la démonstration : `rm -rf /srv/data` → `queued_for_human` → **rejected**, et `host:///etc/app/secret` → **refused (403)** | idem, plein écran, sans défilement |
| **D** | L'isolation, en vrai | la ligne `docker run` exécutée **sur la VM** (sans réseau, 256 Mo, 1 CPU, 128 PID, root en lecture seule), et un `curl` vers l'extérieur qui **échoue** | la VM Vultr, terminal SSH |
| **E** | Le journal vérifié **hors ligne** | `python3 -m signedorders verify journal.jsonl` → `OK — the chain is intact (N entries checked)` | le poste de Tgg, sur le fichier de journal téléchargé |

> Les captures **D** et **E** sont les deux preuves qui répondent « et alors ? ». **B et C**
> portent le moment de confinement.

## 2. Le scénario, minute par minute

### SCÈNE 1 — « Blast Radius » (0:00 → 0:25)

**À l'écran :** carton-titre, puis le dépôt public (capture A), la licence MIT visible.

> **Voix (EN) :** *"An agent that does real work can break real things. A shell command, a
> deleted file, a request to the wrong address. We call that the blast radius. Most agent
> frameworks hand the agent a shell and hope for the best. We hand the agent nothing — only
> the right to ask."*

### SCÈNE 2 — L'architecture en 25 secondes (0:25 → 0:50)

**À l'écran :** le schéma (plan de contrôle → agent → ordres signés → exécuteur → bac à sable →
journal), puis le README, section « How Vultr powers this ».

> **Voix (EN) :** *"One Vultr VM runs the control plane. The agent runs inside a sandbox and
> has exactly one privilege: it can request an order. The control plane classifies the risk
> with named rules, enforces the quotas, and signs the order with an Ed25519 key. The
> executor runs it in a brand-new container, which is destroyed right after. The private key
> never leaves the server — but the public key is in the repository, so anyone can verify our
> journal offline, with no secret at all."*

### SCÈNE 3 — *(conditionnelle)* LE TRAVAIL RÉEL (0:50 → 1:40)

> **À utiliser seulement si la boucle agent est branchée et filmée pour de vrai.** Sinon :
> **on coupe** et on gagne 50 secondes, que l'on donnent à la scène 4.
>
> À l'écran : la mission — *"The checkout-api service is failing. Find the cause, fix it, and
> prove it works. You must not touch customer data."* — puis les ordres : lecture du log
> (`low`, exécuté), le correctif (`low`), le test (`low`) qui **échoue**, le même test qui
> **réussit** (la boucle de reprise), l'application du correctif (`medium`, snapshot
> automatique), le redémarrage, et enfin `500 → 200` avec la réponse JSON affichée.
>
> **Voix (EN) :** *"Real work. It read the log, wrote a fix, ran the tests, read the failure,
> fixed it, and applied it. Each of those six steps is a signed order with a risk level and a
> rule that decided. Note the failing test: the error was fed back to the model, and it tried
> again. The service was dead at 500. It is 200 now — that is the receipt."*

### SCÈNE 4 — ⭐ LE MOMENT DE CONFINEMENT (1:40 → 3:20) — **la scène décisive**

**À l'écran :** la démonstration réelle (capture B puis C), en plein écran, sans défilement
pendant la narration.

> **Voix (EN) :** *"Now the moment this challenge is about. A hostile instruction inside a file
> the agent reads makes the agent request two orders. First: delete all the customer data.
> Second: send the API key to an address we do not control.*
>
> *The control plane does not care how confident the model is. The first order is classified
> high risk and it stops — it waits for a human, and it does not run. The second one is
> forbidden: no human approval can ever unlock it. Both are written to the journal, signed,
> and the chain is verifiable offline.*

**Les 3 étapes à filmer, dans cet ordre (le déroulé réel est dans `scripts/demo-containment.sh`) :**

| Étape | Ce que l'agent demande | Ce que le plan de contrôle répond | Sortie à l'écran |
|---|---|---|---|
| **4a** | `rm -rf /srv/data` (destruction) | `risk.destructive` → **mis en attente d'un humain** | `verdict: queued_for_human` |
| **4b** | le même ordre, **approuvé/refusé par un humain** | refus **signé**, écrit au journal | `verdict: rejected` — « the blast radius was one order that never ran » |
| **4c** | `read host:///etc/app/secret` (évasion) | `forbidden.escape` → **refusé, aucun human ne peut le débloquer** | `HTTP 403` — `verdict: refused` |

> **Voix (EN), sur 4c :** *"And even if a human had approved the deletion, the sandbox still
> absorbs it: no network, 256 megabytes, one CPU, a read-only root filesystem, no capabilities,
> no privileges, and a wall-clock timeout. The blast radius is one container that is thrown
> away."*

**Capture D (à insérer entre 4b et 4c, 10 secondes) :** la ligne `docker run` réellement
exécutée sur la VM, et un `curl` vers l'extérieur qui **échoue** (because `--network=none`).

> **Voix (EN) :** *"Here is the isolation, running: no network, 256 megabytes, one CPU, 128
> processes, a read-only filesystem, every capability dropped, and the container is destroyed
> when it exits. The call to the outside world fails, because there is no outside world in
> there."*

### SCÈNE 5 — Rejouer, vérifier, et « et alors ? » (3:20 → 4:30)

**À l'écran :** capture E — le journal téléchargé et vérifié **hors ligne**, sans clé, sans
serveur ; puis le compteur de tests.

```bash
python3 -m signedorders verify journal.jsonl     # → OK — the chain is intact (N entries checked)
pytest                                            # → 218 passed
```

> **Voix (EN) :** *"Everything we just showed is written down. The journal is a hash chain:
> if anyone edits a single record afterwards, the verification fails and says exactly where.
> You can check it yourself — clone the repository, run the tests, and replay our story. No
> key, no account, no server.*
>
> *An agent that only chats is a demo. An agent that executes safely is a product. Signed
> Orders is the part that decides what an agent is allowed to do — and proves it afterwards.
> Thank you."*

---

## 3. Les commandes exactes à taper à l'écran

```bash
# la démonstration du moment de confinement (B + C) — aucune clé, aucun compte
cd atlassian-apps/agent-rush/signed-orders
bash scripts/demo-containment.sh

# la vérification hors ligne du journal (E)
python3 -m signedorders verify journal.jsonl
pytest

# l'isolation en direct (D), sur la VM
docker run --rm --name=sb-demo --network=none --memory=256m --cpus=1 --pids-limit=128 \
  --read-only --cap-drop=ALL --security-opt=no-new-privileges --user=65534:65534 \
  --tmpfs=/tmp:rw,noexec,nosuid,size=64m --volume /tmp/sb-demo:/work:rw --workdir=/work \
  debian:bookworm-slim /work/run-in-sandbox.sh sh -c 'echo inside; (curl -s https://example.com || echo "NO NETWORK")'
```

## 4. La liste de contrôle « scrub » (à cocher avant l'export)

| ☐ | Contrôle |
|---|---|
| ☐ | **Aucune clé** à l'écran : ni `SIGNEDORDERS_AGENT_KEY`, ni `SIGNEDORDERS_OPERATOR_KEY`, ni la clé d'inférence, ni `/etc/signedorders/env` en entier |
| ☐ | **Aucune IP de VM** à l'écran : le terminal de la capture D est flouté, ou le hostname est remplacé |
| ☐ | Aucune donnée « client » : les données de la démo sont fictives, le « secret » est `DEMO_ONLY_NOT_A_REAL_KEY` |
| ☐ | La vidéo dure **moins de 5 minutes** et pèse **moins de 300 Mo** |
| ☐ | Les sous-titres sont **intégrés** (pas de fichier `.srt` séparé) |
| ☐ | L'audio est ** intelligible** (volume constant, sans musique) |
| ☐ | Le **containment moment** est visible à l'écran (le `refused` / `rejected` en gros, lisible) |
| ☐ | Le lien de la vidéo est **public et non listé** (YouTube « Unlisted » suffit) |

## 5. Le plan de secours (si la démo plante pendant le tournage)

| Panne | Solution |
|---|---|
| la VM ne répond plus | filmer la capture B/C **en local** sur le poste de Tgg : `scripts/demo-containment.sh` fait la même chose sans la VM |
| pas de Docker sur la VM | sauter la capture D et dire « the isolation is published as data, in the manifest, and the test asserts it matches the code » — **et ne pas prétendre l'avoir exécutée** |
| la boucle agent n'est pas prête | **couper la scène 3** (elle est conditionnelle) |
| la vidéo est trop longue | couper la scène 2 (le schéma) — **jamais** la scène 4 |

## 6. Le texte exact de la soumission dans lablab.ai (rappel)

Le texte à coller est dans `SOUMISSION-EN.md` (section 2 et 3). La ligne de la démo et celle de
la vidéo sont les deux seules choses à remplir :

| Champ | Valeur |
|---|---|
| Demo URL | `http://<IP-DE-LA-VM>:8000/health` |
| Video | l'URL YouTube de la vidéo (non listée) |
