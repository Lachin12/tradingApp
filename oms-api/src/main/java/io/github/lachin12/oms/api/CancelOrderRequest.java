package io.github.lachin12.oms.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CancelOrderRequest(
        @NotNull @PositiveOrZero
        @Schema(description = "Version from the latest order response; stale versions return 409", example = "0")
        Long expectedVersion,
        @NotBlank @Size(max = 256) String reason) {
    public CancelOrderRequest {
        if (reason != null) reason = reason.strip();
    }
}
