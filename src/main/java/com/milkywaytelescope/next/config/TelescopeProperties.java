package com.milkywaytelescope.next.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telescope")
public class TelescopeProperties {
    private String sitePassword = "";
    private final Storage storage = new Storage();
    private final Message message = new Message();
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

    public Wss getWss() {
        return wss;
    }

    public static class Storage {
        private Path connectionFile = Path.of("data/connections.json");
        private Path controlFile = Path.of("data/connection-control.json");

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
