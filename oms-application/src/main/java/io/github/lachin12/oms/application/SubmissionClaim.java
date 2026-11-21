package io.github.lachin12.oms.application;

import java.util.UUID;

public record SubmissionClaim(UUID orderId, String fingerprint, boolean owner) { }
