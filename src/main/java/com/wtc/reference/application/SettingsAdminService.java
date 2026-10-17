package com.wtc.reference.application;

import com.wtc.reference.application.port.SettingsRepository;
import com.wtc.shared.audit.AuditTrail;
import com.wtc.shared.error.DomainException;
import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.FieldViolation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Paramètres de la plateforme. Seuls les paramètres existants se modifient, et chacun a un
 * <b>validateur de type</b> : on ne peut pas écrire un texte dans un plafond de montant.
 */
@Service
public class SettingsAdminService {

    private static final Map<String, Predicate<JsonNode>> VALIDATORS =
            Map.of(
                    "transfer.enforce_fee_schedule",
                    JsonNode::isBoolean,
                    "transfer.max_amount_per_transfer",
                    n -> n.isNumber() && n.decimalValue().signum() > 0,
                    "wallet.default_currency",
                    n -> n.isString() && n.asString().matches("^[A-Z]{3}$"),
                    "client.requirements",
                    SettingsAdminService::validRequirements);

    private final SettingsRepository settings;
    private final AuditTrail audit;
    private final JsonMapper json;

    SettingsAdminService(SettingsRepository settings, AuditTrail audit, JsonMapper json) {
        this.settings = settings;
        this.audit = audit;
        this.json = json;
    }

    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    @Transactional(readOnly = true)
    public List<SettingView> list() {
        return settings.list();
    }

    @PreAuthorize("hasAuthority('REFERENCE_WRITE')")
    @Transactional
    public SettingView update(UUID actor, String key, JsonNode value) {
        SettingView before =
                settings.find(key)
                        .orElseThrow(
                                () ->
                                        new DomainException(
                                                ErrorCode.SETTING_NOT_FOUND, "Paramètre inconnu."));
        Predicate<JsonNode> validator = VALIDATORS.get(key);
        if (validator == null || value == null || !validator.test(value)) {
            throw new DomainException(
                    ErrorCode.VALIDATION_FAILED,
                    "Valeur invalide pour « " + key + " ».",
                    List.of(
                            new FieldViolation(
                                    "value",
                                    "InvalidType",
                                    "Type ou valeur non conforme à ce paramètre.")));
        }
        settings.update(key, json.writeValueAsString(value), actor);
        SettingView after = settings.find(key).orElseThrow();
        audit.recordAs(
                actor,
                "SETTING_UPDATED",
                "SETTING",
                key,
                "{\"value\":" + before.valueJson() + "}",
                "{\"value\":" + after.valueJson() + "}");
        return after;
    }

    private static boolean validRequirements(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return false;
        }
        for (JsonNode entry : node) {
            JsonNode platform = entry.get("platform");
            JsonNode version = entry.get("minimumVersion");
            if (platform == null
                    || !List.of("ANDROID", "WEB").contains(platform.asString())
                    || version == null
                    || !version.asString().matches("^\\d+\\.\\d+\\.\\d+$")) {
                return false;
            }
        }
        return true;
    }
}
