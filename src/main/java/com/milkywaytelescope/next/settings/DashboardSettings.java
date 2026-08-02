package com.milkywaytelescope.next.settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record DashboardSettings(List<String> sectionOrder, List<String> inventoryWatchTerms) {
    public static final String CURRENT_ACTIVITY = "currentActivity";
    public static final String TASKS = "tasks";
    public static final String INVENTORY_HIGHLIGHTS = "inventoryHighlights";
    public static final String ACTION_QUEUE = "actionQueue";
    public static final String RECENT_ALERTS = "recentAlerts";

    public static final List<String> DEFAULT_SECTION_ORDER = List.of(
            CURRENT_ACTIVITY,
            TASKS,
            INVENTORY_HIGHLIGHTS,
            ACTION_QUEUE,
            RECENT_ALERTS
    );

    private static final Set<String> AVAILABLE_SECTIONS = Set.copyOf(DEFAULT_SECTION_ORDER);
    private static final Set<String> LEGACY_AVAILABLE_SECTIONS = Set.of(
            CURRENT_ACTIVITY,
            INVENTORY_HIGHLIGHTS,
            ACTION_QUEUE,
            RECENT_ALERTS
    );

    public DashboardSettings(List<String> sectionOrder) {
        this(sectionOrder, List.of());
    }

    public DashboardSettings {
        List<String> normalizedSectionOrder = normalizeSectionOrder(sectionOrder);
        if (normalizedSectionOrder == null
                || normalizedSectionOrder.size() != DEFAULT_SECTION_ORDER.size()
                || !new HashSet<>(normalizedSectionOrder).equals(AVAILABLE_SECTIONS)) {
            throw new IllegalArgumentException(
                    "sectionOrder must contain each dashboard section exactly once"
            );
        }
        sectionOrder = List.copyOf(normalizedSectionOrder);
        inventoryWatchTerms = inventoryWatchTerms == null
                ? List.of()
                : inventoryWatchTerms.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(term -> !term.isBlank())
                        .distinct()
                        .toList();
    }

    private static List<String> normalizeSectionOrder(List<String> sectionOrder) {
        if (sectionOrder == null
                || sectionOrder.size() != DEFAULT_SECTION_ORDER.size() - 1
                || !new HashSet<>(sectionOrder).equals(LEGACY_AVAILABLE_SECTIONS)) {
            return sectionOrder;
        }

        List<String> upgraded = new ArrayList<>(sectionOrder);
        upgraded.add(upgraded.indexOf(CURRENT_ACTIVITY) + 1, TASKS);
        return upgraded;
    }

    public static DashboardSettings defaults() {
        return new DashboardSettings(DEFAULT_SECTION_ORDER, List.of());
    }
}
