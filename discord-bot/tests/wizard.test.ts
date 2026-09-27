import { describe, expect, it } from "vitest";

import {
  FIELDS,
  formatEnvValue,
  mergeEnvFile,
  missingFields,
  modConfigCandidates,
  readEnvFile,
  validateValues,
} from "../src/config/wizard.js";

const SAMPLE = [
  "# UnionKitBot env",
  "DISCORD_TOKEN=",
  "DISCORD_GUILD_ID=123456789012345678",
  "",
  "# keep this comment",
  "LOG_LEVEL=debug",
].join("\n");

describe("mergeEnvFile", () => {
  it("fills an empty key while preserving comments and unrelated keys", () => {
    const result = mergeEnvFile(SAMPLE, new Map([["DISCORD_TOKEN", "abc.token.value"]]));

    expect(result.applied).toEqual(["DISCORD_TOKEN"]);
    expect(result.appended).toEqual([]);
    expect(result.text).toContain("# keep this comment");
    expect(result.text).toContain("LOG_LEVEL=debug");
    expect(result.text).toContain("DISCORD_GUILD_ID=123456789012345678");
    expect(result.text).toContain("DISCORD_TOKEN=abc.token.value");
  });

  it("does not duplicate a key that already exists", () => {
    const result = mergeEnvFile(SAMPLE, new Map([["LOG_LEVEL", "info"]]));
    const occurrences = result.text.split("\n").filter((line) => line.startsWith("LOG_LEVEL="));

    expect(occurrences).toEqual(["LOG_LEVEL=info"]);
    expect(result.applied).toEqual(["LOG_LEVEL"]);
  });

  it("appends keys the file does not mention yet", () => {
    const result = mergeEnvFile(SAMPLE, new Map([["MOD_API_SECRET", "0123456789abcdef"]]));

    expect(result.appended).toEqual(["MOD_API_SECRET"]);
    expect(result.text).toContain("MOD_API_SECRET=0123456789abcdef");
  });

  it("creates a header when there was no file", () => {
    const result = mergeEnvFile(undefined, new Map([["DISCORD_TOKEN", "abc.token.value"]]));

    expect(result.text).toContain("Never commit this file");
    expect(result.text).toContain("DISCORD_TOKEN=abc.token.value");
  });

  it("ends with exactly one newline", () => {
    const result = mergeEnvFile(`${SAMPLE}\n\n\n`, new Map([["LOG_LEVEL", "info"]]));

    expect(result.text.endsWith("\n")).toBe(true);
    expect(result.text.endsWith("\n\n")).toBe(false);
  });

  it("is idempotent when run twice", () => {
    const first = mergeEnvFile(SAMPLE, new Map([["DISCORD_TOKEN", "abc.token.value"]]));
    const second = mergeEnvFile(first.text, new Map([["DISCORD_TOKEN", "abc.token.value"]]));

    expect(second.text).toBe(first.text);
  });

  it("quotes values a dotenv parser could misread", () => {
    expect(formatEnvValue("plain")).toBe("plain");
    expect(formatEnvValue("has space")).toBe('"has space"');
    expect(formatEnvValue("has#hash")).toBe('"has#hash"');
  });
});

describe("readEnvFile", () => {
  it("reads values and ignores comments and blank lines", () => {
    const values = readEnvFile(SAMPLE);

    expect(values.get("DISCORD_GUILD_ID")).toBe("123456789012345678");
    expect(values.get("LOG_LEVEL")).toBe("debug");
    expect(values.has("DISCORD_TOKEN")).toBe(false);
  });

  it("returns an empty map for an absent file", () => {
    expect(readEnvFile(undefined).size).toBe(0);
  });

  it("round-trips a quoted value", () => {
    const text = mergeEnvFile(undefined, new Map([["A_KEY", "has space"]])).text;
    expect(readEnvFile(text).get("A_KEY")).toBe("has space");
  });
});

describe("validateValues", () => {
  const good = new Map([
    ["DISCORD_TOKEN", "MTAx.yyyyyyyyyyyyyyyyyyyyyyyy.zzzzzzzzzzzzzzzzzzzzzzzzzzz"],
    ["DISCORD_APPLICATION_ID", "123456789012345678"],
    ["DISCORD_ADMIN_USER_IDS", "111111111111111111"],
    ["MOD_API_SECRET", "0123456789abcdef"],
  ]);

  it("accepts a complete, well-formed set", () => {
    expect(validateValues(good)).toEqual([]);
  });

  it("uses the same keys the setup script prompts for", () => {
    const prompted = FIELDS.map((field) => field.key).sort();
    expect(prompted).toEqual([...good.keys()].sort());
  });

  it("reports a missing required value", () => {
    const values = new Map(good);
    values.delete("MOD_API_SECRET");

    expect(validateValues(values).map((issue) => issue.key)).toContain("MOD_API_SECRET");
  });

  it("rejects a non-numeric admin id", () => {
    const values = new Map(good);
    values.set("DISCORD_ADMIN_USER_IDS", "not-an-id");

    expect(validateValues(values).map((issue) => issue.message)).toEqual([
      "'not-an-id' is not a Discord user ID",
    ]);
  });

  it("accepts several comma-separated admin ids", () => {
    const values = new Map(good);
    values.set("DISCORD_ADMIN_USER_IDS", "111111111111111111, 222222222222222222");

    expect(validateValues(values)).toEqual([]);
  });

  it("rejects a too-short mod secret", () => {
    const values = new Map(good);
    values.set("MOD_API_SECRET", "short");

    expect(validateValues(values).map((issue) => issue.key)).toContain("MOD_API_SECRET");
  });

  it("rejects an application id that is not a snowflake", () => {
    const values = new Map(good);
    values.set("DISCORD_APPLICATION_ID", "abc");

    expect(validateValues(values).map((issue) => issue.key)).toContain("DISCORD_APPLICATION_ID");
  });
});

describe("missingFields", () => {
  it("reports keys that are absent", () => {
    expect(missingFields(new Map()).map((field) => field.key)).toEqual(FIELDS.map((field) => field.key));
  });

  it("treats a leftover template placeholder as missing", () => {
    const values = new Map([["DISCORD_TOKEN", "<your-token>"]]);
    expect(missingFields(values).map((field) => field.key)).toContain("DISCORD_TOKEN");
  });

  it("does not report keys that are filled in", () => {
    const values = new Map([
      ["DISCORD_TOKEN", "a-real-looking-token"],
      ["DISCORD_APPLICATION_ID", "123456789012345678"],
      ["DISCORD_ADMIN_USER_IDS", "111111111111111111"],
      ["MOD_API_SECRET", "0123456789abcdef"],
    ]);

    expect(missingFields(values)).toEqual([]);
  });
});

describe("modConfigCandidates", () => {
  it("prefers an explicit override", () => {
    const candidates = modConfigCandidates("linux", {
      HOME: "/home/someone",
      UNIONKITBOT_MOD_DIR: "/custom/config",
    });

    expect(candidates).toEqual(["/custom/config"]);
  });

  it("uses the standard Linux layout", () => {
    const candidates = modConfigCandidates("linux", { HOME: "/home/someone" });
    expect(candidates[0]).toBe("/home/someone/.minecraft/config/unionkitbot");
  });

  it("uses the macOS layout", () => {
    const candidates = modConfigCandidates("darwin", { HOME: "/Users/someone" });
    expect(candidates[0]).toBe("/Users/someone/Library/Application Support/minecraft/config/unionkitbot");
  });

  it("uses the Windows layout", () => {
    const candidates = modConfigCandidates("win32", {
      HOME: "C:\\Users\\someone",
      APPDATA: "C:\\Users\\someone\\AppData\\Roaming",
    });
    expect(candidates[0]).toContain(".minecraft");
  });
});
