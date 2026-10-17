#!/usr/bin/env python3
"""Ajoute à docs/phase0/routes.csv la décision de migration et la nouvelle route (base unique /api, sans version).

Règles : les routes appelées par Android ou le web sont portées ; les alias /v3 sont déjà couverts par /v2/admin ;
/v1 n'a aucun appelant connu et n'est pas porté ; ce qui n'a pas d'appelant connu est « à décider ».
Usage : python3 tools/routes_inventory.py && python3 tools/route_map.py
"""
import csv, re, pathlib, collections

OUT = pathlib.Path(__file__).resolve().parents[1] / 'docs' / 'phase0'
N = lambda p: re.sub(r'\{[^}]+\}', '{}', p.strip('/'))

# (méthode, ancien chemin normalisé) -> (nouvelle méthode, nouvelle route, note)
MAP = {
    # --- authentification -----------------------------------------------------------------
    ('POST', 'auth/logincustomer'): ('POST', '/api/auth/login', 'Connexion unique ; réponse = surensemble des trois formats actuels (token, refresh_token, id, customer_id, email, name, roles, photoURL).'),
    ('POST', 'auth/signin'): ('POST', '/api/auth/login', 'Même endpoint que ci-dessus (implémentation active : V2\\AuthController::login).'),
    ('POST', 'v2/auth/login'): ('POST', '/api/auth/login', 'Alias admin de /auth/v3/login.'),
    ('POST', 'auth/v3/login'): ('POST', '/api/auth/login', 'Login du back-office (web).'),
    ('POST', 'auth/register'): ('POST', '/api/auth/register', 'Implémentation active : V2\\AuthController::register (la copie de SecurityController est masquée).'),
    ('POST', 'auth/refresh'): ('POST', '/api/auth/refresh', 'À renforcer : rotation et révocation du refresh token.'),
    # --- profil et données du client connecté (l'identité vient du jeton : plus d'id client dans l'URL) ---
    ('GET', 'v2/profile/{}'): ('GET', '/api/me', ''),
    ('POST', 'v2/profile'): ('PATCH', '/api/me', ''),
    ('POST', 'v2/change_password'): ('PUT', '/api/me/password', ''),
    ('GET', 'v2/senders/{}'): ('GET', '/api/me/senders', 'Pagination page/limit.'),
    ('GET', 'v2/all_senders/{}'): ('GET', '/api/me/senders/options', 'Liste complète non paginée (listes déroulantes).'),
    ('POST', 'v2/senders'): ('POST', '/api/me/senders', ''),
    ('GET', 'v2/senders/{}/{}/one'): ('GET', '/api/me/senders/{senderId}', ''),
    ('POST', 'v2/senders/update/{}'): ('PUT', '/api/me/senders/{senderId}', ''),
    ('POST', 'v2/sender/update'): ('PUT', '/api/me/senders/{senderId}', 'Ancien chemin sans appelant : fusionné avec la route ci-dessus.'),
    ('GET', 'v2/beneficiaries/{}'): ('GET', '/api/me/beneficiaries', 'Pagination page/limit.'),
    ('GET', 'v2/all_beneficiaries/{}'): ('GET', '/api/me/beneficiaries/options', ''),
    ('POST', 'v2/beneficiaries'): ('POST', '/api/me/beneficiaries', ''),
    ('GET', 'v2/beneficiaries/{}/one'): ('GET', '/api/me/beneficiaries/{beneficiaryId}', ''),
    ('POST', 'v2/beneficiaries/update/{}'): ('PUT', '/api/me/beneficiaries/{beneficiaryId}', ''),
    ('GET', 'v2/transactions/{}'): ('GET', '/api/me/transactions', 'Pagination page/limit.'),
    ('GET', 'v2/last_transactions'): ('GET', '/api/me/transactions/latest', ''),
    ('GET', 'v2/last_transactions/{}'): ('GET', '/api/me/transactions/latest', 'Le paramètre était un id utilisateur : supprimé.'),
    ('GET', 'v2/transactions/detail/{}'): ('GET', '/api/me/transactions/{transactionId}', ''),
    ('POST', 'v2/transferts/bank'): ('POST', '/api/me/transactions/bank', 'Cœur financier : TransferService.'),
    ('POST', 'v2/transferts/mobile'): ('POST', '/api/me/transactions/mobile', 'Cœur financier : TransferService.'),
    ('GET', 'v2/recharges/{}'): ('GET', '/api/me/recharges', 'Pagination page/limit.'),
    ('POST', 'v2/recharges'): ('POST', '/api/me/recharges', 'Recharge par carte (Stripe Checkout).'),
    ('POST', 'v2/recharges/upload'): ('POST', '/api/me/recharges/proof', 'Recharge manuelle avec preuve ; à restreindre (type/taille) et stocker hors dossier public.'),
    ('POST', 'v2/recharges/proof'): (None, None, 'Aucun appelant : complète la preuve d\'une recharge existante.'),
    # --- référentiels -----------------------------------------------------------------------
    ('GET', 'v2/countries'): ('GET', '/api/countries', 'Public.'),
    ('GET', 'api/countries'): ('GET', '/api/countries', 'Doublon de /v2/countries.'),
    ('GET', 'v2/cities/{}'): ('GET', '/api/countries/{countryId}/cities', 'Public.'),
    ('GET', 'v2/cities/{}/codeiso'): ('GET', '/api/countries/{isoCode}/cities', 'Variante par code ISO (le client choisit id ou ISO).'),
    ('GET', 'api/cities/{}/codeiso'): ('GET', '/api/countries/{isoCode}/cities', 'Doublon.'),
    ('GET', 'v2/banklists/{}'): ('GET', '/api/countries/{countryId}/banks', ''),
    ('GET', 'api/banklists/{}'): ('GET', '/api/countries/{countryId}/banks', 'Doublon ; aujourd\'hui public.'),
    ('GET', 'v2/operatorslists/{}'): ('GET', '/api/countries/{countryId}/mobile-operators', ''),
    ('GET', 'api/operatorslists/{}'): ('GET', '/api/countries/{countryId}/mobile-operators', 'Doublon ; aujourd\'hui public.'),
    ('GET', 'v2/operators/{}'): ('GET', '/api/countries/{countryId}/payment-operators', 'Opérateurs de paiement (gateway AGENSICPAY ou WACEPAY selon le pays).'),
    ('GET', 'v2/tauxechanges/{}'): ('GET', '/api/countries/{countryId}/exchange-rate', 'Renvoie rate (% de frais de la tranche), taux_xaf_usd, taux_country.'),
    ('GET', 'api/tauxechanges/{}'): ('GET', '/api/countries/{countryId}/exchange-rate', 'Doublon.'),
    ('GET', 'v2/wace_data'): ('GET', '/api/reference/wace-services', ''),
    # --- back-office (alias /v3 couverts par /v2/admin) ----------------------------------------
    ('GET', 'v2/admin/customers'): ('GET', '/api/admin/customers', ''),
    ('GET', 'v2/admin/customers/{}'): ('GET', '/api/admin/customers/{customerId}', ''),
    ('GET', 'v2/admin/customers/senders/{}'): ('GET', '/api/admin/customers/{customerId}/senders', ''),
    ('GET', 'v2/admin/all_beneficiaries/{}'): ('GET', '/api/admin/customers/{customerId}/beneficiaries', ''),
    ('GET', 'v2/admin/all_senders/{}'): ('GET', '/api/admin/senders/options', 'Tous les expéditeurs (liste déroulante).'),
    ('GET', 'v2/admin/senders'): ('GET', '/api/admin/senders', ''),
    ('GET', 'v2/admin/senders/{}/one'): ('GET', '/api/admin/senders/{senderId}', ''),
    ('POST', 'v2/admin/senders/{}/update'): ('PUT', '/api/admin/senders/{senderId}', ''),
    ('GET', 'v2/admin/beneficiaries'): ('GET', '/api/admin/beneficiaries', ''),
    ('GET', 'v2/admin/beneficiaries/{}/one'): ('GET', '/api/admin/beneficiaries/{beneficiaryId}', ''),
    ('POST', 'v2/admin/beneficiaries/{}/update'): ('PUT', '/api/admin/beneficiaries/{beneficiaryId}', ''),
    ('GET', 'v2/admin/transactions'): ('GET', '/api/admin/transactions', ''),
    ('GET', 'v2/admin/transactions/detail/{}'): ('GET', '/api/admin/transactions/{transactionId}', ''),
    ('POST', 'v2/admin/transactions/bank'): ('POST', '/api/admin/transactions/bank', 'Transfert saisi par un admin : aucun débit.'),
    ('GET', 'v2/admin/recharges'): ('GET', '/api/admin/recharges', ''),
    ('GET', 'v2/admin/users'): ('GET', '/api/admin/users', 'Utilise JSON_CONTAINS (MySQL).'),
    ('GET', 'v2/admin/audits'): ('GET', '/api/admin/audit-logs', ''),
    ('GET', 'v2/admin/connexions'): ('GET', '/api/admin/login-audits', ''),
    ('GET', 'v2/admin/webtokens'): ('GET', '/api/admin/web-tokens', ''),
    ('POST', 'v2/admin/webtokens'): ('POST', '/api/admin/web-tokens', ''),
    ('GET', 'v2/admin/zones'): ('GET', '/api/admin/zones', ''),
    ('POST', 'v2/admin/zones'): ('POST', '/api/admin/zones', ''),
    ('GET', 'v2/admin/grilletarifaires'): ('GET', '/api/admin/fee-grids', ''),
    ('POST', 'v2/admin/grilletarifaires'): ('POST', '/api/admin/fee-grids', ''),
    ('POST', 'v2/admin/update-balance'): ('POST', '/api/admin/customers/{customerId}/balance-adjustments', 'Corps actuel : user_id, amount, operator (add|sub) ; à journaliser.'),
    # --- jetons web (chatbot WhatsApp) et partenaires ------------------------------------------------
    ('GET', 'api/verify-token/{}'): ('GET', '/api/web-tokens/{token}', 'Appelant externe probable (chatbot) : à confirmer.'),
    ('POST', 'api/consume-token/{}'): ('POST', '/api/web-tokens/{token}/consume', 'Idem.'),
    ('POST', 'webhook/stripe'): ('POST', '/api/webhooks/stripe', 'Garder l\'ancien chemin tant que Stripe n\'est pas reconfiguré. Secret en dur à retirer.'),
    ('POST', 'auth/notifyurl/ajax'): ('POST', '/api/webhooks/payci', 'Callback PayCI payin. Chemin enregistré chez le partenaire : alias à conserver.'),
    ('GET', 'auth/notifyurl/ajax'): ('POST', '/api/webhooks/payci', ''),
    ('POST', 'auth/notifyurl/payci'): ('POST', '/api/webhooks/payci-prefund', ''),
    ('GET', 'auth/notifyurl/payci'): ('POST', '/api/webhooks/payci-prefund', ''),
    ('POST', 'auth/notifyurl/cinetpay'): ('POST', '/api/webhooks/cinetpay', ''),
    ('GET', 'auth/notifyurl/cinetpay'): ('POST', '/api/webhooks/cinetpay', ''),
    ('POST', 'auth/notifyurl/paydunya'): ('POST', '/api/webhooks/paydunya', ''),
    ('GET', 'auth/notifyurl/paydunya'): ('POST', '/api/webhooks/paydunya', ''),
    ('POST', 'auth/notifyurlprefund/paydunya'): ('POST', '/api/webhooks/paydunya-prefund', ''),
    ('GET', 'auth/notifyurlprefund/paydunya'): ('POST', '/api/webhooks/paydunya-prefund', ''),
}

# Routes conservées par défaut parce qu'un appelant externe est plausible : sinon « à décider »
DECIDE = {
    ('POST', 'auth/changephoto'): ('PUT', '/api/me/photo', 'ANONYME aujourd\'hui (id utilisateur dans le corps, écrit un fichier dans public/uploads).'),
    ('POST', 'auth/login_pin'): ('POST', '/api/auth/pin-login', '3 utilisateurs ont un PIN (6 chiffres en clair). Aucun appelant connu.'),
    ('POST', 'auth/save_pin'): ('PUT', '/api/me/pin', 'ANONYME aujourd\'hui : définit le PIN de n\'importe quel client.'),
    ('POST', 'auth/resendotp/{}'): ('POST', '/api/auth/otp/resend', 'OTP via Twilio Verify ; aucun appelant connu.'),
    ('POST', 'auth/verifyotp/{}/{}'): ('POST', '/api/auth/otp/verify', 'Aucun appelant connu.'),
    ('POST', 'auth/resendtoken/{}'): ('POST', '/api/auth/otp/resend', ''),
    ('GET', 'auth/google'): (None, None, 'Connexion Google : aucun appelant connu.'),
    ('POST', 'auth/google/post'): (None, None, 'Connexion Google : aucun appelant connu.'),
    ('GET', 'auth/countries'): ('GET', '/api/countries', 'Doublon de /v2/countries.'),
    ('POST', 'collecte/create'): ('POST', '/api/payins', 'ANONYME aujourd\'hui : déclenche un pay-in Wace ; callback_url fourni par l\'appelant.'),
    ('POST', ''): ('POST', '/api/me/kyc-documents', 'ANONYME aujourd\'hui (DefaultController::uploadKYC sur « POST / »).'),
    ('POST', 'api/transactions/bank'): ('POST', '/api/me/transactions/bank', 'Consommateur inconnu ; JWT exigé depuis l\'étape 4. Fusionner avec la route ci-dessus ou supprimer.'),
    ('POST', 'api/transactions/mobil'): ('POST', '/api/me/transactions/mobile', 'Idem.'),
    ('POST', 'auth/webhook/telegram'): ('POST', '/api/webhooks/telegram', 'Aucun secret vérifié (Telegram propose un secret_token).'),
    ('GET', 'auth/testapitransaction/ajax'): (None, None, 'Route de test : à supprimer.'),
    ('POST', 'auth/testapitransaction/ajax'): (None, None, 'Route de test : à supprimer.'),
    ('GET', 'auth/transactions/ussd/last10'): (None, None, 'Aucun appelant connu (dernières transactions USSD).'),
    ('POST', 'auth/login'): ('POST', '/api/auth/login', 'Ancien login (SecurityController::postLoginAction) ; aucun appelant connu.'),
    ('GET', 'auth/login_check'): (None, None, 'Route technique du firewall Lexik : sans objet.'),
}


def main():
    rows = list(csv.DictReader(open(OUT / 'routes.csv')))
    counts = collections.Counter()
    for r in rows:
        key = (r['method'], N(r['path']))
        new_m = new_p = None
        note = ''
        if r['area'] == 'v3':
            decision, note = 'ALIAS (couvert par /v2/admin)', 'Même action que la route /v2/admin équivalente.'
            # la cible est celle de l'alias /v2/admin correspondant
            twin = ('GET' if r['method'] == 'GET' else r['method'], N(r['path'].replace('/v3/', '/v2/admin/', 1)))
            if twin in MAP:
                new_m, new_p, _ = MAP[twin]
        elif key in MAP:
            new_m, new_p, note = MAP[key]
            decision = 'PORTER' if (r['callers'] != '-' or r['area'] == 'v2/admin') else 'PORTER (à confirmer : aucun appelant connu)'
            if new_p is None:
                decision = 'À DÉCIDER'
        elif key in DECIDE:
            new_m, new_p, note = DECIDE[key]
            decision = 'SUPPRIMER' if new_p is None and 'test' in note.lower() else 'À DÉCIDER'
            if new_p is None and 'technique' in note.lower():
                decision = 'SANS OBJET'
        elif r['area'] == 'v1':
            decision, note = 'NE PAS PORTER', 'Ancien préfixe /v1 : aucun appelant connu (Android et web utilisent /v2). À confirmer avec le journal de production.'
        else:
            decision, note = 'À DÉCIDER', 'Non classée.'
        r.update(decision=decision, new_method=new_m or '', new_route=new_p or '', note=note)
        counts[(r['area'], decision.split(' (')[0])] += 1
    with open(OUT / 'routes.csv', 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader(); w.writerows(rows)
    print('%-9s %s' % ('zone', 'décisions'))
    byarea = collections.defaultdict(dict)
    for (a, d), n in sorted(counts.items()):
        byarea[a][d] = n
    for a, d in byarea.items():
        print('%-9s %s' % (a, ', '.join(f'{k}={v}' for k, v in d.items())))
    todo = [r for r in rows if r['decision'] == 'À DÉCIDER' and r['note'] == 'Non classée.']
    print('non classées:', [(r['method'], r['path']) for r in todo])


main()
