import { describe, expect, it } from "vitest";

import { Backoff } from "../src/modlink/backoff.js";

describe("Backoff", () => {
  it("rejects an inverted window", () => {
    expect(() => new Backoff({ minMs: 100, maxMs: 50 })).toThrowError(RangeError);
  });

  it("rejects a non-positive minimum", () => {
    expect(() => new Backoff({ minMs: 0, maxMs: 50 })).toThrowError(RangeError);
  });

  it("grows exponentially with full jitter", () => {
    // A deterministic random of 1 removes jitter so the ceiling is observable.
    const backoff = new Backoff({ minMs: 100, maxMs: 10_000, random: () => 1 });
    expect(backoff.nextDelayMs()).toBe(100);
    expect(backoff.nextDelayMs()).toBe(200);
    expect(backoff.nextDelayMs()).toBe(400);
    expect(backoff.nextDelayMs()).toBe(800);
    expect(backoff.attempts()).toBe(4);
  });

  it("never exceeds the maximum", () => {
    const backoff = new Backoff({ minMs: 100, maxMs: 400, random: () => 1 });
    const delays = [backoff.nextDelayMs(), backoff.nextDelayMs(), backoff.nextDelayMs(), backoff.nextDelayMs()];
    expect(delays).toEqual([100, 200, 400, 400]);
  });

  it("applies jitter below the ceiling", () => {
    const backoff = new Backoff({ minMs: 100, maxMs: 10_000, random: () => 0.5 });
    expect(backoff.nextDelayMs()).toBe(50);
    expect(backoff.nextDelayMs()).toBe(100);
  });

  it("never returns a zero delay", () => {
    const backoff = new Backoff({ minMs: 1, maxMs: 1, random: () => 0 });
    expect(backoff.nextDelayMs()).toBeGreaterThanOrEqual(1);
  });

  it("resets the attempt counter", () => {
    const backoff = new Backoff({ minMs: 100, maxMs: 10_000, random: () => 1 });
    backoff.nextDelayMs();
    backoff.nextDelayMs();
    expect(backoff.attempts()).toBe(2);
    backoff.reset();
    expect(backoff.attempts()).toBe(0);
    expect(backoff.nextDelayMs()).toBe(100);
  });
});
