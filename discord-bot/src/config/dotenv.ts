import { readFileSync } from "node:fs";
import { existsSync } from "node:fs";

/**
 * Loads a `.env` file into the process environment without adding a dependency.
 *
 * The parser is deliberately strict about the subset it supports (`KEY=value` with
 * optional single/double quotes) so it cannot silently misinterpret a value. It
 * never prints what it loaded.
 *
 * @param path the file to read, defaulting to `.env` in the working directory
 * @returns the keys that were applied, for logging
 */
export function loadDotEnv(path = ".env"): string[] {
  if (!existsSync(path)) {
    return [];
  }
  const applied: string[] = [];
  const content = readFileSync(path, "utf8");
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line === "" || line.startsWith("#")) {
      continue;
    }
    const separator = line.indexOf("=");
    if (separator <= 0) {
      continue;
    }
    const key = line.slice(0, separator).trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key)) {
      continue;
    }
    let value = line.slice(separator + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"') && value.length >= 2) ||
      (value.startsWith("'") && value.endsWith("'") && value.length >= 2)
    ) {
      value = value.slice(1, -1);
    }
    // An explicitly exported variable wins over the file, matching dotenv semantics.
    if (process.env[key] === undefined) {
      process.env[key] = value;
      applied.push(key);
    }
  }
  return applied;
}
