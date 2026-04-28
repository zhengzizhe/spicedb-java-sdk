# AuthX Test App

This app connects to the local SpiceDB instance in `infra/spicedb`.

```bash
docker compose -f infra/spicedb/docker-compose.yml up -d
zed --endpoint localhost:50051 --token localdevlocaldevlocaldevlocaldev --insecure schema write infra/spicedb/doc.zed
GRADLE_USER_HOME=/tmp/authcses-gradle ./gradlew :test-app:generateAuthxSchema --no-daemon --console=plain
GRADLE_USER_HOME=/tmp/authcses-gradle ./gradlew :test-app:bootRun --no-daemon --console=plain --args='--server.port=8081'
```

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
