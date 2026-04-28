# AuthX Test App

This app connects to the local SpiceDB instance in `infra/spicedb`.

```bash
docker compose -f infra/spicedb/docker-compose.yml up -d
zed --endpoint localhost:50051 --token localdevlocaldevlocaldevlocaldev --insecure schema write infra/spicedb/doc.zed
GRADLE_USER_HOME=/tmp/authcses-gradle ./gradlew :test-app:generateAuthxSchema --no-daemon --console=plain
GRADLE_USER_HOME=/tmp/authcses-gradle ./gradlew :test-app:bootRun --no-daemon --console=plain --args='--server.port=8081'
```

HTTP requests are traced by default. The app creates one OpenTelemetry server
span for each Spring request, enables SDK telemetry so AuthX SDK operations
create child spans, and the SDK propagates `traceparent` to SpiceDB gRPC calls.
Spans are logged locally by default, so no Jaeger or collector is required.

Useful local tracing knobs:

```bash
# Default: print spans to the app console.
AUTHX_TRACING_EXPORTERS=console ./gradlew :test-app:bootRun

# Send spans to a local OTLP collector instead.
AUTHX_TRACING_EXPORTERS=otlp \
OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4317 \
./gradlew :test-app:bootRun

# Export to both console and OTLP.
AUTHX_TRACING_EXPORTERS=console,otlp \
OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4317 \
./gradlew :test-app:bootRun

# Disable request spans and SDK child spans.
AUTHX_TRACING_ENABLED=false ./gradlew :test-app:bootRun
```

Application logs include `trace_id` and `span_id` MDC fields for the active
HTTP span. SDK logs also include the SDK's `[trace=<id>]` prefix and `authx.*`
MDC fields around SDK calls.

Core demo APIs:

- `POST /doc/nodes`
- `POST /doc/relationships/grant`
- `POST /doc/relationships/revoke`
- `POST /doc/org-members/grant`
- `POST /doc/org-members/revoke`
- `POST /doc/check`
- `GET /doc/nodes/{nodeId}/relations`
- `GET /doc/nodes/{nodeId}/expand/{permission}`

This is only a client demo. It does not implement CSES business validation.

Examples:

```bash
curl -sS -X POST http://127.0.0.1:8081/doc/nodes \
  -H 'Content-Type: application/json' \
  -d '{"nodeId":"space1","ownerUserId":"alice"}'

curl -sS -X POST http://127.0.0.1:8081/doc/nodes \
  -H 'Content-Type: application/json' \
  -d '{"nodeId":"folder1","ownerUserId":"bob","parentId":"space1"}'

curl -sS -X POST http://127.0.0.1:8081/doc/relationships/grant \
  -H 'Content-Type: application/json' \
  -d '{"nodeId":"folder1","relation":"READER","subject":"user:carol"}'

curl -sS -X POST http://127.0.0.1:8081/doc/check \
  -H 'Content-Type: application/json' \
  -d '{"nodeId":"folder1","permission":"READER_EFFECTIVE","subject":"user:carol"}'
```
