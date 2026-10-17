package com.wtc.customer.application;

import com.wtc.reference.application.BankView;
import com.wtc.reference.application.MobileOperatorView;
import java.util.UUID;

public record DestinationView(
        UUID id,
        String kind,
        String countryCode,
        BankView bank,
        MobileOperatorView mobileOperator,
        String accountHolderName,
        String accountNumberMasked,
        String swiftCode,
        String ifscCode,
        String routingNumber,
        boolean isDefault) {}
