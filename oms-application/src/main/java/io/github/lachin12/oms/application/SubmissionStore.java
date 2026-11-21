package io.github.lachin12.oms.application;

import java.util.UUID;

public interface SubmissionStore {
    /** Atomic claim; a competing transaction must observe the committed winner or fail. */
    SubmissionClaim claim(UUID accountId, String key, String fingerprint, UUID proposedOrderId);
}
