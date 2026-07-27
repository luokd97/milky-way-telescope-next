package com.milkywaytelescope.next.settings;

public record MessageSettings(MessageFilter filter) {
    public MessageSettings {
        filter = filter == null ? MessageFilter.defaults() : filter;
    }

    public static MessageSettings defaults() {
        return new MessageSettings(MessageFilter.defaults());
    }
}
