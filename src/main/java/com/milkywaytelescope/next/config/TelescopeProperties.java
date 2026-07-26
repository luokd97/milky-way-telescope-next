package com.milkywaytelescope.next.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telescope")
public class TelescopeProperties {
    private String sitePassword = "";
    private final Storage storage = new Storage();
    private final Message message = new Message();
    private final State state = new State();
    private final Inventory inventory = new Inventory();
    private final Wss wss = new Wss();

    public String getSitePassword() {
        return sitePassword;
    }

    public void setSitePassword(String sitePassword) {
        this.sitePassword = sitePassword;
    }

    public Storage getStorage() {
        return storage;
    }

    public Message getMessage() {
        return message;
    }

    public State getState() {
        return state;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public Wss getWss() {
        return wss;
    }

    public static class Storage {
        private Path connectionFile = Path.of("data/connections.json");
        private Path controlFile = Path.of("data/connection-control.json");
        private Path settingsFile = Path.of("data/settings.json");

        public Path getConnectionFile() {
            return connectionFile;
        }

        public void setConnectionFile(Path connectionFile) {
            this.connectionFile = connectionFile;
        }

        public Path getControlFile() {
            return controlFile;
        }

        public void setControlFile(Path controlFile) {
            this.controlFile = controlFile;
        }

        public Path getSettingsFile() {
            return settingsFile;
        }

        public void setSettingsFile(Path settingsFile) {
            this.settingsFile = settingsFile;
        }
    }

    public static class Message {
        private int recentLimit = 100;
        private int maxPayloadBytes = 1_048_576;

        public int getRecentLimit() {
            return recentLimit;
        }

        public void setRecentLimit(int recentLimit) {
            this.recentLimit = recentLimit;
        }

        public int getMaxPayloadBytes() {
            return maxPayloadBytes;
        }

        public void setMaxPayloadBytes(int maxPayloadBytes) {
            this.maxPayloadBytes = maxPayloadBytes;
        }
    }

    public static class State {
        private int recentEventLimit = 50;

        public int getRecentEventLimit() {
            return recentEventLimit;
        }

        public void setRecentEventLimit(int recentEventLimit) {
            this.recentEventLimit = recentEventLimit;
        }
    }

    public static class Inventory {
        private int highlightLimit = 12;

        public int getHighlightLimit() {
            return highlightLimit;
        }

        public void setHighlightLimit(int highlightLimit) {
            this.highlightLimit = highlightLimit;
        }
    }

    public static class Wss {
        private boolean autoConnect;
        private boolean autoReconnect = true;
        private Duration reconnectDelay = Duration.ofSeconds(30);
        private Duration takeoverYieldDuration = Duration.ofHours(2);

        public boolean isAutoConnect() {
            return autoConnect;
        }

        public void setAutoConnect(boolean autoConnect) {
            this.autoConnect = autoConnect;
        }

        public boolean isAutoReconnect() {
            return autoReconnect;
        }

        public void setAutoReconnect(boolean autoReconnect) {
            this.autoReconnect = autoReconnect;
        }

        public Duration getReconnectDelay() {
            return reconnectDelay;
        }

        public void setReconnectDelay(Duration reconnectDelay) {
            this.reconnectDelay = reconnectDelay;
        }

        public Duration getTakeoverYieldDuration() {
            return takeoverYieldDuration;
        }

        public void setTakeoverYieldDuration(Duration takeoverYieldDuration) {
            if (takeoverYieldDuration == null
                    || takeoverYieldDuration.isZero()
                    || takeoverYieldDuration.isNegative()) {
                throw new IllegalArgumentException("takeoverYieldDuration must be positive");
            }
            this.takeoverYieldDuration = takeoverYieldDuration;
        }
    }
}
