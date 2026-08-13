package com.portfolio.cairn.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public class CacheDtos {

    public record SetRequest(
            @NotBlank(message = "Key must not be blank")
            @Size(max = 250, message = "Key must not exceed 250 characters")
            String key,

            @NotNull(message = "Value must not be null")
            @Size(max = 1048576, message = "Value must not exceed 1MB")
            String value,

            @Min(value = 1, message = "TTL must be positive")
            Long ttl
    ) {}

    public record SetResponse(
            String key,
            String status,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long ttl
    ) {}

    public record GetResponse(
            String key,
            String value,
            long ttl_remaining
    ) {}

    public record ExistsResponse(
            String key,
            boolean exists
    ) {}

    public record ExpireRequest(
            @NotNull(message = "TTL must not be null")
            @Min(value = 1, message = "TTL must be positive")
            Long ttl
    ) {}

    public record ExpireResponse(
            String key,
            long ttl_updated
    ) {}

    public record TtlResponse(
            String key,
            long ttl_remaining
    ) {}

    public record InvalidateRequest(
            @NotBlank(message = "Pattern must not be blank")
            String pattern
    ) {}

    public record InvalidateResponse(
            String status,
            int invalidatedKeysCount
    ) {}

    public record ErrorResponse(
            String status,
            String errorCode,
            String message,
            String timestamp
    ) {
        public static ErrorResponse of(String errorCode, String message) {
            return new ErrorResponse("error", errorCode, message, Instant.now().toString());
        }
    }
}
