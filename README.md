# UnionKitBot Fabric

A client-side Minecraft Fabric automation mod with a completely separate Discord
controller.

The mod runs inside your own Minecraft client and owns all automation state: the
lifecycle state machine, the task queue, the modules, the world observations and the
diagnostics. The Discord bot is a remote control. It never drives the game directly,
and the game never depends on it.

Both halves build and run independently. You can run the mod with no Discord bot at
all, and you can run the bot with no Minecraft client running.

| | |
| --- | --- |
| Mod name | UnionKitBot Fabric |
| Mod id | `unionkitbot` |
| Minecraft | 1.21.11 |
| Java | 21 |
| Loader | Fabric Loader 0.19.5 |
| Fabric API | 0.141.6+1.21.11 |
| Loom | 1.18.2 |
| Controller | TypeScript, discord.js v14, Node 20.11+ |

> Scope. This is a personal automation assistant. It contains no anti-cheat evasion,
> no anti-bot detection workaround, no packet manipulation and no server security
> bypass. It only does what an ordinary client can do, through ordinary client APIs.

---

## 0. Setup guide

End-to-end walkthrough for a fresh machine. Sections 1–10 afterwards are deeper
reference on the same topics; you do not need them to get running.

### 0.1 What you need

| Requirement | Version | Why |
| --- | --- | --- |
| JDK | 21 | To build the mod |
| Node.js | 20.11+ | To run the Discord bot |
| Minecraft Java Edition | 1.21.11 | With a Fabric profile |
| Fabric Loader | for 1.21.11 | From the Fabric installer |
| Fabric API | for 1.21.11 | From Modrinth |

The mod is client-side only. Do not install it on a server, and you do not need to be
an operator anywhere.

### 0.2 Get the code

```bash
git clone https://github.com/logvex-sg/unionkitbot-fabric.git
cd unionkitbot-fabric
```

If you downloaded a zip instead, note that it is not a git repository: `git pull` will
not work in it. Prefer the clone.

### 0.3 Build the mod

```bash
cd minecraft-mod
chmod +x gradlew
./gradlew build
```

The `chmod` matters when the folder came from a zip; without it Gradle reports
`Permission denied`. The first build needs network access, because Loom downloads
Minecraft, the Mojang mappings and Fabric API. Later builds work with
`./gradlew build --offline`.

Outputs land in `minecraft-mod/build/libs/`:

| File | Purpose |
| --- | --- |
| `unionkitbot-fabric-0.1.0.jar` | Install this one |
| `unionkitbot-fabric-0.1.0-sources.jar` | Sources |
| `unionkitbot-fabric-0.1.0-headless.jar` | Logic-only, for tooling without Minecraft |

Run the tests with `./gradlew test`.

### 0.4 Install the mod

1. Run the Fabric installer for Minecraft 1.21.11 and create a profile.
2. Put Fabric API for 1.21.11 into your mods folder.
3. Put `unionkitbot-fabric-0.1.0.jar` into the same folder.
4. Launch the game with the Fabric profile.

The mods folder is `%appdata%\.minecraft\mods` on Windows,
`~/Library/Application Support/minecraft/mods` on macOS, and `~/.minecraft/mods` on
Linux.

Launch the game at least once before continuing. That first run generates the API
secret needed in step 0.6. Confirm it exists:

```bash
cat ~/.minecraft/config/unionkitbot/unionkitbot.secret
```

You should see a 64-character hex string. You do not need to copy it by hand -
`npm run setup` in step 0.6 reads it for you.

The mod also writes `unionkitbot.json` alongside it. That file arrives fully
commented, explaining every option and its default, so you do not need to consult
section 9 before changing something:

```bash
cat ~/.minecraft/config/unionkitbot/unionkitbot.json
```

The JSON is safe to share when reporting a bug, because the secret is deliberately
kept in the separate `.secret` file. The secret file is generated with owner-only
permissions where the filesystem supports it.

### 0.5 Create the Discord application

1. Create an application in the
   [Discord Developer Portal](https://discord.com/developers/applications).
2. Open **Bot**, then **Reset Token**, and copy the token. Treat it as a password:
   anyone holding it controls your bot.
3. Copy the **Application ID** from **General Information**.
4. Leave the **Public Key** alone. The bot connects over the Discord Gateway rather
   than an HTTP interactions endpoint, so nothing reads it.
5. Do **not** enable privileged intents. The bot requests only `Guilds`, which is not
   privileged; slash commands work without Message Content or Server Members.
6. Invite the bot via **OAuth2 → URL Generator** with scopes `bot` and
   `applications.commands` and permissions **Send Messages** and **Embed Links**.
   Responses are embeds, so without Embed Links the commands fail.

For your own user ID, enable Developer Mode in Discord settings, then right-click
yourself and choose **Copy User ID**.

### 0.6 Configure and run the bot

```bash
cd discord-bot
npm install
npm run build
npm run setup
```

`npm run setup` is an interactive wizard. It asks only for the values that are
genuinely per-install, then writes `.env` for you:

| It asks for | Where to get it |
| --- | --- |
| Discord bot token | Developer Portal → your app → **Bot** → **Reset Token** |
| Discord application ID | Developer Portal → your app → **General Information** |
| Admin user IDs | Right-click yourself in Discord → **Copy User ID** (Developer Mode on) |
| Mod API secret | Read automatically from `unionkitbot.secret`; you only type it if the file is not found |

It validates each answer before writing anything, quotes values a dotenv parser
could misread, keeps any lines it does not manage, and re-running it is safe.
The resulting `.env` is created with `0600` permissions where the filesystem
supports it. If Minecraft lives somewhere unusual, point it at the directory:

```bash
UNIONKITBOT_MOD_DIR=/path/to/config/unionkitbot npm run setup
```

Then confirm everything is wired up before starting:

```bash
npm run doctor
```

`doctor` verifies the token against Discord, checks that the application ID
belongs to that token, compares the secret in `.env` with the mod's secret file,
and probes the mod. It prints a `PASS`/`WARN`/`FAIL` line per check with a hint
next to anything that needs action, and never prints a secret. Expect `WARN` for
the mod checks when Minecraft is not running - that is normal, not a fault.

Finally:

```bash
npm start
```

Commands are also registered automatically on every startup. Use `npm run
register-commands` to refresh the definitions without starting the controller,
and `npm run dev` to recompile TypeScript on change.

Startup order does not matter. Start the bot first and it retries with exponential
backoff and jitter until Minecraft appears; start Minecraft first and the bot
connects on its next attempt. Stop with Ctrl+C: the bot handles `SIGINT` and
`SIGTERM`, closes the mod socket and destroys the Discord client, leaving no
half-open socket behind.

Everything except the four required values has a sensible default. Section 5 lists
the full set if you want to tune it by hand.

### 0.7 Verify

1. Minecraft is running with the mod loaded. The mod logs the bound port at startup,
   `127.0.0.1:8765` by default.
2. The bot logs a successful mod connection once the WebSocket handshake completes.
3. Run `/status` in Discord. You should see the state, connection status, queue depth
   and module toggles.

Automation is **off** by default. `/status` reporting `IDLE` with automation off is
correct rather than a fault; run `/start` when you want it running.

In game, press `K` (rebindable under Controls → Miscellaneous) for the status and
configuration screen, or use `/unionkitbot`, `/unionkitbot status`,
`/unionkitbot start` and `/unionkitbot stop`.

### 0.8 Setup troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `./gradlew: Permission denied` | Run `chmod +x gradlew` |
| Bot says the client is unreachable | Minecraft not running with the mod, or `MOD_API_URL` does not match `api.host`/`api.port` |
| `401 unauthorized` on connect | Secrets differ. Run `npm run doctor`: it compares the two values and tells you which side is wrong |
| `403 forbidden` on connect | Mod bound to a non-loopback address while `api.allowRemote` is `false` |
| `missing required environment variable` | Bot exits naming the variable; run `npm run setup` to fill in the required values |
| Edits to `unionkitbot.json` seem ignored | Fixed in this release. If you have an old file, delete it and restart: the mod rewrites it fully commented. A rejected file is now copied to `unionkitbot.json.invalid` instead of being lost |
| Commands missing in Discord | Set `DISCORD_GUILD_ID` for instant registration; global commands take up to an hour. Check the `applications.commands` scope |
| Commands time out | The mod answers on the client thread; a paused, minimised or sleeping client times out |
| Automation idle | Automation is off by default. Run `/start`, check a module is on, and check `/tasks` |
| Mod tests fail on a fresh clone | Run `./gradlew build` online once; `--offline` works afterwards |

Section 10 covers the rest.

### 0.9 Security essentials

- Never commit `.env`. It is git-ignored; keep it that way and `chmod 600` it.
- The API binds loopback only by default. To control a client from another machine you
  must set `api.allowRemote` to `true`, set an explicit `api.allowedOrigins` list, and
  put a TLS proxy in front, on a network you control.
- The bot redacts the Discord token and the mod secret from every log line and never
  echoes them in a Discord response.
- If a token leaks, reset it in the Developer Portal immediately. To rotate the mod
  secret, delete `unionkitbot.secret`, restart Minecraft, then update
  `MOD_API_SECRET` in `.env`.

---

## 1. Repository architecture

```
unionkitbot-fabric/
├── minecraft-mod/                    # Fabric client mod (Gradle + Loom)
│   ├── build.gradle
│   ├── gradle.properties
│   ├── settings.gradle
│   └── src/
│       ├── main/java/dev/unionkitbot/fabric/    # logic + control server
│       ├── client/java/dev/unionkitbot/fabric/  # client entrypoint, GUI, HUD, command
│       └── test/java/dev/unionkitbot/fabric/    # JUnit 5 tests
├── discord-bot/                      # Discord controller (TypeScript)
│   ├── package.json
│   ├── tsconfig.json
│   ├── tsconfig.test.json
│   ├── vitest.config.ts
│   ├── .env.example
│   ├── src/
│   └── tests/
├── shared/protocol/v1/README.md      # authoritative wire-protocol description
├── README.md
└── .gitignore
```

### Mod internals

| Package | Responsibility |
| --- | --- |
| `core.state` | `BotState` enum and the `StateMachine` that guards legal transitions |
| `core.task` | `AgentTask`, `TaskKind`, `TaskPriority`, `TaskParameters`, `TaskQueue` |
| `core.agent` | `BotAgent` — the OBSERVE → DECIDE → ACT → VERIFY loop |
| `core.world` | `Observation`, `WorldStatus` — safe reads of player/world/server state |
| `core.action` | `ClientActions` — the only place that touches the Minecraft client |
| `module` | `AutomationModule` plus `NavigationModule`, `DeliveryModule`, `ScanningModule`, `RecoveryModule` and `ModuleRegistry` |
| `protocol` | `ProtocolVersion`, `MessageType`, `Envelope`, `Messages` |
| `net` | `ControlServer`, `ControlDispatcher`, `WebSocketSession`, `WebSocketHandshake`, `AuthTokens`, `BuildInfo` |
| `config` | `AgentConfig`, `ConfigManager` |
| `diag` | `DiagnosticsLog`, `LogLevel`, structured error capture |
| `runtime` | `BotRuntime` — wires the agent, queue, modules, server and config together |
| `client` | Client entrypoint, key binding, `/unionkitbot` command, `BotHud`, `BotConfigScreen` |

The mod is split into `main` and `client` source sets. The state machine, task queue
and protocol layers live in `main` and do not touch the Minecraft client, which is
what makes them unit-testable without a game and what keeps the failure surface
small.

### Discord bot internals

| Path | Responsibility |
| --- | --- |
| `src/config` | Environment parsing and `.env` loading, with validation |
| `src/logging` | Levelled logger with secret redaction |
| `src/protocol` | Frame types, envelope encode/decode/validate |
| `src/modlink` | `ModLink` (WebSocket, correlation, reconnect, heartbeat) and `ModController` (typed facade) |
| `src/discord` | Command definitions, `Authorizer`, `CommandRouter`, embeds, `DiscordController` |
| `src/index.ts` | Process wiring, lifecycle, graceful shutdown |

### Why `shared/` holds no code

`shared/` is documentation only. Generating code from a shared schema would couple
the two build systems, and the point of the layout is that either half can be built
and deployed on its own. The protocol version number is the contract.

---

## 2. Building the Fabric 1.21.11 mod

Requirements: JDK 21, plus network access for the first build (Loom downloads
Minecraft, the Mojang mappings and Fabric API).

```bash
cd minecraft-mod
./gradlew build
```

On Windows use `gradlew.bat build`.

Outputs land in `minecraft-mod/build/libs/`:

| File | Purpose |
| --- | --- |
| `unionkitbot-fabric-0.1.0.jar` | The mod to drop into `mods/` |
| `unionkitbot-fabric-0.1.0-sources.jar` | Sources |
| `unionkitbot-fabric-0.1.0-headless.jar` | Logic-only jar (state machine, queue, protocol) for tests and tooling without Minecraft |

Run only the tests:

```bash
cd minecraft-mod
./gradlew test
```

Launch a development client with the mod loaded:

```bash
cd minecraft-mod
./gradlew runClient
```

After one successful online build, offline builds work:

```bash
./gradlew build --offline
```

Version knobs live in `minecraft-mod/gradle.properties`: `minecraft_version`,
`loader_version`, `fabric_api_version`, `loom_version` and `version`.

---

## 3. Installing the mod

1. Install Fabric Loader for Minecraft 1.21.11.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11 into `.minecraft/mods/`.
3. Copy `unionkitbot-fabric-0.1.0.jar` into `.minecraft/mods/`.
4. Launch the game with the Fabric profile.

In game:

- Press `K` (rebindable under Controls → Miscellaneous) to open the UnionKitBot screen.
- Or run `/unionkitbot` for the screen, `/unionkitbot status`, `/unionkitbot start`,
  `/unionkitbot stop`.

On first launch the mod writes:

```
.minecraft/config/unionkitbot/unionkitbot.json     # non-secret settings
.minecraft/config/unionkitbot/unionkitbot.secret   # the API secret
```

If no secret file exists the mod generates a strong one. The secret is deliberately
kept out of `unionkitbot.json` so that the JSON can be pasted into a bug report
without leaking a credential. `UNIONKITBOT_API_SECRET` in the environment overrides
the file.

The mod is client-side only. It does not need to be installed on the server, and
installing it changes nothing about what the server sees beyond ordinary client
traffic.

---

## 4. Running the Discord bot

Requirements: Node 20.11 or newer.

```bash
cd discord-bot
npm install
cp .env.example .env      # then fill in the values
npm run build
npm start
```

Development loop with recompilation on change:

```bash
npm run dev
```

Register the slash commands. Guild registration is instant; global registration can
take up to an hour to propagate.

```bash
npm run register-commands
```

Set `DISCORD_GUILD_ID` while testing to get instant registration; leave it empty for a
global release.

The bot handles `SIGINT` and `SIGTERM`: it stops accepting commands, closes the mod
socket, destroys the Discord client and exits, leaving no half-open socket behind.

---

## 5. Configuring the Discord bot

All configuration is environment based. Copy `.env.example` to `.env`; `.env` is
git-ignored, and nothing containing a token is ever committed.

### Discord

| Variable | Required | Meaning |
| --- | --- | --- |
| `DISCORD_TOKEN` | yes | Bot token from the Developer Portal |
| `DISCORD_APPLICATION_ID` | yes | Application id, used to register commands |
| `DISCORD_GUILD_ID` | no | Guild for instant command registration; empty means global |
| `DISCORD_ADMIN_USER_IDS` | one of these two | Comma-separated user ids allowed to run administrative commands |
| `DISCORD_ADMIN_ROLE_IDS` | one of these two | Comma-separated role ids allowed to run administrative commands |
| `DISCORD_ALLOWED_GUILD_ID` | no | When set, only this guild is served |

If neither an admin user id nor an admin role id is configured, the bot refuses to
start. There is no "allow everyone" fallback and no implicit trust in whoever invited
the bot.

### Mod connection

| Variable | Default | Meaning |
| --- | --- | --- |
| `MOD_API_URL` | `http://127.0.0.1:8765` | Must match `api.host` and `api.port` in the mod config |
| `MOD_API_SECRET` | — required | Must equal the mod's API secret; at least 16 characters |
| `MOD_API_USE_TLS` | `false` | Use `wss://` behind a TLS proxy |
| `MOD_API_ALLOW_INSECURE_TLS` | `false` | Accept self-signed certificates |
| `MOD_PROTOCOL_VERSION` | `1` | Must match the mod's `ProtocolVersion.CURRENT` |

### Behaviour

| Variable | Default | Meaning |
| --- | --- | --- |
| `MOD_REQUEST_TIMEOUT_MS` | `10000` | Fail an unanswered request after this long |
| `MOD_HEARTBEAT_TIMEOUT_MS` | `45000` | Treat the socket as dead after this much silence |
| `MOD_RECONNECT_MIN_MS` | `1000` | Reconnect backoff floor |
| `MOD_RECONNECT_MAX_MS` | `60000` | Reconnect backoff ceiling |
| `LOG_BUFFER_SIZE` | `200` | In-memory log and error lines retained for `/logs` |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn` or `error` |

### Secrets

The logger redacts the bot token and the mod API secret from every line it writes,
including exception messages. The bot never echoes `MOD_API_SECRET` or
`DISCORD_TOKEN` in a Discord response, an embed, a log line or an error message. If
you add code that logs configuration, log the parsed shape, never the raw secret.

---

## 6. Connecting the Fabric client to the Discord bot

1. Start Minecraft with the mod installed. The control server binds `127.0.0.1:8765`
   by default; the mod logs the bound port at startup.
2. Read the generated secret from `.minecraft/config/unionkitbot/unionkitbot.secret`.
3. Put the same value in the bot's `.env` as `MOD_API_SECRET`.
4. Start the bot. It connects on startup and logs a successful mod connection once
   the WebSocket handshake completes.
5. Run `/status` in Discord. You should see the state, the queue depth and the module
   toggles.

Startup order does not matter. Start the bot first and it keeps retrying until
Minecraft appears; start Minecraft first and the bot connects on its next attempt.

Because `api.allowRemote` defaults to `false`, the mod only accepts loopback peers. To
control a client from another machine, set `api.allowRemote` to `true`, set an explicit
`api.allowedOrigins` list and put a TLS proxy in front of it — and only do that on a
network you control.

---

## 7. Discord commands

All commands require an authorised user or role.

| Command | What it does |
| --- | --- |
| `/status` | Lifecycle state, connection state, uptime, queue depth, active task, module toggles, recent errors |
| `/start` | Starts automation; optional `reason` |
| `/stop` | Stops automation and idles the modules; optional `reason` |
| `/restart` | Cancels in-flight tasks and restarts the automation loop; optional `reason` |
| `/tasks` | Lists active and recently finished tasks; optional `limit` |
| `/task create` | Queues a task: `kind` (`navigate`, `deliver`, `scan`, `wait`, `return_home`, `recover`), `priority`, and either `x`/`y`/`z` or `target` |
| `/task info` | Shows one task by `id` |
| `/task cancel` | Cancels one task by `id` |
| `/logs` | Recent log lines and structured errors; optional `limit` and `level` |
| `/config show` | Current mod configuration, with secrets redacted |
| `/config module` | Toggles a module on or off |
| `/home` | Explains how to record a home point: from the in-game screen, or by sending a `home.set` frame with `x`/`y`/`z`. The controller never guesses coordinates |

Responses are embeds. Long lists are truncated with an explicit "and N more" rather
than being silently cut.

When the Minecraft client is unreachable, administrative commands answer with a clear
"client unreachable, retrying" message instead of hanging or surfacing a raw stack
trace.

---

## 8. API / WebSocket protocol

Full specification: [`shared/protocol/v1/README.md`](shared/protocol/v1/README.md).

Summary:

- The mod exposes a WebSocket at `/ws` (alias `/websocket`) and three HTTP routes:
  unauthenticated `GET /health`, authenticated `GET /status` and authenticated
  `POST /command`.
- WebSockets are the primary transport. Polling is not used, because the mod pushes
  `session.heartbeat`, `state.changed` and task events.
- Every frame is JSON with `v`, `type`, `id`, `ts` and `data`. `id` correlates a reply
  with its request. Unknown members are rejected rather than ignored.
- Authentication is a pre-shared secret in `X-UnionKitBot-Secret` (or `?secret=`). A
  missing or wrong secret is refused with `401` before any frame is read.
- Requests: `session.hello`, `session.ping`, `status.get`, `control.start`,
  `control.stop`, `control.restart`, `tasks.list`, `tasks.get`, `tasks.create`,
  `tasks.cancel`, `tasks.cancelall`, `logs.get`, `config.get`, `config.patch`,
  `modules.set`, `home.set`.
- Replies: `response.ok` and `response.error` with `{ code, message, path, retryable }`.
- Events: `session.welcome`, `session.heartbeat`, `state.changed`, `task.created`,
  `task.updated`, `task.completed`, `task.failed`, `task.cancelled`, `system.error`,
  `system.log`, `config.changed`, `modules.changed`, `session.goodbye`.

Example: queue a navigation task over HTTP.

```bash
curl -sS -X POST http://127.0.0.1:8765/command \
  -H "X-UnionKitBot-Secret: $MOD_API_SECRET" \
  -H 'Content-Type: application/json' \
  -d '{"v":1,"type":"tasks.create","id":"demo1","ts":0,
       "data":{"kind":"navigate","priority":"high","x":128,"y":70,"z":-64}}'
```

### Failure behaviour

- **Bot down, game up.** The control server simply has no peers. Automation, the queue
  and the state machine are unaffected, and events broadcast to zero peers.
- **Game down, bot up.** The bot retries with exponential backoff and full jitter
  between `MOD_RECONNECT_MIN_MS` and `MOD_RECONNECT_MAX_MS`. Commands report the client
  as unreachable rather than failing silently.
- **Mid-session disconnect.** Pending requests fail immediately with a retryable
  transport error and are never left hanging. Replies are matched by `id`, so a late
  reply from a dead socket cannot satisfy a new request.
- **Half-open socket.** If no frame and no pong arrives within the heartbeat window,
  the socket is terminated and reconnected instead of being trusted forever.

---

## 9. Configuration

### Mod: `config/unionkitbot/unionkitbot.json`

Written on first launch, fully commented, and re-readable without recompiling.
Editable from the in-game screen, from Discord with `/config`, or by hand.

`//` and `/* ... */` comments are accepted, so you can annotate your own changes
without them breaking the load. The mod rewrites the file without comments the next
time it saves, so keep anything you want to persist in the table below rather than
only in a comment. Unknown keys are rejected by name rather than ignored, which
turns a typo into a clear error instead of a silent fallback.

The file on disk omits `api.secret` entirely - the authoritative value lives in
`unionkitbot.secret`, and keeping it out means this JSON is safe to paste into a
bug report. If a load fails, the rejected file is copied to
`unionkitbot.json.invalid` and named in the log, so a hand-edit is never lost.

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Master switch. Automation stays off until you start it |
| `logLevel` | `INFO` | Diagnostics verbosity (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`) |
| `api.host` | `127.0.0.1` | Control server bind address |
| `api.port` | `8765` | Control server bind port |
| `api.allowRemote` | `false` | Accept non-loopback peers |
| `api.allowedOrigins` | `[]` | Origin allow-list for browser clients |
| `api.heartbeatSeconds` | `10` | Heartbeat interval |
| `api.maxPayloadBytes` | `262144` | Reject oversized frames |
| `modules.navigation` | `true` | Enable the navigation module |
| `modules.delivery` | `true` | Enable the delivery module |
| `modules.scanning` | `true` | Enable the scanning module |
| `modules.recovery` | `true` | Enable the recovery module |
| `limits.tickIntervalMs` | `100` | Minimum delay between agent ticks |
| `limits.maxAttempts` | `3` | Attempts before a task fails permanently |
| `limits.taskTimeoutMs` | `120000` | Wall-clock budget for one task attempt |
| `limits.stuckTicks` | `100` | Ticks without positional progress before recovery |
| `limits.maxQueueSize` | `128` | Maximum queued tasks |
| `navigation.arriveRadius` | `1.5` | Distance at which a destination counts as reached |
| `navigation.stepTicks` | `2` | Ticks between movement input updates |
| `navigation.home` | `null` | Recorded home point (`x`, `y`, `z`) |
| `delivery.interactRadius` | `3.0` | Distance at which a hand-off is attempted |
| `delivery.itemWhitelist` | `[]` | Deliverable item ids; empty means any |
| `delivery.defaultTargetPlayer` | `""` | Default recipient when a task omits one |
| `scan.radius` | `24` | Horizontal scan radius |
| `scan.verticalRadius` | `8` | Vertical scan radius |
| `scan.maxResults` | `256` | Maximum entries returned by one scan |
| `recovery.backoffMillis` | `1000` | Base delay between recovery attempts |
| `recovery.maxBackoffMillis` | `30000` | Backoff ceiling |
| `recovery.disconnectGraceMillis` | `5000` | Delay before leaving `DISCONNECTED` |

Values are clamped to safe ranges on load, so a typo cannot produce an unbounded
timeout or a negative queue limit. Unknown keys are rejected instead of being silently
ignored. The API secret is not in this file; it lives in `unionkitbot.secret` or in
`UNIONKITBOT_API_SECRET`.

### Discord bot: `.env`

Create it with `npm run setup` rather than by hand, and check it with
`npm run doctor`. The defaults suit a same-machine setup: loopback URL, no TLS,
one-second minimum backoff. `.env.example` documents every option.

### Keeping the two in sync

Only three values must agree: `api.host`/`api.port` ↔ `MOD_API_URL`, the mod's API
secret ↔ `MOD_API_SECRET`, and `ProtocolVersion.CURRENT` ↔ `MOD_PROTOCOL_VERSION`. If
the protocol versions disagree, the bot logs a loud warning rather than
misinterpreting frames.

---

## 10. Troubleshooting

**The bot says the client is unreachable.**
Check that Minecraft is running with the mod loaded, that the control server bound
successfully (the mod logs the bound port at startup), and that `MOD_API_URL` matches
`api.host`/`api.port`. A port collision makes the mod log a bind failure and continue
without a control server; automation still works, only remote control is missing.

**`401 unauthorized` on connect.**
The secrets differ. Re-read `config/unionkitbot/unionkitbot.secret` and copy the exact
value. If `UNIONKITBOT_API_SECRET` is set in the mod's environment, that value wins over
the file. Trailing whitespace and a trailing newline are common causes; the loader trims
both.

**`403 forbidden` on connect.**
The mod is bound to a non-loopback address while `api.allowRemote` is `false`. Either
connect over loopback, or explicitly enable remote access and set an origin allow-list.

**Commands time out.**
The client thread is busy, or the game is paused. The mod answers on the client thread
so it can read world state safely, and it fails a request with `client_thread_timeout`
rather than blocking the server thread. If the game is minimised or the machine is
asleep, expect timeouts.

**Automation does nothing while `/status` says `IDLE`.**
Check that automation is running (`enabled` defaults to `false`), that at least one
module is on, and that the queue is not empty. `/tasks` shows whether a task is pending,
and `/logs` shows why a task failed.

**A task keeps failing.**
`/task info <id>` shows `attempts` and `failureReason`. A navigation task that cannot
reach its target reports its last known position and the stuck counter. The recovery
module retries up to `limits.maxAttempts` times before failing the task permanently.

**State is `DEAD` or `DISCONNECTED` and never recovers.**
Respawn or reconnect. The state machine treats `DEAD` and `DISCONNECTED` as terminal
until the world reports a live player again; it does not force transitions it cannot
verify.

**Minecraft crashed, or a dimension change broke something.**
It should not. Every access to the player, world, network handler, entity and screen is
null-checked, and world unloads, respawns and dimension changes reset the relevant
observation state instead of being treated as errors. If you find a path that crashes,
that is a bug: report it with the `system.error` payload from `/logs`, which includes
the category and the exception class.

**The GUI shows stale data.**
It does not cache across frames: each frame re-reads the runtime snapshot, so it cannot
hold a stale task or a stale error. If a value looks wrong, `/status` will agree with
the screen — both read the same snapshot.

**Discord commands are missing.**
Run `npm run register-commands` again. With a `DISCORD_GUILD_ID` they appear immediately;
globally they can take up to an hour. If the bot is in several guilds and
`DISCORD_ALLOWED_GUILD_ID` is set, only that guild is served.

**`missing required environment variable` on startup.**
The bot validates its configuration before connecting to anything and exits naming the
missing variable. It never starts half-configured.

**Tests fail on a fresh clone.**
The mod tests need the Minecraft artifacts once; run `./gradlew build` online first, after
which `--offline` works. The bot tests are self-contained and need only `npm install`.

---

## Testing

```bash
# Mod: state machine, task queue, agent loop, protocol envelope, auth tokens
cd minecraft-mod && ./gradlew test

# Bot: env parsing, envelope validation, backoff, reconnect, authorizer, logging
cd discord-bot && npm test
```

The bot suite drives a real in-process WebSocket server that mimics the mod's
handshake, framing and error shapes, so reconnection, correlation and authentication
are exercised over an actual socket rather than a stub.

## License

MIT.
