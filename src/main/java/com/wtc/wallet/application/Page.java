package com.wtc.wallet.application;

import java.util.List;

public record Page<T>(List<T> content, long total) {
    public Page {
        content = List.copyOf(content);
    }
}
