package com.milkywaytelescope.next.settings;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record DashboardSettings(List<String> sectionOrder) {
    public static final String CURRENT_ACTIVITY = "currentActivity";
    public static final String INVENTORY_HIGHLIGHTS = "inventoryHighlights";
    public static final String ACTION_QUEUE = "actionQueue";
    public static final String RECENT_ALERTS = "recentAlerts";

    public static final List<String> DEFAULT_SECTION_ORDER = List.of(
            CURRENT_ACTIVITY,
            INVENTORY_HIGHLIGHTS,
            ACTION_QUEUE,
            RECENT_ALERTS
    );

    private static final Set<String> AVAILABLE_SECTIONS = Set.copyOf(DEFAULT_SECTION_ORDER);

    public DashboardSettings {
        if (sectionOrder == null
                || sectionOrder.size() != DEFAULT_SECTION_ORDER.size()
                || !new HashSet<>(sectionOrder).equals(AVAILABLE_SECTIONS)) {
            throw new IllegalArgumentException(
                    "sectionOrder must contain each dashboard section exactly once"
            );
        }
        sectionOrder = List.copyOf(sectionOrder);
    }

    public static DashboardSettings defaults() {
        return new DashboardSettings(DEFAULT_SECTION_ORDER);
    }
}
