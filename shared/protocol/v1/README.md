# UnionKitBot control protocol, version 1

This directory holds the authoritative description of the wire protocol shared by
the Fabric mod (`minecraft-mod`) and the Discord controller (`discord-bot`). It is
documentation, not generated code: both sides are independently buildable, and the
version number is the contract between them.

Nothing here is compiled. The two implementations duplicate the constants on
purpose so that neither component needs the other to build.

## Transport

The mod runs a small control server inside the Minecraft client.

| Transport | Path | Purpose |
| --- | --- | --- |
| WebSocket | `/ws` (alias `/websocket`) | Long-lived request/response and event stream |
| HTTP `GET` | `/health` | Unauthenticated liveness probe |
| HTTP `GET` | `/status` | Authenticated status snapshot |
| HTTP `POST` | `/command` | Authenticated single request/response |

WebSockets are preferred for everything except liveness probing. Polling `/status`
is unnecessary because the mod pushes `session.heartbeat`, `state.changed` and task
events.

### WebSocket upgrade

The upgrade must present:

- `Upgrade: websocket` and `Connection: Upgrade`
- `Sec-WebSocket-Version: 13`
- `Sec-WebSocket-Key`
- `Sec-WebSocket-Protocol: unionkitbot.v1`
- `X-UnionKitBot-Secret: <pre-shared secret>` (or `?secret=` in the query string)
- `X-UnionKitBot-Protocol: 1` (optional; defaults to the current version)

A request missing the secret is refused with `401` before any frame is read. When
the mod is configured with `api.allowRemote = false` (the default), only loopback
peers are accepted and non-loopback upgrades are refused with `403`.

## Frame format

Every frame is a JSON object with exactly these members:

```json
{
  "v": 1,
  "type": "tasks.create",
  "id": "5f1d2c3b",
  "ts": 1758891234567,
  "data": {}
}
```

| Member | Required | Meaning |
| --- | --- | --- |
| `v` | yes | Protocol version. Currently `1`. |
| `type` | yes | Namespaced frame type, lowercase, from the table below. |
| `id` | no | Correlation id. Required for requests; echoed on the reply. |
| `ts` | no | Epoch milliseconds. Defaults to `0`. |
| `data` | no | Type-specific payload object. Defaults to `{}`. |

Unknown members are rejected. A frame whose `v` is not supported is rejected with a
structured error rather than being interpreted optimistically.

## Message types

`→` means the Discord controller sends it, `←` means the mod sends it.

### Requests (`→`)

| Type | Payload | Reply payload |
| --- | --- | --- |
| `session.hello` | `peerName`, `peerVersion`, `protocolVersion` | protocol metadata (`protocol`, `protocolVersion`, `supportedProtocolVersions`, `peerName`, `peerVersion`), plus `modId`, `state`, `running`, `config` |
| `session.ping` | — | `{ "pong": true }` |
| `status.get` | — | status snapshot (see below) |
| `control.start` | `reason` | `{ changed, state, running, detail }` |
| `control.stop` | `reason` | `{ changed, state, running, detail }` |
| `control.restart` | `reason` | `{ cancelledTasks, state, running, detail }` |
| `tasks.list` | `limit` (1–200, default 50) | `{ active[], finished[], activeCount, pendingCount, finishedCount }` |
| `tasks.get` | `id` | `{ task }` |
| `tasks.create` | `kind`, `priority`, `parameters` | `{ task, accepted }` |
| `tasks.cancel` | `id`, `reason` | `{ cancelled, id }` |
| `tasks.cancelall` | `reason` | `{ cancelled }` |
| `logs.get` | `limit` (1–500), `level` | `{ entries[], errors[], level, dropped }` |
| `config.get` | — | `{ config, configFile?, loadError? }` |
| `config.patch` | `config` (partial) | `{ config, applied }` |
| `modules.set` | `modules` (partial booleans) | `{ modules }` |
| `home.set` | `x`, `y`, `z` or `clear` | `{ home }` |

`kind` is one of `navigate`, `deliver`, `scan`, `wait`, `return_home`, `recover`.
`priority` is one of `low`, `normal`, `high`, `critical`.

`tasks.create` accepts `parameters.position` as `{x, y, z}`, or top-level `x`/`y`/`z`
as a convenience, plus `parameters.target` for a delivery target.

### Replies (`←`)

| Type | Payload |
| --- | --- |
| `response.ok` | the handler's payload |
| `response.error` | `{ code, message, path?, retryable }` |

The error object is the frame payload itself. `code` is a stable machine-readable
identifier (`invalid_request`, `unauthorized`, `forbidden`, `timeout`,
`internal_error`, `client_thread_timeout`, …).

### Events (`←`)

| Type | Payload | Notes |
| --- | --- | --- |
| `session.welcome` | `protocol`, `protocolVersion`, `supportedProtocolVersions`, `peerName`, `peerVersion`, `modId`, `state`, `heartbeatSeconds`, `sessionId` | sent once, immediately after the upgrade; the mod identifies itself through `peerName`/`peerVersion` |
| `session.heartbeat` | `state`, `running`, `timestamp`, `activeTasks`, `pendingTasks`, `lastAction` | periodic; the controller treats silence as a dead socket |
| `state.changed` | `state`, `running`, `lastAction`, `timestamp`, `summary?` | lifecycle transition |
| `task.created` | task snapshot | queue insertion |
| `task.updated` | task snapshot | progress or status change |
| `task.completed` | task snapshot | terminal success |
| `task.failed` | task snapshot | terminal failure, `failureReason` set |
| `task.cancelled` | task snapshot | terminal cancellation |
| `system.error` | `timestamp`, `category`, `message`, `exception?`, `detail?`, `state` | structured failure report |
| `system.log` | `sequence`, `timestamp`, `level`, `category`, `message`, `cause?` | diagnostics mirror |
| `config.changed` | `config`, `timestamp` | configuration replaced |
| `modules.changed` | `modules`, `timestamp` | module toggles changed |
| `session.goodbye` | `reason`, `timestamp` | the mod is shutting down |

### Task snapshot

```json
{
  "id": "a1b2c3d4",
  "kind": "navigate",
  "priority": "NORMAL",
  "priorityWeight": 2,
  "status": "RUNNING",
  "owner": "navigation",
  "failureReason": null,
  "attempts": 1,
  "submittedAt": 1758891234567,
  "startedAt": 1758891234700,
  "finishedAt": 0,
  "progress": 0.5,
  "parameters": { "position": { "x": 10, "y": 64, "z": -20 } },
  "notes": ["path recomputed"]
}
```

`status` is one of `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`,
`RETRYING`. `priority` is uppercased on the wire; `kind` stays lowercase.

### Status snapshot

```json
{
  "state": "IDLE",
  "running": true,
  "enabled": true,
  "logLevel": "INFO",
  "uptimeTicks": 2400,
  "lastAction": "idle",
  "peers": 1,
  "protocolVersion": 1,
  "modVersion": "0.1.0",
  "apiPort": 8765,
  "queue": { "active": 0, "pending": 2, "finished": 17 },
  "activeTask": null,
  "modules": {
    "navigation": { "enabled": true, "idle": true, "status": "idle" },
    "delivery": { "enabled": true, "idle": true, "status": "idle" },
    "scanning": { "enabled": false, "idle": true, "status": "disabled" },
    "recovery": { "enabled": true, "idle": true, "status": "idle" }
  },
  "recentErrors": [{ "timestamp": 1758891234567, "category": "navigation", "message": "…" }],
  "world": { "loaded": true, "dimension": "minecraft:overworld", "x": 0, "y": 64, "z": 0 },
  "serverTime": 1758891234567
}
```

`state` is one of `OFFLINE`, `CONNECTING`, `SPAWNING`, `IDLE`, `NAVIGATING`,
`DELIVERING`, `SCANNING`, `RECOVERING`, `DISCONNECTED`, `DEAD`, `ERROR`.

## Failure and reconnection semantics

Both sides must keep working when the other is absent.

- **Mod without Discord.** The control server simply has no peers. Automation,
  the state machine and the task queue are unaffected. Events are broadcast to zero
  peers without error.
- **Discord without Minecraft.** The controller retries the upgrade forever with
  exponential backoff and full jitter, bounded by `MOD_RECONNECT_MIN_MS` and
  `MOD_RECONNECT_MAX_MS`. Commands answer with an explicit "client unreachable"
  message rather than failing silently.
- **Mid-session disconnect.** Outstanding requests are failed immediately with a
  retryable transport error; they are never left pending forever. In-flight replies
  are matched by `id`, so a late reply from a previous socket cannot be mistaken for
  a current one.
- **Silent socket.** If no frame and no pong arrives within the heartbeat window,
  the controller terminates the socket and reconnects rather than waiting on a
  half-open connection.

## Compatibility

Adding an optional member to an existing payload is a non-breaking change. Adding a
new frame type, changing a payload's meaning, or removing a member requires
incrementing the version. The mod reports the supported set in
`supportedProtocolVersions` during the handshake, and the controller logs a loud
warning on mismatch rather than proceeding on a guess.
