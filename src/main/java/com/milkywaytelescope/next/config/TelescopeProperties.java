package com.milkywaytelescope.next.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telescope")
public class TelescopeProperties {
    private String sitePassword = "";
    private String sitePasswordHash = "";
    private String rememberMeKey = "";
    private final Storage storage = new Storage();
    private final Message message = new Message();
    private final State state = new State();
    private final Inventory inventory = new Inventory();

    public String getSitePassword() {
        return sitePassword;
    }

    public void setSitePassword(String sitePassword) {
        this.sitePassword = sitePassword;
    }

    public String getSitePasswordHash() {
        return sitePasswordHash;
    }

    public void setSitePasswordHash(String sitePasswordHash) {
        this.sitePasswordHash = sitePasswordHash;
    }

    public String getRememberMeKey() {
        return rememberMeKey;
    }

    public void setRememberMeKey(String rememberMeKey) {
        this.rememberMeKey = rememberMeKey;
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

    public static class Storage {
        private Path settingsFile = Path.of("data/settings.json");

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

}
