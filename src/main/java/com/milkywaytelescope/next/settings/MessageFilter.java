package com.milkywaytelescope.next.settings;

import java.util.List;
import java.util.Objects;

public record MessageFilter(List<String> type) {
    public MessageFilter {
        type = type == null
                ? List.of()
                : type.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList();
    }

    public static MessageFilter defaults() {
        return new MessageFilter(List.of());
    }

    public boolean matches(String messageType) {
        return messageType != null && type.contains(messageType);
    }
}
