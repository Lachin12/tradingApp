package io.github.lachin12.oms.application;

import io.github.lachin12.oms.domain.Account;
import io.github.lachin12.oms.domain.Instrument;
import java.util.Optional;
import java.util.UUID;

public interface ReferenceData {
    Optional<Account> findAccount(UUID id);
    Optional<Instrument> findInstrument(UUID id);
}
