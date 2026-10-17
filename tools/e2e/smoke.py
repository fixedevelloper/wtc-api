#!/usr/bin/env python3
"""Essai de bout en bout de l'API (stdlib seule) : ce que font le back-office et l'application, dans l'ordre réel.

  administrateur (2FA) -> second administrateur -> client (inscription, pièce d'identité examinée) -> ajustement de solde à
  double validation -> expéditeur, bénéficiaire, compte -> devis -> transfert idempotent -> paiement par le faux fournisseur ->
  reçu PDF -> jeton web du chatbot.

Rejouable : les comptes existants sont réutilisés (clés TOTP gardées dans .local/e2e/). Laisse les données en place pour
l'essai manuel dans le navigateur et sur l'émulateur ; affiche à la fin les identifiants.   Prérequis : tools/e2e/start.sh
"""
import base64, json, os, pathlib, subprocess, sys, time, urllib.error, urllib.parse, urllib.request, uuid

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from totp import code as totp_code  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[2]
STATE = ROOT / ".local/e2e"
API = os.environ.get("E2E_API", "http://localhost:8080") + "/api"
ADMIN1 = (os.environ.get("E2E_ADMIN_EMAIL", "admin@wtc.local"), os.environ.get("E2E_ADMIN_PASSWORD", "Cobalt-Lanterne-Verger-42"))
ADMIN2 = ("support@wtc.local", "Lanterne-Verger-Cobalt-43")
CUSTOMER = ("awa.ndiaye@example.test", "Verger-Cobalt-Lanterne-44")
SERVICE_KEY = os.environ.get("E2E_SERVICE_KEY", "e2e-service-key-0123456789abcdef0123456789abcdef")
PG = os.environ.get("E2E_PG_CONTAINER", "wtc-e2e-pg")

# JPEG 1x1 valide
JPEG = base64.b64decode("/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=")

failures: list[str] = []


def check(ok: bool, label: str, detail: str = ""):
    print(("  ✔ " if ok else "  ✘ ") + label + ("" if ok or not detail else f"  -> {detail}"))
    if not ok:
        failures.append(label)


class Reply:
    def __init__(self, status, body, headers):
        self.status, self.raw, self.headers = status, body, headers

    @property
    def json(self):
        try:
            return json.loads(self.raw) if self.raw else None
        except ValueError:
            return None


def call(method, path, body=None, token=None, headers=None, raw=None, ctype=None) -> Reply:
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    h = {"Accept": "application/json, application/pdf, application/problem+json"}
    if data is not None:
        h["Content-Type"] = ctype or "application/json"
    if token:
        h["Authorization"] = f"Bearer {token}"
    h.update(headers or {})
    req = urllib.request.Request(API + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return Reply(r.status, r.read(), dict(r.headers))
    except urllib.error.HTTPError as e:
        return Reply(e.code, e.read(), dict(e.headers))


def psql(sql: str) -> str:
    return subprocess.run(["docker", "exec", "-i", PG, "psql", "-U", "wtc", "-d", "wtc", "-At", "-v", "ON_ERROR_STOP=1"], input=sql,
                          capture_output=True, text=True, check=True).stdout.strip()


def multipart(fields: dict, file_field: str, filename: str, content: bytes, ctype: str):
    boundary = "----e2e" + uuid.uuid4().hex
    parts = []
    for k, v in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\nContent-Type: {ctype}\r\n\r\n'.encode()
                 + content + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def secret_file(email):
    return STATE / (email.split("@")[0] + ".secret")


def login_staff(email, password) -> str:
    """Connexion d'un membre du personnel avec 2FA : active le second facteur à la première fois, puis le code TOTP."""
    r = call("POST", "/auth/login", {"email": email, "password": password})
    assert r.status == 200, f"connexion de {email} : {r.status} {r.raw[:200]}"
    j = r.json
    sf = secret_file(email)
    if "mfaRequired" in j:  # déjà activée
        secret = sf.read_text().strip()
        for attempt in range(2):
            v = call("POST", "/auth/mfa/verify", {"mfaToken": j["mfaToken"], "code": totp_code(secret)})
            if v.status == 200:
                return v.json["accessToken"]
            # un code déjà utilisé dans la même fenêtre de 30 s est refusé (anti-rejeu) : on attend la suivante
            time.sleep(30 - time.time() % 30 + 1)
        raise AssertionError(f"2FA de {email} : {v.status} {v.raw[:200]}")
    # première connexion : le jeton ne permet que d'activer la 2FA
    e = call("POST", "/me/mfa/enrollment", token=j["accessToken"])
    assert e.status in (200, 201), f"activation 2FA : {e.status} {e.raw[:200]}"
    secret = e.json["secret"]
    sf.write_text(secret)
    c = call("POST", "/me/mfa/confirmation", {"code": totp_code(secret)}, token=j["accessToken"])
    assert c.status == 204, f"confirmation 2FA : {c.status} {c.raw[:200]}"
    # un code TOTP ne sert qu'une fois : la connexion suivante attend la fenêtre de 30 s suivante
    time.sleep(30 - time.time() % 30 + 1)
    return login_staff(email, password)


def upsert_party(path: str, payload: dict, token: str) -> Reply:
    """Crée la personne, ou met à jour la première déjà enregistrée (l'essai est rejouable sans doublons)."""
    existing = call("GET", path, token=token).json["content"]
    if existing:
        return call("PUT", f"{path}/{existing[0]['id']}", payload, token=token)
    return call("POST", path, payload, token=token)


def idem():
    return {"Idempotency-Key": str(uuid.uuid4())}


def main():
    STATE.mkdir(parents=True, exist_ok=True)
    print("\n== 0. API disponible")
    for _ in range(30):
        try:
            if call("GET", "/countries").status == 200:
                break
        except Exception:
            time.sleep(2)
    cs = call("GET", "/countries")
    check(cs.status == 200 and any(c["code"] == "CG" for c in cs.json), "référentiels chargés (pays)")
    req = call("GET", "/meta/client-requirements")
    check(req.status == 200, "exigences de version (Android/web)")

    print("\n== 1. Personnel : deux administrateurs avec double authentification")
    a1 = login_staff(*ADMIN1)
    check(bool(a1), f"{ADMIN1[0]} connecté (2FA)")
    # second administrateur : créé en base (l'invitation par e-mail est simulée par un faux canal, illisible ici)
    h = subprocess.run(["htpasswd", "-bnBC", "10", "", ADMIN2[1]], capture_output=True, text=True, check=True).stdout.strip().lstrip(":")
    psql(f"""INSERT INTO users (email, password_hash, display_name, status, email_verified_at)
             VALUES ('{ADMIN2[0]}', '{h}', 'Support Essai', 'ACTIVE', now()) ON CONFLICT DO NOTHING;
             INSERT INTO user_roles (user_id, role_code) SELECT id, 'ADMIN' FROM users WHERE email = '{ADMIN2[0]}' ON CONFLICT DO NOTHING;""")
    a2 = login_staff(*ADMIN2)
    check(bool(a2), f"{ADMIN2[0]} connecté (2FA) — ancien hash bcrypt accepté")
    me = call("GET", "/me", token=a1)
    check(me.status == 200 and "SUPER_ADMIN" in me.json["user"]["roles"], "profil du super-administrateur")
    check(call("GET", "/admin/customers", token=a1).status == 200, "liste des clients (back-office)")

    print("\n== 2. Client : inscription et identité vérifiée")
    reg = call("POST", "/auth/register", {"email": CUSTOMER[0], "password": CUSTOMER[1], "firstName": "Awa", "lastName": "Ndiaye",
                                          "phone": "+237690000111", "countryCode": "CM"})
    check(reg.status in (201, 409), "inscription", f"{reg.status} {reg.raw[:200]}")
    lg = call("POST", "/auth/login", {"email": CUSTOMER[0], "password": CUSTOMER[1]})
    check(lg.status == 200 and "accessToken" in lg.json, "connexion du client", f"{lg.status} {lg.raw[:200]}")
    ct = lg.json["accessToken"]
    cid = lg.json["user"]["customerId"]
    kyc = call("GET", "/me/kyc", token=ct)
    if kyc.json["status"] != "VERIFIED":
        body, ctype = multipart({"documentType": "NATIONAL_ID", "side": "SINGLE", "expiresOn": "2032-01-01"}, "file", "cni.jpg", JPEG, "image/jpeg")
        up = call("POST", "/me/kyc/documents", raw=body, ctype=ctype, token=ct)
        check(up.status == 201, "dépôt de la pièce d'identité", f"{up.status} {up.raw[:300]}")
        doc = up.json["id"] if up.status == 201 else None
        if doc:
            rv = call("POST", f"/admin/customers/{cid}/kyc/documents/{doc}/review", {"decision": "APPROVE"}, token=a2)
            check(rv.status == 200, "examen de la pièce par le personnel", f"{rv.status} {rv.raw[:300]}")
    check(call("GET", "/me/kyc", token=ct).json["status"] == "VERIFIED", "identité du client VÉRIFIÉE")

    print("\n== 3. Ajustement de solde à double validation")
    wal = call("GET", "/me/wallet", token=ct)
    before = float(wal.json["balance"]) if wal.status == 200 else 0.0
    adj = call("POST", "/admin/balance-adjustments", {"customerId": cid, "direction": "CREDIT", "amount": "500000", "reason": "Dotation essai de bout en bout",
                                                      "currency": "XAF"}, token=a1, headers=idem())
    check(adj.status == 201, "demande d'ajustement (administrateur 1)", f"{adj.status} {adj.raw[:300]}")
    aid = adj.json["id"]
    own = call("POST", f"/admin/balance-adjustments/{aid}/approve", {}, token=a1)
    check(own.status in (403, 409, 422), "l'auteur ne peut pas approuver sa propre demande", f"{own.status}")
    ok = call("POST", f"/admin/balance-adjustments/{aid}/approve", {}, token=a2)
    check(ok.status == 200, "approbation par un autre administrateur", f"{ok.status} {ok.raw[:300]}")
    after = float(call("GET", "/me/wallet", token=ct).json["balance"])
    check(abs(after - before - 500000) < 0.01, f"portefeuille crédité ({before:.0f} -> {after:.0f} XAF)")

    print("\n== 4. Expéditeur, bénéficiaire et compte du bénéficiaire")
    snd = upsert_party("/me/senders", {"kind": "INDIVIDUAL", "firstName": "Awa", "lastName": "Ndiaye", "birthDate": "1990-05-12", "gender": "F",
                                       "nationality": "CM", "occupation": "Commerçante", "addressLine": "Rue 12, Bonanjo", "city": "Douala",
                                       "countryCode": "CM", "phone": "+237690000111", "email": CUSTOMER[0], "idDocumentType": "CNI",
                                       "idDocumentNumber": "AB123456789", "idDocumentExpiresOn": "2032-01-01", "civility": "Single"}, ct)
    check(snd.status in (200, 201), "expéditeur enregistré", f"{snd.status} {snd.raw[:400]}")
    ben = upsert_party("/me/beneficiaries", {"kind": "INDIVIDUAL", "firstName": "Paul", "lastName": "Okoko", "birthDate": "1985-03-02", "gender": "M",
                                             "nationality": "CG", "occupation": "Enseignant", "addressLine": "Av. de la Paix", "city": "Brazzaville",
                                             "countryCode": "CG", "phone": "+242060000222", "idDocumentType": "CNI",
                                             "idDocumentNumber": "CG998877", "idDocumentExpiresOn": "2031-01-01", "civility": "Single"}, ct)
    check(ben.status in (200, 201), "bénéficiaire enregistré", f"{ben.status} {ben.raw[:400]}")
    sid, bid = snd.json["id"], ben.json["id"]
    check("998877" not in json.dumps(ben.json), "numéro de pièce jamais renvoyé en clair")
    bank = call("GET", "/countries/CG/banks", token=ct).json[0]["id"]
    old = call("GET", f"/me/beneficiaries/{bid}/destinations", token=ct).json
    dst = call("GET", f"/me/beneficiaries/{bid}/destinations", token=ct) if old else call("POST", f"/me/beneficiaries/{bid}/destinations", {"kind": "BANK_ACCOUNT", "countryCode": "CG", "bankId": bank,
                                                                 "accountHolderName": "Paul Okoko", "accountNumber": "CG12 0001 2345 6789"}, token=ct)
    dest = old[0] if old else dst.json
    check(dst.status in (200, 201) and dest["accountNumberMasked"].startswith("****"), "compte bancaire enregistré (numéro masqué)", f"{dst.status} {dst.raw[:300]}")
    did = dest["id"]
    purposes = call("GET", "/reference/transfer-purposes?partyTypePair=P2P", token=ct).json
    check(len(purposes) > 0, "motifs de transfert P2P")

    print("\n== 5. Devis puis transfert idempotent")
    q = call("POST", "/me/transfers/quote", {"destinationId": did, "sourceAmount": "20000"}, token=ct)
    check(q.status == 200 and q.json["sufficientFunds"], "devis du serveur", f"{q.status} {q.raw[:300]}")
    print(f"    frais {q.json['fee']['amount']} · total débité {q.json['totalDebit']['amount']} · reçu {q.json.get('destinationAmount')}")
    body = {"senderId": sid, "beneficiaryId": bid, "destinationId": did, "sourceAmount": "20000", "purposeCode": "FAMILY",
            "sourceOfFundsCode": "SALARY", "relationshipCode": "BROTHER", "comment": "Essai de bout en bout",
            "expectedTotalDebit": q.json["totalDebit"]["amount"]}
    key = {"Idempotency-Key": str(uuid.uuid4())}
    t1 = call("POST", "/me/transfers", body, token=ct, headers=key)
    check(t1.status == 201, "transfert créé", f"{t1.status} {t1.raw[:400]}")
    t2 = call("POST", "/me/transfers", body, token=ct, headers=key)
    check(t2.status in (200, 201) and t2.json["id"] == t1.json["id"], "même clé = même transfert (pas de double débit)", f"{t2.status}")
    bad = dict(body, expectedTotalDebit="1")
    t3 = call("POST", "/me/transfers", bad, token=ct, headers=idem())
    check(t3.status == 409 and t3.json.get("code") == "QUOTE_CHANGED", "total attendu périmé refusé (QUOTE_CHANGED)", f"{t3.status} {t3.raw[:200]}")
    tid, ref = t1.json["id"], t1.json["reference"]
    debited = float(call("GET", "/me/wallet", token=ct).json["balance"])
    check(abs(after - debited - float(q.json["totalDebit"]["amount"])) < 0.01, f"débité une seule fois ({after:.0f} -> {debited:.0f})")

    print("\n== 6. Paiement par le faux fournisseur (≈ 20 s)")
    status = ""
    for _ in range(40):
        status = call("GET", f"/me/transfers/{tid}", token=ct).json["status"]
        if status in ("COMPLETED", "FAILED", "REFUNDED"):
            break
        time.sleep(3)
    check(status == "COMPLETED", f"transfert {ref} payé", f"statut final {status}")
    rc = call("GET", f"/me/transfers/{tid}/receipt", token=ct)
    check(rc.status == 200 and rc.raw[:5] == b"%PDF-", "reçu PDF du serveur")
    adm = call("GET", f"/admin/transfers/{tid}", token=a2)
    check(adm.status == 200 and len(adm.json.get("history", [])) >= 3, "historique visible côté back-office")
    check(call("GET", f"/me/transfers/{tid}", token=ct).json["history"] is not None, "historique visible côté client")

    print("\n== 7. Jeton web du chatbot WhatsApp")
    wt = call("POST", "/admin/web-tokens", {"amount": "15000", "countryCode": "CG", "validForMinutes": 30}, token=a1)
    check(wt.status == 201, "jeton web créé", f"{wt.status} {wt.raw[:200]}")
    tok = wt.json["token"]
    sk = {"X-Service-Key": SERVICE_KEY}
    check(call("GET", f"/web-tokens/{tok}", headers=sk).status == 200, "lu avec la clé de service")
    check(call("GET", f"/web-tokens/{tok}").status == 401, "refusé sans clé de service")
    check(call("POST", f"/web-tokens/{tok}/consume", {"whatsappId": "242060000333"}, headers=sk).status == 204, "consommé une fois")
    check(call("POST", f"/web-tokens/{tok}/consume", {"whatsappId": "242060000333"}, headers=sk).status == 409, "deuxième consommation refusée")

    print("\n== 8. Cohérence de l'argent")
    check(psql("SELECT count(*) FROM wallet_balance_discrepancies") == "0", "soldes = grand livre")
    check(psql("SELECT count(*) FROM transfers WHERE payout_hold IS NOT NULL") == "0", "aucun transfert bloqué")

    state = {"admin": ADMIN1, "support": ADMIN2, "customer": CUSTOMER, "customerId": cid, "service_key": SERVICE_KEY}
    (STATE / "state.json").write_text(json.dumps(state, indent=2))
    print("\n" + "=" * 78)
    print("Identifiants pour l'essai manuel (les codes TOTP se calculent avec tools/e2e/totp.py) :")
    print(f"  Back-office  http://localhost:3000   {ADMIN1[0]} / {ADMIN1[1]}   (clé 2FA : {secret_file(ADMIN1[0]).read_text().strip()})")
    print(f"               code du moment : python3 tools/e2e/totp.py {secret_file(ADMIN1[0]).read_text().strip()}")
    print(f"  2e administrateur  {ADMIN2[0]} / {ADMIN2[1]}   (clé 2FA : {secret_file(ADMIN2[0]).read_text().strip()})")
    print(f"  Client (appli Android)  {CUSTOMER[0]} / {CUSTOMER[1]}")
    print("=" * 78)
    if failures:
        print(f"\n{len(failures)} vérification(s) en échec :")
        for f in failures:
            print("  -", f)
        sys.exit(1)
    print("\nToutes les vérifications sont passées.")


if __name__ == "__main__":
    main()
