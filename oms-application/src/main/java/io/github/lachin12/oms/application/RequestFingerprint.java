package io.github.lachin12.oms.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RequestFingerprint {
    private RequestFingerprint() { }

    public static String of(SubmitOrder request) {
        String canonical = String.join("|", "v1", request.accountId().toString(),
                request.instrumentId().toString(), request.side().name(), request.type().name(),
                request.quantity().toPlainString(),
                request.limitPrice() == null ? "-" : request.limitPrice().toPlainString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM does not provide SHA-256", ex);
        }
    }
}
