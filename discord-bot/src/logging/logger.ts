import type { LogLevel } from "../config/env.js";

const LEVEL_ORDER: Record<LogLevel, number> = {
  debug: 10,
  info: 20,
  warn: 30,
  error: 40,
};

/**
 * Values that must never appear verbatim in output.
 *
 * The logger redacts registered secrets from every message and structured field,
 * so a token cannot leak through an exception message or a stray interpolation.
 */
export class RedactionRegistry {
  private readonly secrets = new Set<string>();

  public register(secret: string | undefined): void {
    if (secret !== undefined && secret.length >= 4) {
      this.secrets.add(secret);
    }
  }

  public redact(text: string): string {
    let result = text;
    for (const secret of this.secrets) {
      if (result.includes(secret)) {
        result = result.split(secret).join("<redacted>");
      }
    }
    return result;
  }

  public redactValue(value: unknown): unknown {
    if (typeof value === "string") {
      return this.redact(value);
    }
    if (Array.isArray(value)) {
      return value.map((entry) => this.redactValue(entry));
    }
    if (value instanceof Error) {
      return this.redact(value.message);
    }
    if (value !== null && typeof value === "object") {
      const out: Record<string, unknown> = {};
      for (const [key, entry] of Object.entries(value)) {
        out[key] = this.redactValue(entry);
      }
      return out;
    }
    return value;
  }
}

export interface LogRecord {
  readonly timestamp: number;
  readonly level: LogLevel;
  readonly scope: string;
  readonly message: string;
  readonly detail: string | undefined;
}

export type LogSink = (record: LogRecord) => void;

/**
 * Minimal structured logger.
 *
 * Records are also pushed into a bounded in-memory ring buffer so `/logs` can
 * report recent activity even when the console output has scrolled away.
 */
export class Logger {
  private readonly redaction: RedactionRegistry;
  private readonly buffer: LogRecord[] = [];
  private readonly sinks: LogSink[] = [];
  private readonly capacity: number;
  private threshold: number;

  public constructor(level: LogLevel, capacity: number, redaction: RedactionRegistry) {
    this.threshold = LEVEL_ORDER[level];
    this.capacity = capacity;
    this.redaction = redaction;
  }

  public setLevel(level: LogLevel): void {
    this.threshold = LEVEL_ORDER[level];
  }

  public addSink(sink: LogSink): void {
    this.sinks.push(sink);
  }

  public debug(scope: string, message: string, detail?: unknown): void {
    this.write("debug", scope, message, detail);
  }

  public info(scope: string, message: string, detail?: unknown): void {
    this.write("info", scope, message, detail);
  }

  public warn(scope: string, message: string, detail?: unknown): void {
    this.write("warn", scope, message, detail);
  }

  public error(scope: string, message: string, detail?: unknown): void {
    this.write("error", scope, message, detail);
  }

  /**
   * @param limit maximum number of records to return
   * @param minimum only return records at or above this level
   * @return recent records, newest last
   */
  public recent(limit: number, minimum?: LogLevel): LogRecord[] {
    const floor = minimum === undefined ? this.threshold : LEVEL_ORDER[minimum];
    return this.buffer.filter((record) => LEVEL_ORDER[record.level] >= floor).slice(-limit);
  }

  private write(level: LogLevel, scope: string, message: string, detail: unknown): void {
    if (LEVEL_ORDER[level] < this.threshold) {
      return;
    }
    const record: LogRecord = {
      timestamp: Date.now(),
      level,
      scope,
      message: this.redaction.redact(message),
      detail: this.describeDetail(detail),
    };
    this.buffer.push(record);
    while (this.buffer.length > this.capacity) {
      this.buffer.shift();
    }
    const line = `${new Date(record.timestamp).toISOString()} ${level.toUpperCase()} [${scope}] ${record.message}${
      record.detail === undefined ? "" : ` :: ${record.detail}`
    }`;
    for (const sink of this.sinks) {
      sink(record);
    }
    if (level === "error" || level === "warn") {
      process.stderr.write(`${line}\n`);
    } else {
      process.stdout.write(`${line}\n`);
    }
  }

  private describeDetail(detail: unknown): string | undefined {
    if (detail === undefined) {
      return undefined;
    }
    if (detail instanceof Error) {
      return this.redaction.redact(`${detail.name}: ${detail.message}`);
    }
    try {
      return this.redaction.redact(JSON.stringify(this.redaction.redactValue(detail)));
    } catch {
      // A circular or non-serialisable detail must not break logging.
      return "<unserialisable>";
    }
  }
}
