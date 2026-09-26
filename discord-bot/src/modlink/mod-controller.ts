import type { Logger } from "../logging/logger.js";
import {
  booleanAt,
  numberAt,
  objectAt,
  stringAt,
  type Envelope,
  type JsonObject,
} from "../protocol/envelope.js";
import {
  isBotState,
  type BotState,
  type ModuleName,
  type TaskKind,
  type TaskPriority,
} from "../protocol/messages.js";
import { HttpClient, TransportError } from "./http-client.js";
import { ModLink, type LinkState } from "./mod-link.js";

/** A task as reported by the mod. */
export interface TaskView {
  readonly id: string;
  readonly kind: string;
  readonly priority: string;
  readonly status: string;
  readonly owner: string | undefined;
  readonly attempts: number;
  readonly progress: number;
  readonly failureReason: string | undefined;
  readonly submittedAt: number;
}

/** A status snapshot assembled from `status.get`. */
export interface StatusView {
  readonly state: BotState;
  readonly running: boolean;
  readonly enabled: boolean;
  readonly logLevel: string;
  readonly uptimeTicks: number;
  readonly lastAction: string;
  readonly peers: number;
  readonly protocolVersion: number;
  readonly modVersion: string;
  readonly queueActive: number;
  readonly queuePending: number;
  readonly queueFinished: number;
  readonly activeTask: TaskView | undefined;
  readonly modules: Readonly<Record<string, boolean>>;
  readonly recentErrors: readonly string[];
}

/** A heartbeat as reported by the mod. */
export interface HeartbeatView {
  readonly state: BotState | undefined;
  readonly running: boolean | undefined;
  readonly timestamp: number;
  readonly activeTasks: number | undefined;
  readonly pendingTasks: number | undefined;
  readonly lastAction: string | undefined;
}

/** A structured error event. */
export interface ErrorView {
  readonly timestamp: number;
  readonly category: string;
  readonly message: string;
  readonly exception: string | undefined;
  readonly state: string | undefined;
}

/** A log entry mirrored from the mod. */
export interface ModLogView {
  readonly timestamp: number;
  readonly level: string;
  readonly message: string;
  readonly scope: string;
}

function parseTask(value: JsonObject): TaskView | undefined {
  const id = stringAt(value, "id");
  if (id === undefined) {
    return undefined;
  }
  return {
    id,
    kind: stringAt(value, "kind") ?? "unknown",
    priority: stringAt(value, "priority") ?? "normal",
    status: stringAt(value, "status") ?? "PENDING",
    owner: stringAt(value, "owner"),
    attempts: numberAt(value, "attempts") ?? 0,
    progress: numberAt(value, "progress") ?? 0,
    failureReason: stringAt(value, "failureReason"),
    submittedAt: numberAt(value, "submittedAt") ?? 0,
  };
}

/**
 * High-level, typed facade over the Fabric mod.
 *
 * This is the only place that knows how to turn protocol frames into the shapes
 * the Discord commands consume. It caches the last known status so `/status` still
 * answers meaningfully while the Minecraft client is offline.
 */
export class ModController {
  private readonly http: HttpClient;
  private readonly link: ModLink;
  private readonly logger: Logger;
  private lastStatus: StatusView | undefined;
  private lastHeartbeatAt = 0;
  private lastHeartbeatState: BotState | undefined;

  public constructor(options: {
    baseUrl: string;
    secret: string;
    useTls: boolean;
    allowInsecureTls: boolean;
    protocolVersion: number;
    requestTimeoutMs: number;
    heartbeatTimeoutMs: number;
    reconnectMinMs: number;
    reconnectMaxMs: number;
    logger: Logger;
    backoffRandom?: () => number;
  }) {
    this.logger = options.logger;
    this.http = new HttpClient({
      baseUrl: options.baseUrl,
      secret: options.secret,
      timeoutMs: options.requestTimeoutMs,
      protocolVersion: options.protocolVersion,
      logger: options.logger,
    });
    this.link = new ModLink({
      baseUrl: options.baseUrl,
      secret: options.secret,
      useTls: options.useTls,
      allowInsecureTls: options.allowInsecureTls,
      protocolVersion: options.protocolVersion,
      requestTimeoutMs: options.requestTimeoutMs,
      heartbeatTimeoutMs: options.heartbeatTimeoutMs,
      reconnectMinMs: options.reconnectMinMs,
      reconnectMaxMs: options.reconnectMaxMs,
      logger: options.logger,
      ...(options.backoffRandom === undefined ? {} : { backoffRandom: options.backoffRandom }),
    });
  }

  public get linkState(): LinkState {
    return this.link.currentState();
  }

  public get reconnects(): number {
    return this.link.reconnectCount();
  }

  public get cachedStatus(): StatusView | undefined {
    return this.lastStatus;
  }

  public get lastHeartbeat(): { at: number; state: BotState | undefined } {
    return { at: this.lastHeartbeatAt, state: this.lastHeartbeatState };
  }

  public onStateChange(listener: (state: LinkState) => void): void {
    this.link.on("state", listener);
  }

  public onDisconnect(listener: (info: { code: number; reason: string }) => void): void {
    this.link.on("disconnected", listener);
  }

  public onConnected(listener: (welcome: Envelope) => void): void {
    this.link.on("connected", listener);
  }

  public onError(listener: (view: ErrorView) => void): void {
    this.link.on("envelope", (envelope) => {
      if (envelope.type === "ERROR") {
        listener(parseError(envelope.data));
      }
    });
  }

  public onLog(listener: (view: ModLogView) => void): void {
    this.link.on("envelope", (envelope) => {
      if (envelope.type === "LOG") {
        listener(parseLog(envelope.data));
      }
    });
  }

  public onHeartbeat(listener: (view: HeartbeatView) => void): void {
    this.link.on("envelope", (envelope) => {
      if (envelope.type !== "HEARTBEAT") {
        return;
      }
      const rawState = stringAt(envelope.data, "state");
      const view: HeartbeatView = {
        state: isBotState(rawState) ? rawState : undefined,
        running: booleanAt(envelope.data, "running"),
        timestamp: numberAt(envelope.data, "timestamp") ?? Date.now(),
        activeTasks: numberAt(envelope.data, "activeTasks"),
        pendingTasks: numberAt(envelope.data, "pendingTasks"),
        lastAction: stringAt(envelope.data, "lastAction"),
      };
      this.lastHeartbeatAt = Date.now();
      this.lastHeartbeatState = view.state;
      listener(view);
    });
  }

  public start(): void {
    this.link.start();
  }

  public stop(): void {
    this.link.stop();
  }

  /**
   * Probes the unauthenticated `/health` endpoint.
   *
   * @returns true when the mod answered
   */
  public async probeHealth(): Promise<boolean> {
    try {
      await this.http.health();
      return true;
    } catch (error) {
      if (error instanceof TransportError) {
        this.logger.debug("mod.controller", "health probe failed", error.message);
      }
      return false;
    }
  }

  public async status(): Promise<StatusView> {
    const envelope = await this.link.request("STATUS_GET");
    const view = this.parseStatus(envelope.data);
    this.lastStatus = view;
    return view;
  }

  public async startAutomation(reason: string): Promise<StatusView> {
    await this.link.request("START", { reason });
    return await this.status();
  }

  public async stopAutomation(reason: string): Promise<StatusView> {
    await this.link.request("STOP", { reason });
    return await this.status();
  }

  public async restartAutomation(reason: string): Promise<{ cancelledTasks: number; status: StatusView }> {
    const envelope = await this.link.request("RESTART", { reason });
    const cancelledTasks = numberAt(envelope.data, "cancelledTasks") ?? 0;
    return { cancelledTasks, status: await this.status() };
  }

  public async listTasks(limit: number): Promise<{ active: TaskView[]; finished: TaskView[] }> {
    const envelope = await this.link.request("TASKS_LIST", { limit });
    const active = (envelope.data["active"] as unknown[] | undefined) ?? [];
    const finished = (envelope.data["finished"] as unknown[] | undefined) ?? [];
    return {
      active: active.filter(isJsonObject).map(parseTask).filter(isDefined),
      finished: finished.filter(isJsonObject).map(parseTask).filter(isDefined),
    };
  }

  public async getTask(id: string): Promise<TaskView> {
    const envelope = await this.link.request("TASK_GET", { id });
    const task = objectAt(envelope.data, "task");
    if (task === undefined) {
      throw new TransportError("the mod returned no task payload", false);
    }
    const parsed = parseTask(task);
    if (parsed === undefined) {
      throw new TransportError("the mod returned a task without an id", false);
    }
    return parsed;
  }

  public async createTask(input: {
    kind: TaskKind;
    priority: TaskPriority;
    parameters?: Record<string, unknown>;
  }): Promise<TaskView> {
    const payload: Record<string, unknown> = {
      kind: input.kind,
      priority: input.priority,
    };
    if (input.parameters !== undefined) {
      payload["parameters"] = input.parameters;
    }
    const envelope = await this.link.request("TASK_CREATE", payload);
    const task = objectAt(envelope.data, "task");
    if (task === undefined) {
      throw new TransportError("the mod accepted the task but returned no payload", false);
    }
    const parsed = parseTask(task);
    if (parsed === undefined) {
      throw new TransportError("the mod returned a task without an id", false);
    }
    return parsed;
  }

  public async cancelTask(id: string, reason: string): Promise<void> {
    await this.link.request("TASK_CANCEL", { id, reason });
  }

  public async cancelAllTasks(reason: string): Promise<number> {
    const envelope = await this.link.request("TASKS_CANCEL_ALL", { reason });
    return numberAt(envelope.data, "cancelled") ?? 0;
  }

  public async logs(limit: number): Promise<{ entries: ModLogView[]; errors: ErrorView[] }> {
    const envelope = await this.link.request("LOGS_GET", { limit });
    const entries = (envelope.data["entries"] as unknown[] | undefined) ?? [];
    const errors = (envelope.data["errors"] as unknown[] | undefined) ?? [];
    return {
      entries: entries.filter(isJsonObject).map(parseLog),
      errors: errors.filter(isJsonObject).map(parseError),
    };
  }

  public async config(): Promise<JsonObject> {
    const envelope = await this.link.request("CONFIG_GET");
    return objectAt(envelope.data, "config") ?? {};
  }

  public async patchConfig(patch: JsonObject): Promise<JsonObject> {
    const envelope = await this.link.request("CONFIG_PATCH", { config: patch });
    return objectAt(envelope.data, "config") ?? {};
  }

  public async setModules(modules: Partial<Record<ModuleName, boolean>>): Promise<JsonObject> {
    const envelope = await this.link.request("MODULES_SET", { modules: modules as JsonObject });
    return objectAt(envelope.data, "modules") ?? {};
  }

  private parseStatus(data: JsonObject): StatusView {
    const rawState = stringAt(data, "state");
    const queue = objectAt(data, "queue");
    const activeTask = objectAt(data, "activeTask");
    const modulesObject = objectAt(data, "modules");
    const modules: Record<string, boolean> = {};
    if (modulesObject !== undefined) {
      for (const [name, value] of Object.entries(modulesObject)) {
        // The mod reports each module as an object; a bare boolean is also accepted
        // so the controller tolerates a future flattening of the status payload.
        if (typeof value === "boolean") {
          modules[name] = value;
          continue;
        }
        if (isJsonObject(value)) {
          modules[name] = booleanAt(value, "enabled") ?? false;
        }
      }
    }
    const recentErrors = (data["recentErrors"] as unknown[] | undefined) ?? [];

    return {
      state: isBotState(rawState) ? rawState : "OFFLINE",
      running: booleanAt(data, "running") ?? false,
      enabled: booleanAt(data, "enabled") ?? false,
      logLevel: stringAt(data, "logLevel") ?? "info",
      uptimeTicks: numberAt(data, "uptimeTicks") ?? 0,
      lastAction: stringAt(data, "lastAction") ?? "",
      peers: numberAt(data, "peers") ?? 0,
      protocolVersion: numberAt(data, "protocolVersion") ?? 0,
      modVersion: stringAt(data, "modVersion") ?? "unknown",
      queueActive: queue === undefined ? 0 : (numberAt(queue, "active") ?? 0),
      queuePending: queue === undefined ? 0 : (numberAt(queue, "pending") ?? 0),
      queueFinished: queue === undefined ? 0 : (numberAt(queue, "finished") ?? 0),
      activeTask: activeTask === undefined ? undefined : parseTask(activeTask),
      modules,
      recentErrors: recentErrors
        .filter(isJsonObject)
        .map((entry) => stringAt(entry, "message") ?? "unspecified error"),
    };
  }
}

function parseError(value: JsonObject): ErrorView {
  return {
    timestamp: numberAt(value, "timestamp") ?? Date.now(),
    category: stringAt(value, "category") ?? "general",
    message: stringAt(value, "message") ?? "unspecified failure",
    exception: stringAt(value, "cause") ?? stringAt(value, "exception"),
    state: stringAt(value, "state"),
  };
}

/**
 * The mod labels a log entry's subsystem `category`, not `scope`.
 */
function parseLog(value: JsonObject): ModLogView {
  return {
    timestamp: numberAt(value, "timestamp") ?? Date.now(),
    level: (stringAt(value, "level") ?? "INFO").toLowerCase(),
    message: stringAt(value, "message") ?? "",
    scope: stringAt(value, "category") ?? stringAt(value, "scope") ?? "mod",
  };
}

function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isDefined<T>(value: T | undefined): value is T {
  return value !== undefined;
}
