package com.milkywaytelescope.next.connection;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public record ConnectionProfile(String characterId, String url, String accessToken) {
    private static final String ALLOWED_HOST = "api.milkywayidle.com";

    public ConnectionProfile {
        if (characterId == null || characterId.isBlank()) {
            throw new IllegalArgumentException("A characterId is required");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("A WebSocket URL is required");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("An access token is required");
        }
    }

    public static ConnectionProfile from(String url, String accessToken) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("The WebSocket URL is invalid");
        }
        if (!"wss".equalsIgnoreCase(uri.getScheme()) || !ALLOWED_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("The URL must use wss://api.milkywayidle.com");
        }

        String characterId = queryParams(uri).get("characterId");
        if (characterId == null || characterId.isBlank()) {
            throw new IllegalArgumentException("The URL must contain characterId");
        }
        String normalizedToken = accessToken == null ? "" : accessToken.trim();
        if (normalizedToken.startsWith("accessToken=")) {
            normalizedToken = normalizedToken.substring("accessToken=".length());
        }
        return new ConnectionProfile(characterId.trim(), uri.toString(), normalizedToken);
    }

    @JsonIgnore
    public URI uri() {
        return URI.create(url);
    }

    @JsonIgnore
    public String cookieHeader() {
        return "accessToken=" + accessToken;
    }

    @JsonIgnore
    public String redactedUrl() {
        return url.replaceAll("([?&]hash=)[^&]+", "$1<redacted>");
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null) {
            return values;
        }
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            if (separator > 0) {
                values.put(
                        decode(part.substring(0, separator)),
                        decode(part.substring(separator + 1))
                );
            }
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
