# Milky Way Telescope Next

Read-only, multi-character WebSocket monitor for Milky Way Idle.

## Features

- One isolated WSS session per character.
- In-memory ring buffer containing the latest 100 messages per character.
- Typed character projections for actions, tasks, consumables, battles, inventory highlights, and alerts.
- Password-protected dashboard and global settings.
- Runtime URL/access-token updates persisted outside the application JAR.
- Globally configurable character-card section order.
- Protocol-aware session takeover handling that yields for two hours instead of fighting the official game client.
- GitHub Release workflow for reproducible Java 21 builds.

The monitor never sends game actions. Its only outbound WebSocket operation is the protocol close frame.

## Requirements

- Java 21
- Maven 3.9+
- A site password supplied through `MONITOR_SITE_PASSWORD`

## Run locally

```bash
export MONITOR_SITE_PASSWORD='choose-a-local-password'
mvn spring-boot:run
```

Open <http://127.0.0.1:8081>. WSS connections do not start automatically by default. Add or reconnect characters and configure the Dashboard from <http://127.0.0.1:8081/settings>.

Dashboard settings, connection profiles, and takeover-yield state are stored in one owner-only JSON file at
`data/settings.json`, which is excluded from Git. The Settings page can load, format, validate, and replace this
complete configuration. A typical file looks like:

```json
{
  "schemaVersion": 2,
  "dashboard": {
    "sectionOrder": [
      "currentActivity",
      "inventoryHighlights",
      "actionQueue",
      "recentAlerts"
    ],
    "inventoryWatchTerms": ["wisdom_tea", "coin"]
  },
  "message": {
    "filter": {
      "type": ["battle_updated"]
    }
  },
  "connectionSettings": {
    "autoConnect": false,
    "autoReconnect": true,
    "reconnectDelay": "PT30S",
    "takeoverYieldDuration": "PT2H"
  },
  "connections": [
    {
      "characterId": "<character-id>",
      "url": "wss://api.milkywayidle.com/ws?hash=<connection-hash>&characterId=<character-id>",
      "accessToken": "<access-token>"
    }
  ],
  "disabledConnections": [],
  "connectionControls": []
}
```

The `accessToken` is intentionally visible in the authenticated Settings configuration editor. Never commit a real
configuration file.

If the server sends a `close_session` message with `shouldReconnect: false`, Telescope records the
message, cancels automatic reconnects, and yields that character for two hours. The yield deadline
is persisted in the unified configuration, survives application restarts, and can be resumed or extended from the
runtime controls in Settings.

Disconnecting a character from Settings closes only its current WebSocket and keeps its profile. The profile can be
reconnected later. Removing a profile from the configuration permanently deletes its connection credentials.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `MONITOR_SITE_PASSWORD` | required | Password for the entire site |
| `SERVER_ADDRESS` | `127.0.0.1` | HTTP bind address |
| `SERVER_PORT` | `8081` | HTTP port |
| `SESSION_COOKIE_SECURE` | `false` | Set to `true` behind production HTTPS |
| `TELESCOPE_SETTINGS_FILE` | `data/settings.json` | Unified Dashboard, connection, and connection-control configuration |
| `TELESCOPE_RECENT_EVENT_LIMIT` | `50` | Maximum low-inventory alerts retained per character |
| `TELESCOPE_INVENTORY_HIGHLIGHT_LIMIT` | `12` | Maximum inventory highlights shown per character |

## Build

```bash
mvn verify
```

The executable artifact is `target/telescope-next.jar`.

## Release

Push a semantic version tag to trigger `.github/workflows/release.yml`:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The workflow publishes:

- `telescope-next.jar`
- `telescope-next.jar.sha256`

## Debian deployment

Keep the JAR, state, and secrets in separate locations:

```text
/opt/telescope-next/app.jar
/var/lib/telescope-next/settings.json
/etc/telescope-next/telescope-next.env
```

Download an explicit public release:

```bash
RELEASE_VERSION=v0.1.0

curl -fL \
  "https://github.com/luokd97/milky-way-telescope-next/releases/download/${RELEASE_VERSION}/telescope-next.jar" \
  -o /tmp/telescope-next.jar

curl -fL \
  "https://github.com/luokd97/milky-way-telescope-next/releases/download/${RELEASE_VERSION}/telescope-next.jar.sha256" \
  -o /tmp/telescope-next.jar.sha256

cd /tmp
sha256sum --check telescope-next.jar.sha256
```

Run the service as a dedicated unprivileged user, bind it to `127.0.0.1:8081`, and expose it through an HTTPS reverse proxy. Keep `MONITOR_SITE_PASSWORD` in the systemd environment file and point `TELESCOPE_SETTINGS_FILE` at `/var/lib/telescope-next/settings.json`.

The `deploy/` directory contains a systemd unit, environment template, and release installer. On Debian, run the installer as root with an explicit tag:

```bash
sudo ./deploy/install-release.sh v0.1.0
```

The installer verifies the checksum and atomically replaces the JAR, but intentionally does not restart the service.

## Security

- Every page and API except login and health requires authentication.
- Mutating requests require a CSRF token.
- Dashboard API responses and runtime status views redact the connection hash and never return access tokens.
- The complete plaintext configuration is available only through the authenticated, CSRF-protected Settings API.
- The unified configuration file is written with owner-only permissions on POSIX filesystems.

## License

MIT
