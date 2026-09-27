/**
 * Interactive configuration wizard: `npm run setup`.
 *
 * Goal: get from a fresh clone to a working `.env` without the operator having
 * to hunt through the Discord Developer Portal or hand-copy the mod's secret.
 * It never prints a secret back to the terminal, and re-running it is safe -
 * existing answers are offered as defaults and unrelated lines are preserved.
 */

import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createInterface } from "node:readline/promises";
import { dirname, join, resolve } from "node:path";

import {
  mergeEnvFile,
  missingFields,
  modConfigCandidates,
  readEnvFile,
  validateValues,
  type FieldSpec,
} from "./config/wizard.js";

/** The mod's secret file name, mirroring `ConfigManager.SECRET_FILE`. */
const MOD_SECRET_FILE = "unionkitbot.secret";

/** Renders a value for display without revealing a secret. */
function preview(spec: FieldSpec, value: string): string {
  if (spec.secret) {
    return `<${value.length} characters, hidden>`;
  }
  return value;
}

/** Locates the mod's secret file, or returns `undefined` when not found. */
function findModSecret(env: NodeJS.ProcessEnv): { path: string; secret: string } | undefined {
  for (const directory of modConfigCandidates(process.platform, env)) {
    const path = join(directory, MOD_SECRET_FILE);
    if (!existsSync(path)) {
      continue;
    }
    const secret = readFileSync(path, "utf8").trim();
    if (secret.length > 0) {
      return { path, secret };
    }
  }
  return undefined;
}

async function main(): Promise<number> {
  const envPath = resolve(process.env["UNIONKITBOT_ENV_FILE"] ?? ".env");
  const examplePath = resolve(".env.example");

  if (!existsSync(examplePath)) {
    process.stderr.write(
      `cannot find ${examplePath}; run this from the discord-bot directory (cd discord-bot)\n`,
    );
    return 1;
  }

  const current = existsSync(envPath) ? readFileSync(envPath, "utf8") : undefined;
  const existing = readEnvFile(current);
  const values = new Map<string, string>(existing);

  // Fill the mod secret from disk before prompting, so the operator is not asked
  // for a value the machine already has.
  if (!values.has("MOD_API_SECRET")) {
    const found = findModSecret(process.env);
    if (found !== undefined) {
      values.set("MOD_API_SECRET", found.secret);
      process.stdout.write(`Found the mod secret at ${found.path}\n`);
    }
  } else {
    process.stdout.write("Using the MOD_API_SECRET already in .env\n");
  }

  const outstanding = missingFields(values);
  const rl = createInterface({ input: process.stdin, output: process.stdout });

  try {
    process.stdout.write(
      "\nUnionKitBot Discord bot setup. Press Enter to keep the current value.\n" +
        "Values are written to " +
        envPath +
        ", which is git-ignored.\n\n",
    );

    for (const spec of outstanding) {
      const existingValue = values.get(spec.key);
      // Secrets are never echoed, so an existing secret cannot be shown as a default.
      const shown = existingValue === undefined ? "" : preview(spec, existingValue);
      for (;;) {
        const suffix = shown === "" ? "" : ` [${shown}]`;
        const answer = (await rl.question(`${spec.label}${suffix}\n  ${spec.help}\n> `)).trim();
        if (answer !== "") {
          values.set(spec.key, answer);
          break;
        }
        if (existingValue !== undefined) {
          break;
        }
        process.stdout.write("  A value is required.\n");
      }
    }
  } finally {
    rl.close();
  }

  const issues = validateValues(values);
  if (issues.length > 0) {
    process.stderr.write("\nThe following values need attention:\n");
    for (const issue of issues) {
      process.stderr.write(`  ${issue.key}: ${issue.message}\n`);
    }
    process.stderr.write("Nothing was written. Fix the values and run `npm run setup` again.\n");
    return 2;
  }

  const merged = mergeEnvFile(current, values);
  mkdirSync(dirname(envPath), { recursive: true });
  writeFileSync(envPath, merged.text, { encoding: "utf8", mode: 0o600 });
  try {
    // Best effort: the mode above is ignored on some filesystems.
    chmodSync(envPath, 0o600);
  } catch {
    process.stdout.write("Note: could not restrict permissions on .env; do it manually.\n");
  }

  const changed = [...merged.applied, ...merged.appended];
  process.stdout.write(`\nWrote ${envPath} (${changed.length} value(s) set).\n`);
  process.stdout.write("\nNext steps:\n");
  process.stdout.write("  npm run build\n");
  process.stdout.write("  npm run doctor    # check the token and find the mod\n");
  process.stdout.write("  npm start\n");
  return 0;
}

main()
  .then((code) => {
    process.exit(code);
  })
  .catch((error: unknown) => {
    process.stderr.write(`setup failed: ${error instanceof Error ? error.message : String(error)}\n`);
    process.exit(1);
  });
