package io.github.lachin12.oms.domain;

import java.util.UUID;

/** Reference data snapshot; balances and authorisation belong to later modules. */
public record Account(UUID id, String name, boolean active) {
    public Account {
        DecimalRules.required(id, "account id");
        name = DecimalRules.text(name, "account name", 128);
    }
}
