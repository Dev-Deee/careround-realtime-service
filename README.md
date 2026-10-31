# careround-realtime-service

Pushes alerts to ward tablets over STOMP. HTTP 8088, no database, no Kafka, no gRPC.

```
round-service --Kafka--> notification-service --(save Alert)--> Redis "careround.alerts" --> realtime-service --STOMP--> tablets
```

notification-service owns `Alert` (persist, replay, acknowledge). This service only holds connections and fans out what
arrives on Redis. Redis pub/sub is fire-and-forget: a message published while an instance is down is lost, and clients
recover it from notification-service's replay endpoint.

## Run

Needs `careround-redis` (root `docker-compose.yml`) and a `.env` with `JWT_PUBLIC_KEY` (same value as the other services;
copy `.env.example` to `.env`).

```bash
./mvnw clean package -DskipTests && java -jar target/careround-realtime-service-0.0.1-SNAPSHOT.jar
```

A second instance (to see the Redis backplane): add `--server.port=8089`.

| Property | Default | |
|---|---|---|
| `app.realtime.redis-channel` | `careround.alerts` | Redis channel to subscribe to |
| `app.realtime.allowed-origins` | `http://localhost:*` | comma-separated origin patterns for the `/ws` handshake |
| `app.realtime.session-sweep-ms` | `30000` | how often sessions past their token `exp` are closed |
| `app.realtime.heartbeat-ms` | `10000` | STOMP heart-beat interval both ways; `0` disables |

## Connecting

- Endpoint `ws://<host>:8088/ws` (plain WebSocket, STOMP 1.2). Open at HTTP level, because browsers cannot set headers on
  the handshake.
- Send the JWT in the **CONNECT frame** header `Authorization: Bearer <accessToken>`. Missing, invalid or expired → ERROR
  frame, then the socket is closed.
- SUBSCRIBE (deny by default; a denied SUBSCRIBE gets an ERROR frame and the socket is closed):

| Destination | Allowed |
|---|---|
| `/topic/wards/{wardPublicId}` | DOCTOR/NURSE whose token `assignedWardId` is that ward; ADMIN (logged) |
| `/topic/admin/alerts` | ADMIN (logged). Alerts whose ward could not be resolved |
| anything else, or a malformed ward id | nobody |

- Clients never SEND; any SEND is rejected.
- **Heart-beats:** the broker offers `heart-beat:10000,10000` (`app.realtime.heartbeat-ms`). Clients should ask for
  10 s both ways (stomp.js `heartbeatIncoming` / `heartbeatOutgoing: 10000`), so proxies and load balancers see
  traffic on idle sockets. A client that negotiated heart-beats and then goes silent is closed by the server.
- Tokens last 15 minutes and a socket can outlive one. Each session's `exp` is recorded at CONNECT; the sweep sends
  `ERROR: Access token expired: refresh the token and reconnect` and closes the socket. Refresh, then reconnect.
- `assignedWardId` is set at registration (and verified against ward-service). Staff who move ward must log in again.

## Message contract (Redis → STOMP, `schemaVersion: 1`, additive only)

```json
{ "schemaVersion": 1,
  "kind": "ALERT_RAISED | ALERT_RESOLVED | ALERT_ACKNOWLEDGED",
  "alertId": "<Alert.publicId, null for ALERT_RESOLVED>",
  "eventId": "<Kafka record key / Alert.sourceEventId>",
  "type": "PATIENT_EMERGENCY_DECLARED",
  "wardId": "<ward publicId or null>",
  "patientId": "<patient publicId>",
  "occurredAt": "<ISO-8601 instant>",
  "acknowledgedBy": "<userId or null>" }
```

Ids only, no names or clinical detail. `wardId` present → `/topic/wards/{wardId}`; null → `/topic/admin/alerts`. A
message that is not JSON, or lacks `kind`, `eventId` or `patientId`, is logged (`Dropping malformed alert message`) and
dropped. Clients receive exactly the fields above; a field added upstream is not forwarded until `AlertMessage` adds it.

## Errors

Every HTTP error uses the body `{"status", "error", "message", "timestamp"}`.

- A path no endpoint maps (e.g. a collection root with a trailing slash, or a wrong sub-path) → **404**
  `No endpoint <METHOD> <path>`. A wrong HTTP method on a mapped path → 405, unsupported content type → 415,
  malformed JSON → 400.
- STOMP errors are ERROR frames (see Connecting above).
- Anything unexpected → 500 with a generic message; the exception is logged at ERROR with its stack trace.

## Test page

`tools/ws-test.html` is a small STOMP client (stomp.js 7.0.0 from jsDelivr). Serve it from localhost so its origin is
allowed:

```bash
cd tools && python3 -m http.server 5500
```

Open http://localhost:5500/ws-test.html, paste an access token, Connect, set the destination
(`/topic/wards/<wardPublicId>`), Subscribe. Then publish a test message:

```bash
docker exec careround-redis redis-cli PUBLISH careround.alerts '{"schemaVersion":1,"kind":"ALERT_RAISED","alertId":"11111111-1111-1111-1111-111111111111","eventId":"manual-test-1","type":"PATIENT_EMERGENCY_DECLARED","wardId":"<wardPublicId>","patientId":"22222222-2222-2222-2222-222222222222","occurredAt":"2026-01-01T00:00:00Z","acknowledgedBy":null}'
```

## Tests

`./mvnw test`: STOMP auth rules (`StompAuthChannelInterceptorTest`), the expiry sweep (`SessionExpiryServiceTest`) and
message parsing/routing (`AlertRedisSubscriberTest`).
