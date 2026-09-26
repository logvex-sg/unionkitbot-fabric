import { describe, expect, it } from "vitest";

import { loadDotEnv } from "../src/config/dotenv.js";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

function withEnvFile(content: string, run: (path: string) => void): void {
  const directory = mkdtempSync(join(tmpdir(), "unionkitbot-dotenv-"));
  const path = join(directory, ".env");
  writeFileSync(path, content, "utf8");
  try {
    run(path);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

describe("loadDotEnv", () => {
  it("returns an empty list when the file is absent", () => {
    expect(loadDotEnv(join(tmpdir(), "definitely-missing-unionkitbot.env"))).toEqual([]);
  });

  it("parses keys, comments and blank lines", () => {
    withEnvFile(
      [
        "# a comment",
        "",
        "UNIONKITBOT_TEST_ALPHA=one",
        "UNIONKITBOT_TEST_BETA = two ",
        "not a valid line",
        "UNIONKITBOT_TEST_GAMMA=",
      ].join("\n"),
      (path) => {
        const applied = loadDotEnv(path);
        expect(applied).toEqual([
          "UNIONKITBOT_TEST_ALPHA",
          "UNIONKITBOT_TEST_BETA",
          "UNIONKITBOT_TEST_GAMMA",
        ]);
        expect(process.env["UNIONKITBOT_TEST_ALPHA"]).toBe("one");
        expect(process.env["UNIONKITBOT_TEST_BETA"]).toBe("two");
        expect(process.env["UNIONKITBOT_TEST_GAMMA"]).toBe("");
        delete process.env["UNIONKITBOT_TEST_ALPHA"];
        delete process.env["UNIONKITBOT_TEST_BETA"];
        delete process.env["UNIONKITBOT_TEST_GAMMA"];
      },
    );
  });

  it("strips surrounding quotes", () => {
    withEnvFile(
      ['UNIONKITBOT_TEST_QUOTED="quoted value"', "UNIONKITBOT_TEST_SINGLE='single value'"].join("\n"),
      (path) => {
        loadDotEnv(path);
        expect(process.env["UNIONKITBOT_TEST_QUOTED"]).toBe("quoted value");
        expect(process.env["UNIONKITBOT_TEST_SINGLE"]).toBe("single value");
        delete process.env["UNIONKITBOT_TEST_QUOTED"];
        delete process.env["UNIONKITBOT_TEST_SINGLE"];
      },
    );
  });

  it("does not overwrite a variable already present in the environment", () => {
    process.env["UNIONKITBOT_TEST_PRESET"] = "from-process";
    withEnvFile("UNIONKITBOT_TEST_PRESET=from-file", (path) => {
      const applied = loadDotEnv(path);
      expect(applied).not.toContain("UNIONKITBOT_TEST_PRESET");
      expect(process.env["UNIONKITBOT_TEST_PRESET"]).toBe("from-process");
    });
    delete process.env["UNIONKITBOT_TEST_PRESET"];
  });
});
