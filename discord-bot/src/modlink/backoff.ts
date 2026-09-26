/**
 * Exponential backoff with full jitter.
 *
 * Full jitter (a uniform pick from `[0, min(max, base * 2^attempt)]`) avoids the
 * thundering-herd problem when several controllers reconnect at once, and is
 * deterministic enough to test by injecting the random source.
 */
export interface BackoffOptions {
  readonly minMs: number;
  readonly maxMs: number;
  readonly random?: () => number;
}

export class Backoff {
  private readonly minMs: number;
  private readonly maxMs: number;
  private readonly random: () => number;
  private attempt = 0;

  public constructor(options: BackoffOptions) {
    if (options.minMs <= 0) {
      throw new RangeError("minMs must be greater than zero");
    }
    if (options.maxMs < options.minMs) {
      throw new RangeError("maxMs must be greater than or equal to minMs");
    }
    this.minMs = options.minMs;
    this.maxMs = options.maxMs;
    this.random = options.random ?? Math.random;
  }

  /**
   * @returns the delay to wait before the next attempt and advances the counter
   */
  public nextDelayMs(): number {
    const exponential = Math.min(this.maxMs, this.minMs * 2 ** this.attempt);
    this.attempt += 1;
    const jittered = exponential * this.random();
    // Never return a sub-millisecond delay; a tight retry loop is worse than waiting.
    return Math.max(1, Math.round(jittered));
  }

  /**
   * @returns the number of attempts recorded since the last reset
   */
  public attempts(): number {
    return this.attempt;
  }

  /**
   * Resets the counter after a successful connection.
   */
  public reset(): void {
    this.attempt = 0;
  }
}
