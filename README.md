# Milky Way Telescope Next

Read-only, multi-character WebSocket monitor for Milky Way Idle.

## Features

- One isolated WSS session per character.
- In-memory ring buffer containing the latest 100 messages per character.
- Typed connection and character summary state with room for additional message projectors.
- Password-protected dashboard and connection administration.
- Runtime URL/access-token updates persisted outside the application JAR.
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

Open <http://127.0.0.1:8081>. WSS connections do not start automatically by default. Add or reconnect characters from <http://127.0.0.1:8081/admin>.

Runtime profiles are written to `data/connections.json`, which is excluded from Git. A stored profile contains only:

```json
[
  {
    "characterId": "<character-id>",
    "url": "wss://api.milkywayidle.com/ws?hash=<connection-hash>&characterId=<character-id>",
    "accessToken": "<access-token>"
  }
]
```

Never commit a real profile.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `MONITOR_SITE_PASSWORD` | required | Password for the entire site |
| `SERVER_ADDRESS` | `127.0.0.1` | HTTP bind address |
| `SERVER_PORT` | `8081` | HTTP port |
| `SESSION_COOKIE_SECURE` | `false` | Set to `true` behind production HTTPS |
| `TELESCOPE_CONNECTION_FILE` | `data/connections.json` | External profile file |
| `TELESCOPE_AUTO_CONNECT` | `false` | Connect stored profiles during startup |

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
/var/lib/telescope-next/connections.json
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

Run the service as a dedicated unprivileged user, bind it to `127.0.0.1:8081`, and expose it through an HTTPS reverse proxy. Keep `MONITOR_SITE_PASSWORD` in the systemd environment file and point `TELESCOPE_CONNECTION_FILE` at `/var/lib/telescope-next/connections.json`.

The `deploy/` directory contains a systemd unit, environment template, and release installer. On Debian, run the installer as root with an explicit tag:

```bash
sudo ./deploy/install-release.sh v0.1.0
```

The installer verifies the checksum and atomically replaces the JAR, but intentionally does not restart the service.

## Security

- Every page and API except login and health requires authentication.
- Mutating requests require a CSRF token.
- API responses and logs redact the connection hash and never return access tokens.
- The profile file is written with owner-only permissions on POSIX filesystems.

## License

MIT
