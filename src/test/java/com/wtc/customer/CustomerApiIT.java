package com.wtc.customer;

import static com.wtc.support.Api.map;
import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.identity.IdentityFixtures;
import com.wtc.identity.IdentityFixtures.Catalog;
import com.wtc.identity.application.port.PasswordHasher;
import com.wtc.identity.application.port.UserRepository;
import com.wtc.identity.domain.Role;
import com.wtc.support.AbstractIT;
import com.wtc.support.Api;
import com.wtc.support.Api.Reply;
import com.wtc.support.Sessions;
import com.wtc.support.Sessions.Session;
import com.wtc.support.TestClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Module client de bout en bout : profil, expéditeurs, bénéficiaires, destinations, KYC et
 * administration.
 */
class CustomerApiIT extends AbstractIT {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired PasswordHasher hasher;
    @Autowired TestClock clock;

    static Catalog catalog;

    Api api;
    IdentityFixtures fx;

    @BeforeAll
    static void seed(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        catalog = new IdentityFixtures(jdbc, users, hasher).seedCatalog();
    }

    @AfterAll
    static void clean(
            @Autowired JdbcClient jdbc,
            @Autowired UserRepository users,
            @Autowired PasswordHasher hasher) {
        new IdentityFixtures(jdbc, users, hasher).cleanUp();
    }

    @BeforeEach
    void init() {
        api = new Api(mvc);
        fx = new IdentityFixtures(jdbc, users, hasher);
        clock.reset();
    }

    // ------------------------------------------------------------------ référentiels de paiement

    @Test
    void banksAndMobileOperatorsAreListedPerCountryForSignedInUsers() {
        Session me = customer();
        Reply banks = api.get("/api/countries/YY/banks", me.accessToken());
        assertThat(banks.status()).isEqualTo(200);
        assertThat(banks.contractViolations("listBanks", 200)).isEmpty();
        assertThat(banks.json().get(0).get("name").asString()).isEqualTo("Banque YY");

        Reply operators = api.get("/api/countries/yy/mobile-operators", me.accessToken());
        assertThat(operators.contractViolations("listMobileOperators", 200)).isEmpty();
        assertThat(operators.json().size()).isEqualTo(2);

        assertThat(api.get("/api/countries/QQ/banks", me.accessToken()).code())
                .isEqualTo("COUNTRY_NOT_FOUND");
        assertThat(api.get("/api/countries/YY/banks", null).status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ profil

    @Test
    void updatingTheProfileChangesOnlyTheGivenFieldsAndKeepsTheLoginAccountInSync() {
        Session me = customer();
        String phone = "+2420" + (7_000_000 + (int) (Math.random() * 999_999));

        Reply updated =
                api.patch(
                        "/api/me",
                        map(
                                "city",
                                "Pointe-Noire",
                                "occupation",
                                "Ingénieure",
                                "phone",
                                phone,
                                "lastName",
                                "Martin"),
                        me.accessToken());

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.contractViolations("updateMe", 200)).isEmpty();
        assertThat(updated.text("city")).isEqualTo("Pointe-Noire");
        assertThat(updated.text("firstName")).as("champ non cité : inchangé").isEqualTo("Claire");
        assertThat(
                        jdbc.sql(
                                        "SELECT display_name || '|' || phone_e164 FROM users WHERE"
                                                + " lower(email) = :e")
                                .param("e", me.email())
                                .query(String.class)
                                .single())
                .isEqualTo("Claire Martin|" + phone);
        assertThat(
                        jdbc.sql(
                                        "SELECT after_state::text FROM audit_log WHERE action ="
                                                + " 'CUSTOMER_PROFILE_UPDATED' AND entity_id = :id")
                                .param("id", me.customerId())
                                .query(String.class)
                                .single())
                .as("l'audit garde les noms des champs, pas leurs valeurs")
                .contains("city", "phone")
                .doesNotContain("Pointe-Noire")
                .doesNotContain(phone);
    }

    @Test
    void profileUpdatesAreValidated() {
        Session me = customer();
        String other = customer().email();
        String otherPhone =
                jdbc.sql("SELECT phone_e164 FROM users WHERE lower(email) = :e")
                        .param("e", other)
                        .query(String.class)
                        .single();

        assertThat(api.patch("/api/me", map(), me.accessToken()).status())
                .as("corps vide")
                .isIn(400, 422);
        assertThat(api.patch("/api/me", map("countryCode", "QQ"), me.accessToken()).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(api.patch("/api/me", map("birthDate", "2999-01-01"), me.accessToken()).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(api.patch("/api/me", map("kind", "BUSINESS"), me.accessToken()).code())
                .as("raison sociale obligatoire")
                .isEqualTo("VALIDATION_FAILED");
        Reply email = api.patch("/api/me", map("email", "nouveau@example.test"), me.accessToken());
        assertThat(email.status()).isEqualTo(422);
        assertThat(email.code()).isEqualTo("EMAIL_CHANGE_UNSUPPORTED");
        Reply phone = api.patch("/api/me", map("phone", otherPhone), me.accessToken());
        assertThat(phone.status()).isEqualTo(409);
        assertThat(phone.code()).isEqualTo("PHONE_ALREADY_REGISTERED");
        assertThat(api.patch("/api/me", map("city", "Brazzaville"), me.accessToken()).status())
                .as("rien n'a été à moitié appliqué")
                .isEqualTo(200);
    }

    @Test
    void aVerifiedIdentityCannotBeRewritten() {
        Session me = customer();
        jdbc.sql("UPDATE customers SET kyc_status = 'VERIFIED' WHERE public_id = CAST(:id AS uuid)")
                .param("id", me.customerId())
                .update();

        Reply rename = api.patch("/api/me", map("firstName", "Autre"), me.accessToken());
        assertThat(rename.status()).isEqualTo(409);
        assertThat(rename.code()).isEqualTo("KYC_LOCKED");
        assertThat(api.patch("/api/me", map("city", "Dolisie"), me.accessToken()).status())
                .as("les champs non identitaires restent modifiables")
                .isEqualTo(200);
    }

    @Test
    void staffHaveNoCustomerSpace() {
        Session staff = Sessions.staff(api, fx, clock, Role.SUPER_ADMIN);
        assertThat(api.get("/api/me/senders", staff.accessToken()).status()).isEqualTo(403);
        assertThat(api.get("/api/me/kyc", staff.accessToken()).status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ expéditeurs et
    // bénéficiaires

    private static Map<String, Object> person(String first, String last) {
        return map(
                "kind",
                "INDIVIDUAL",
                "firstName",
                first,
                "lastName",
                last,
                "countryCode",
                "YY",
                "phone",
                "+24201234567",
                "idDocumentType",
                "PASSPORT",
                "idDocumentNumber",
                "AB123456789");
    }

    @Test
    void sendersAreCreatedListedUpdatedAndArchivedWithoutEverRevealingTheIdNumber() {
        Session me = customer();
        Reply created = api.post("/api/me/senders", person("Jean", "Durand"), me.accessToken());

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.contractViolations("createSender", 201)).isEmpty();
        assertThat(created.text("role")).isEqualTo("SENDER");
        assertThat(created.json().get("idDocument").get("numberMasked").asString())
                .isEqualTo("****6789");
        assertThat(created.body())
                .as("le numéro de pièce n'apparaît jamais")
                .doesNotContain("AB123456789");
        String id = created.text("id");
        assertThat(
                        jdbc.sql(
                                        "SELECT encode(id_document_number_enc, 'escape') FROM"
                                                + " parties WHERE public_id = CAST(:id AS uuid)")
                                .param("id", id)
                                .query(String.class)
                                .single())
                .as("chiffré au repos")
                .doesNotContain("AB123456789");

        Reply listed = api.get("/api/me/senders?search=durand&sort=lastName,asc", me.accessToken());
        assertThat(listed.contractViolations("listSenders", 200)).isEmpty();
        assertThat(listed.json().get("totalElements").asLong()).isEqualTo(1);
        assertThat(
                        api.get("/api/me/senders?search=introuvable", me.accessToken())
                                .json()
                                .get("totalElements")
                                .asLong())
                .isZero();
        assertThat(api.get("/api/me/senders?sort=password,asc", me.accessToken()).status())
                .isEqualTo(422);

        Reply update =
                api.put(
                        "/api/me/senders/" + id,
                        map(
                                "kind",
                                "INDIVIDUAL",
                                "firstName",
                                "Jean",
                                "lastName",
                                "Dupond",
                                "countryCode",
                                "YY"),
                        me.accessToken());
        assertThat(update.status()).isEqualTo(200);
        assertThat(update.contractViolations("updateSender", 200)).isEmpty();
        assertThat(update.text("lastName")).isEqualTo("Dupond");
        assertThat(update.json().get("idDocument").get("numberMasked").asString())
                .as("numéro conservé quand il n'est pas renvoyé")
                .isEqualTo("****6789");

        assertThat(api.delete("/api/me/senders/" + id, null, me.accessToken()).status())
                .isEqualTo(204);
        assertThat(api.get("/api/me/senders/" + id, me.accessToken()).status()).isEqualTo(404);
        assertThat(
                        api.get("/api/me/senders", me.accessToken())
                                .json()
                                .get("totalElements")
                                .asLong())
                .isZero();
    }

    @Test
    void aCustomerNeverSeesOrTouchesAnotherCustomersContacts() {
        Session alice = customer();
        Session bob = customer();
        String senderId =
                api.post("/api/me/senders", person("Jean", "Durand"), alice.accessToken())
                        .text("id");
        String beneficiaryId =
                api.post("/api/me/beneficiaries", person("Awa", "Traoré"), alice.accessToken())
                        .text("id");

        assertThat(api.get("/api/me/senders/" + senderId, bob.accessToken()).code())
                .isEqualTo("PARTY_NOT_FOUND");
        assertThat(
                        api.put(
                                        "/api/me/senders/" + senderId,
                                        person("Pirate", "X"),
                                        bob.accessToken())
                                .status())
                .isEqualTo(404);
        assertThat(api.delete("/api/me/senders/" + senderId, null, bob.accessToken()).status())
                .isEqualTo(404);
        assertThat(
                        api.get(
                                        "/api/me/beneficiaries/" + beneficiaryId + "/destinations",
                                        bob.accessToken())
                                .status())
                .isEqualTo(404);
        assertThat(
                        api.get("/api/me/senders", bob.accessToken())
                                .json()
                                .get("totalElements")
                                .asLong())
                .isZero();
        assertThat(api.get("/api/me/beneficiaries/" + senderId, alice.accessToken()).status())
                .as("un expéditeur n'est pas un bénéficiaire")
                .isEqualTo(404);
        assertThat(api.get("/api/me/senders/" + senderId, alice.accessToken()).text("firstName"))
                .as("rien n'a bougé")
                .isEqualTo("Jean");
    }

    @Test
    void partiesAreValidated() {
        Session me = customer();
        assertThat(
                        api.post(
                                        "/api/me/senders",
                                        map("kind", "INDIVIDUAL", "firstName", "Jean"),
                                        me.accessToken())
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(api.post("/api/me/senders", map("kind", "BUSINESS"), me.accessToken()).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(api.post("/api/me/senders", map("kind", "ROBOT"), me.accessToken()).status())
                .isIn(400, 422);
        assertThat(
                        api.post(
                                        "/api/me/senders",
                                        map(
                                                "kind",
                                                "INDIVIDUAL",
                                                "firstName",
                                                "A",
                                                "lastName",
                                                "B",
                                                "countryCode",
                                                "QQ"),
                                        me.accessToken())
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.post(
                                        "/api/me/senders",
                                        map("kind", "BUSINESS", "businessName", "ACME SARL"),
                                        me.accessToken())
                                .status())
                .isEqualTo(201);
    }

    // ------------------------------------------------------------------ destinations

    @Test
    void destinationsHandleBanksMobileWalletsAndTheDefaultChoice() {
        Session me = customer();
        String beneficiary =
                api.post("/api/me/beneficiaries", person("Awa", "Traoré"), me.accessToken())
                        .text("id");
        String base = "/api/me/beneficiaries/" + beneficiary + "/destinations";

        Reply bank =
                api.post(
                        base,
                        map(
                                "kind",
                                "BANK_ACCOUNT",
                                "countryCode",
                                "YY",
                                "bankId",
                                catalog.bankYy(),
                                "accountNumber",
                                "FR76 3000 6000 0112 3456 7890 189",
                                "accountHolderName",
                                "Awa Traoré"),
                        me.accessToken());
        assertThat(bank.status()).isEqualTo(201);
        assertThat(bank.contractViolations("createDestination", 201)).isEmpty();
        assertThat(bank.json().get("isDefault").asBoolean())
                .as("la première destination est par défaut")
                .isTrue();
        assertThat(bank.text("accountNumberMasked")).isEqualTo("****0189");
        assertThat(bank.body()).doesNotContain("3000").doesNotContain("FR76");
        assertThat(
                        jdbc.sql(
                                        "SELECT encode(account_number_enc, 'escape') FROM"
                                            + " payout_destinations WHERE public_id = CAST(:id AS"
                                            + " uuid)")
                                .param("id", bank.text("id"))
                                .query(String.class)
                                .single())
                .doesNotContain("FR76")
                .doesNotContain("30006");

        Reply mobile =
                api.post(
                        base,
                        map(
                                "kind",
                                "MOBILE_WALLET",
                                "countryCode",
                                "YY",
                                "mobileOperatorId",
                                catalog.operatorYy(),
                                "accountNumber",
                                "+242 06 123 45 67",
                                "isDefault",
                                true),
                        me.accessToken());
        assertThat(mobile.status()).isEqualTo(201);
        assertThat(mobile.json().get("mobileOperator").get("name").asString())
                .isEqualTo("Mobile YY");

        Reply all = api.get(base, me.accessToken());
        assertThat(all.contractViolations("listDestinations", 200)).isEmpty();
        assertThat(all.json().size()).isEqualTo(2);
        assertThat(all.json().get(0).get("id").asString())
                .as("la destination par défaut vient en premier")
                .isEqualTo(mobile.text("id"));
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM payout_destinations d JOIN parties p"
                                            + " ON p.id = d.party_id WHERE d.is_default AND"
                                            + " d.archived_at IS NULL AND p.public_id = CAST(:id AS"
                                            + " uuid)")
                                .param("id", beneficiary)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);

        // Archiver la destination par défaut en désigne une autre.
        assertThat(api.delete(base + "/" + mobile.text("id"), null, me.accessToken()).status())
                .isEqualTo(204);
        Reply remaining = api.get(base, me.accessToken());
        assertThat(remaining.json().size()).isEqualTo(1);
        assertThat(remaining.json().get(0).get("isDefault").asBoolean()).isTrue();

        Reply changed =
                api.put(
                        base + "/" + bank.text("id"),
                        map(
                                "kind",
                                "BANK_ACCOUNT",
                                "countryCode",
                                "YY",
                                "bankId",
                                catalog.bankYy(),
                                "accountNumber",
                                "ZZ99887766554433",
                                "swiftCode",
                                "bnpafrpp"),
                        me.accessToken());
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.contractViolations("updateDestination", 200)).isEmpty();
        assertThat(changed.text("swiftCode")).isEqualTo("BNPAFRPP");
        assertThat(changed.text("accountNumberMasked")).isEqualTo("****4433");
    }

    @Test
    void destinationsMustBeCoherentWithTheCatalog() {
        Session me = customer();
        String beneficiary =
                api.post("/api/me/beneficiaries", person("Awa", "Traoré"), me.accessToken())
                        .text("id");
        String base = "/api/me/beneficiaries/" + beneficiary + "/destinations";
        String token = me.accessToken();
        assertThat(
                        api.post(
                                        base,
                                        map(
                                                "kind",
                                                "BANK_ACCOUNT",
                                                "countryCode",
                                                "WW",
                                                "bankId",
                                                catalog.bankZz(),
                                                "accountNumber",
                                                "12345678"),
                                        token)
                                .status())
                .isEqualTo(422);
        assertThat(
                        api.post(
                                        base,
                                        map(
                                                "kind",
                                                "BANK_ACCOUNT",
                                                "countryCode",
                                                "YY",
                                                "bankId",
                                                catalog.bankZz(),
                                                "accountNumber",
                                                "12345678"),
                                        token)
                                .field("bankId"))
                .as("banque d'un autre pays")
                .isEqualTo("bankId");
        assertThat(
                        api.post(
                                        base,
                                        map(
                                                "kind",
                                                "BANK_ACCOUNT",
                                                "countryCode",
                                                "YY",
                                                "accountNumber",
                                                "12345678"),
                                        token)
                                .field("bankId"))
                .isEqualTo("bankId");
        assertThat(
                        api.post(
                                        base,
                                        map(
                                                "kind",
                                                "BANK_ACCOUNT",
                                                "countryCode",
                                                "YY",
                                                "bankId",
                                                99999999,
                                                "accountNumber",
                                                "12345678"),
                                        token)
                                .field("bankId"))
                .isEqualTo("bankId");
        assertThat(
                        api.post(
                                        base,
                                        map(
                                                "kind",
                                                "BANK_ACCOUNT",
                                                "countryCode",
                                                "YY",
                                                "bankId",
                                                catalog.bankYy(),
                                                "mobileOperatorId",
                                                catalog.operatorYy(),
                                                "accountNumber",
                                                "12345678"),
                                        token)
                                .status())
                .isEqualTo(422);
        assertThat(
                        api.post(
                                        base,
                                        map(
                                                "kind",
                                                "BANK_ACCOUNT",
                                                "countryCode",
                                                "YY",
                                                "bankId",
                                                catalog.bankYy(),
                                                "accountNumber",
                                                "12"),
                                        token)
                                .field("accountNumber"))
                .isEqualTo("accountNumber");
        assertThat(
                        api.post(
                                        base,
                                        map(
                                                "kind",
                                                "MOBILE_WALLET",
                                                "countryCode",
                                                "YY",
                                                "mobileOperatorId",
                                                catalog.operatorWithoutPayout(),
                                                "accountNumber",
                                                "061234567"),
                                        token)
                                .field("mobileOperatorId"))
                .isEqualTo("mobileOperatorId");
        assertThat(
                        api.post(
                                        base,
                                        map(
                                                "kind",
                                                "MOBILE_WALLET",
                                                "countryCode",
                                                "YY",
                                                "mobileOperatorId",
                                                catalog.operatorYy(),
                                                "accountNumber",
                                                "pas-un-numero"),
                                        token)
                                .field("accountNumber"))
                .isEqualTo("accountNumber");
        assertThat(
                        api.post(
                                        base,
                                        map(
                                                "kind",
                                                "BANK_ACCOUNT",
                                                "countryCode",
                                                "YY",
                                                "bankId",
                                                catalog.bankYy(),
                                                "accountNumber",
                                                "12345678",
                                                "swiftCode",
                                                "nope"),
                                        token)
                                .field("swiftCode"))
                .isEqualTo("swiftCode");
        assertThat(api.get(base, token).json().size())
                .as("aucune destination invalide n'a été enregistrée")
                .isZero();
    }

    // ------------------------------------------------------------------ KYC

    private static byte[] png() {
        byte[] data = new byte[200];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(signature, 0, data, 0, signature.length);
        for (int i = signature.length; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }
        return data;
    }

    @Test
    void customersUploadKycDocumentsToPrivateStorageAndReceiveSignedLinks() throws Exception {
        Session me = customer();
        assertThat(api.get("/api/me/kyc", me.accessToken()).text("status"))
                .isEqualTo("NOT_SUBMITTED");

        Reply upload =
                api.upload(
                        "POST",
                        "/api/me/kyc/documents",
                        map(
                                "documentType",
                                "PASSPORT",
                                "side",
                                "SINGLE",
                                "expiresOn",
                                "2031-01-01"),
                        png(),
                        me.accessToken());
        assertThat(upload.status()).isEqualTo(201);
        assertThat(upload.contractViolations("uploadKycDocument", 201)).isEmpty();
        assertThat(upload.text("status")).isEqualTo("PENDING");

        HttpResponse<byte[]> download =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(URI.create(upload.text("downloadUrl")))
                                        .build(),
                                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).as("le lien signé livre le fichier d'origine").isEqualTo(png());
        String key =
                jdbc.sql(
                                "SELECT storage_key FROM kyc_documents WHERE public_id = CAST(:id"
                                        + " AS uuid)")
                        .param("id", upload.text("id"))
                        .query(String.class)
                        .single();
        assertThat(key).startsWith("kyc/" + me.customerId() + "/");
        String unsigned = upload.text("downloadUrl").split("\\?")[0];
        assertThat(
                        HttpClient.newHttpClient()
                                .send(
                                        HttpRequest.newBuilder(URI.create(unsigned)).build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .as("sans signature le fichier est inaccessible")
                .isIn(401, 403);

        Reply overview = api.get("/api/me/kyc", me.accessToken());
        assertThat(overview.contractViolations("getKyc", 200)).isEmpty();
        assertThat(overview.text("status")).isEqualTo("PENDING");
        assertThat(
                        api.get("/api/me/kyc/documents/" + upload.text("id"), me.accessToken())
                                .contractViolations("getKycDocument", 200))
                .isEmpty();
    }

    @Test
    void kycUploadsAreCheckedByContentNotByWhatTheClientClaims() {
        Session me = customer();
        String t = me.accessToken();
        Reply html =
                api.upload(
                        "POST",
                        "/api/me/kyc/documents",
                        map("documentType", "PASSPORT"),
                        "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8),
                        t);
        assertThat(html.status()).isEqualTo(422);
        assertThat(html.code()).isEqualTo("UNSUPPORTED_FILE_TYPE");
        byte[] pdf = "%PDF-1.7 contenu".getBytes(StandardCharsets.US_ASCII);
        assertThat(
                        api.upload(
                                        "POST",
                                        "/api/me/kyc/documents",
                                        map("documentType", "SELFIE"),
                                        pdf,
                                        t)
                                .code())
                .as("selfie = image")
                .isEqualTo("UNSUPPORTED_FILE_TYPE");
        assertThat(
                        api.upload(
                                        "POST",
                                        "/api/me/kyc/documents",
                                        map("documentType", "PROOF_OF_ADDRESS"),
                                        pdf,
                                        t)
                                .status())
                .isEqualTo(201);
        assertThat(
                        api.upload(
                                        "POST",
                                        "/api/me/kyc/documents",
                                        map("documentType", "LAISSEZ_PASSER"),
                                        png(),
                                        t)
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.upload(
                                        "POST",
                                        "/api/me/kyc/documents",
                                        map("documentType", "PASSPORT", "expiresOn", "2001-01-01"),
                                        png(),
                                        t)
                                .code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(
                        api.upload(
                                        "POST",
                                        "/api/me/kyc/documents",
                                        map("documentType", "PASSPORT"),
                                        new byte[0],
                                        t)
                                .status())
                .isIn(400, 422);
        byte[] huge = new byte[8 * 1024 * 1024 + 1];
        System.arraycopy(png(), 0, huge, 0, 8);
        Reply tooBig =
                api.upload(
                        "POST", "/api/me/kyc/documents", map("documentType", "PASSPORT"), huge, t);
        assertThat(tooBig.status()).isEqualTo(413);
        assertThat(tooBig.code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM kyc_documents d JOIN customers c ON"
                                            + " c.id = d.customer_id WHERE c.public_id = CAST(:id"
                                            + " AS uuid)")
                                .param("id", me.customerId())
                                .query(Long.class)
                                .single())
                .as("seul le justificatif de domicile valide a été retenu")
                .isEqualTo(1);
    }

    @Test
    void supportStaffReviewKycDocumentsAndTheCustomerStatusFollows() {
        Session me = customer();
        Session support = Sessions.staff(api, fx, clock, Role.SUPPORT);
        String docId =
                api.upload(
                                "POST",
                                "/api/me/kyc/documents",
                                map("documentType", "NATIONAL_ID"),
                                png(),
                                me.accessToken())
                        .text("id");
        String review =
                "/api/admin/customers/" + me.customerId() + "/kyc/documents/" + docId + "/review";

        Reply overview =
                api.get("/api/admin/customers/" + me.customerId() + "/kyc", support.accessToken());
        assertThat(overview.contractViolations("adminGetCustomerKyc", 200)).isEmpty();
        assertThat(api.post(review, map("decision", "REJECT"), support.accessToken()).status())
                .as("motif obligatoire")
                .isEqualTo(422);
        assertThat(api.post(review, map("decision", "APPROVE"), me.accessToken()).status())
                .as("un client ne se valide pas lui-même")
                .isEqualTo(403);

        Reply approved = api.post(review, map("decision", "APPROVE"), support.accessToken());
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.contractViolations("adminReviewKycDocument", 200)).isEmpty();
        assertThat(approved.text("status")).isEqualTo("APPROVED");
        assertThat(api.get("/api/me/kyc", me.accessToken()).text("status")).isEqualTo("VERIFIED");
        assertThat(
                        jdbc.sql(
                                        "SELECT kyc_verified_at IS NOT NULL FROM customers WHERE"
                                                + " public_id = CAST(:id AS uuid)")
                                .param("id", me.customerId())
                                .query(Boolean.class)
                                .single())
                .isTrue();
        assertThat(
                        api.post(
                                        review,
                                        map("decision", "REJECT", "reason", "trop tard"),
                                        support.accessToken())
                                .code())
                .isEqualTo("KYC_DOCUMENT_ALREADY_REVIEWED");
        assertThat(
                        jdbc.sql(
                                        "SELECT u.email FROM kyc_documents d JOIN users u ON u.id ="
                                                + " d.reviewed_by WHERE d.public_id = CAST(:id AS"
                                                + " uuid)")
                                .param("id", docId)
                                .query(String.class)
                                .single())
                .as("le relecteur est enregistré")
                .isEqualTo(support.email());

        Session other = customer();
        assertThat(
                        api.post(
                                        "/api/admin/customers/"
                                                + other.customerId()
                                                + "/kyc/documents/"
                                                + docId
                                                + "/review",
                                        map("decision", "APPROVE"),
                                        support.accessToken())
                                .status())
                .as("un document ne se relit pas à travers un autre client")
                .isEqualTo(404);

        String rejectedId =
                api.upload(
                                "POST",
                                "/api/me/kyc/documents",
                                map("documentType", "PASSPORT"),
                                png(),
                                other.accessToken())
                        .text("id");
        Reply rejected =
                api.post(
                        "/api/admin/customers/"
                                + other.customerId()
                                + "/kyc/documents/"
                                + rejectedId
                                + "/review",
                        map("decision", "REJECT", "reason", "photo floue"),
                        support.accessToken());
        assertThat(rejected.text("rejectionReason")).isEqualTo("photo floue");
        assertThat(api.get("/api/me/kyc", other.accessToken()).text("status"))
                .isEqualTo("REJECTED");
    }

    // ------------------------------------------------------------------ administration des clients

    @Test
    void suspendingACustomerClosesTheirSessionsAndBlocksChanges() {
        Session me = customer();
        Session admin = Sessions.staff(api, fx, clock, Role.SUPER_ADMIN);
        String url = "/api/admin/customers/" + me.customerId();

        Reply suspended =
                api.patch(
                        url,
                        map("status", "SUSPENDED", "reason", "fraude suspectée"),
                        admin.accessToken());
        assertThat(suspended.status()).isEqualTo(200);
        assertThat(suspended.contractViolations("adminUpdateCustomerStatus", 200)).isEmpty();

        assertThat(api.post("/api/auth/refresh", map("refreshToken", me.refreshToken())).status())
                .as("sessions fermées")
                .isEqualTo(401);
        assertThat(
                        api.post(
                                        "/api/auth/login",
                                        map(
                                                "email",
                                                me.email(),
                                                "password",
                                                IdentityFixtures.PASSWORD))
                                .status())
                .isEqualTo(403);
        Reply blocked = api.post("/api/me/senders", person("Jean", "Durand"), me.accessToken());
        assertThat(blocked.status())
                .as("le jeton encore valable ne permet plus de modifier")
                .isEqualTo(403);
        assertThat(blocked.code()).isEqualTo("CUSTOMER_NOT_ACTIVE");
        assertThat(api.get("/api/me/senders", me.accessToken()).status())
                .as("la consultation reste possible")
                .isEqualTo(200);

        assertThat(api.patch(url, map("status", "ACTIVE"), admin.accessToken()).status())
                .isEqualTo(200);
        assertThat(
                        api.post(
                                        "/api/auth/login",
                                        map(
                                                "email",
                                                me.email(),
                                                "password",
                                                IdentityFixtures.PASSWORD))
                                .status())
                .isEqualTo(200);

        assertThat(api.patch(url, map("status", "CLOSED"), admin.accessToken()).status())
                .isEqualTo(200);
        Reply reopen = api.patch(url, map("status", "ACTIVE"), admin.accessToken());
        assertThat(reopen.status()).isEqualTo(409);
        assertThat(
                        api.post(
                                        "/api/auth/login",
                                        map(
                                                "email",
                                                me.email(),
                                                "password",
                                                IdentityFixtures.PASSWORD))
                                .status())
                .isEqualTo(403);
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM audit_log WHERE action ="
                                                + " 'CUSTOMER_STATUS_CHANGED' AND entity_id = :id")
                                .param("id", me.customerId())
                                .query(Long.class)
                                .single())
                .isEqualTo(3);
    }

    @Test
    void backOfficeListsSearchesAndReadsCustomersAndTheirContacts() {
        Session me = customer();
        Session admin = Sessions.staff(api, fx, clock, Role.ADMIN);
        String beneficiary =
                api.post("/api/me/beneficiaries", person("Awa", "Traoré"), me.accessToken())
                        .text("id");
        api.post(
                "/api/me/beneficiaries/" + beneficiary + "/destinations",
                map(
                        "kind",
                        "BANK_ACCOUNT",
                        "countryCode",
                        "YY",
                        "bankId",
                        catalog.bankYy(),
                        "accountNumber",
                        "98765432"),
                me.accessToken());

        Reply list =
                api.get(
                        "/api/admin/customers?search="
                                + me.email().substring(0, 8)
                                + "&sort=createdAt,desc",
                        admin.accessToken());
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.contractViolations("adminListCustomers", 200)).isEmpty();
        assertThat(list.json().get("totalElements").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(
                        api.get("/api/admin/customers/" + me.customerId(), admin.accessToken())
                                .contractViolations("adminGetCustomer", 200))
                .isEmpty();
        assertThat(
                        api.get(
                                        "/api/admin/customers/" + java.util.UUID.randomUUID(),
                                        admin.accessToken())
                                .code())
                .isEqualTo("CUSTOMER_NOT_FOUND");

        Reply parties =
                api.get(
                        "/api/admin/parties?role=BENEFICIARY&customerId=" + me.customerId(),
                        admin.accessToken());
        assertThat(parties.contractViolations("adminListParties", 200)).isEmpty();
        assertThat(parties.json().get("content").get(0).get("customer").get("id").asString())
                .isEqualTo(me.customerId());
        assertThat(parties.json().get("content").get(0).get("destinations").size()).isEqualTo(1);
        Reply party = api.get("/api/admin/parties/" + beneficiary, admin.accessToken());
        assertThat(party.contractViolations("adminGetParty", 200)).isEmpty();
        Reply fixed =
                api.put(
                        "/api/admin/parties/" + beneficiary,
                        map(
                                "kind",
                                "INDIVIDUAL",
                                "firstName",
                                "Awa",
                                "lastName",
                                "Traoré-Diallo",
                                "countryCode",
                                "YY"),
                        admin.accessToken());
        assertThat(fixed.status()).isEqualTo(200);
        assertThat(fixed.contractViolations("adminUpdateParty", 200)).isEmpty();
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM audit_log WHERE action ="
                                                + " 'PARTY_UPDATED_BY_ADMIN'")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);

        assertThat(api.get("/api/admin/customers", me.accessToken()).status())
                .as("un client n'accède pas au back-office")
                .isEqualTo(403);
        Session support = Sessions.staff(api, fx, clock, Role.SUPPORT);
        assertThat(api.get("/api/admin/customers", support.accessToken()).status())
                .as("SUPPORT lit les clients")
                .isEqualTo(200);
        assertThat(
                        api.patch(
                                        "/api/admin/customers/" + me.customerId(),
                                        map("status", "SUSPENDED"),
                                        support.accessToken())
                                .status())
                .as("mais ne les suspend pas")
                .isEqualTo(403);
        assertThat(
                        api.put(
                                        "/api/admin/parties/" + beneficiary,
                                        map(
                                                "kind",
                                                "INDIVIDUAL",
                                                "firstName",
                                                "X",
                                                "lastName",
                                                "Y"),
                                        support.accessToken())
                                .status())
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------ photo

    @Test
    void aProfilePhotoIsStoredPrivatelyAndReplacedOnTheNextUpload() throws Exception {
        Session me = customer();
        Reply first = api.upload("PUT", "/api/me/photo", Map.of(), png(), me.accessToken());
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.contractViolations("uploadPhoto", 200)).isEmpty();
        String firstUrl = first.text("photoUrl");
        HttpClient http = HttpClient.newHttpClient();
        assertThat(
                        http.send(
                                        HttpRequest.newBuilder(URI.create(firstUrl)).build(),
                                        HttpResponse.BodyHandlers.ofByteArray())
                                .body())
                .isEqualTo(png());
        assertThat(api.get("/api/me", me.accessToken()).json().get("user").get("photoUrl"))
                .as("visible aussi dans /me")
                .isNotNull();

        Reply second = api.upload("PUT", "/api/me/photo", Map.of(), png(), me.accessToken());
        assertThat(second.status()).isEqualTo(200);
        assertThat(
                        http.send(
                                        HttpRequest.newBuilder(URI.create(firstUrl)).build(),
                                        HttpResponse.BodyHandlers.discarding())
                                .statusCode())
                .as("l'ancienne photo est supprimée du stockage")
                .isEqualTo(404);

        assertThat(
                        api.upload(
                                        "PUT",
                                        "/api/me/photo",
                                        Map.of(),
                                        "%PDF-1.7 pas une image"
                                                .getBytes(StandardCharsets.US_ASCII),
                                        me.accessToken())
                                .code())
                .isEqualTo("UNSUPPORTED_FILE_TYPE");
        byte[] tooBig = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(png(), 0, tooBig, 0, 8);
        assertThat(api.upload("PUT", "/api/me/photo", Map.of(), tooBig, me.accessToken()).status())
                .isEqualTo(413);
    }

    // ------------------------------------------------------------------ utilitaires

    private Session customer() {
        return Sessions.customer(api, fx, "client");
    }
}
