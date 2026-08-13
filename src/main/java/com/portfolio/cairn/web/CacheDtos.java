package com.portfolio.cairn.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

public class CacheDtos {

    public record SetRequest(
            String key,
            String value,
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
