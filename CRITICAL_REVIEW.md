# Critical Production Review: LowLatencyGraph

**Reviewer:** Claude Code
**Date:** 2026-02-23
**Verdict: NOT PRODUCTION READY — multiple financial-correctness bugs and architectural gaps must be resolved first.**

---

## CRITICAL — Must Fix Before Any Live Trading

### 1. Beta Formula Is Wrong (`Beta.java:64-70`)

**The denominator uses `Var(Asset)` instead of `Var(Benchmark)`.**

```java
// Current (wrong):
double covXY = (sumXY / count) - (meanX * meanY);
double varY  = (sumY2 / count) - (meanY * meanY);  // Y = Asset
return covXY / varY;                                 // Cov(B,A)/Var(A) ≠ Beta
```

Standard financial beta = `Cov(Asset, Benchmark) / Var(Benchmark)`.
The code computes `Cov(Benchmark, Asset) / Var(Asset)` — wrong denominator.
Fix: divide by `varX` (benchmark variance), not `varY`.
The class comment even contradicts itself: it states "X is Benchmark, Y is Asset" but the formula as written is the regression of X on Y, not of Y on X.

---

### 2. `VectorSourceNode.update()` Stores Caller's Array by Reference (`VectorSourceNode.java:68`)

```java
// Current (dangerous):
this.currentValues = values;   // caller retains the reference
```

If the caller reuses or modifies `values` after calling `update()`, the graph's internal data is silently corrupted mid-stabilization. This is a data integrity bug that will cause wrong prices under any real feed adapter that reuses buffers.

Fix: `System.arraycopy(values, 0, this.currentValues, 0, values.length)`.

---

### 3. Circuit Breaker Is Documented But Not Implemented (`StabilizationEngine.java`)

The Javadoc on the class and on `stabilize()` both state:

> "the engine captures it, notifies listeners, and eventually marks the graph as 'unhealthy'. This prevents a broken graph from continuing to process financial data."

There is **no `isHealthy` flag, no state machine, no throw-on-next-call**. A node that repeatedly throws will continue to return NaN and propagate it, but the engine never stops. Downstream risk systems will receive NaN prices with no programmatic way to detect the graph is broken.
A minimal fix: add `private volatile boolean healthy = true;` and throw `IllegalStateException` from `stabilize()` if `!healthy`, set `healthy = false` on node error.

---

### 4. Zero Unit Tests for Any Financial Function

There are exactly **3 test files** covering the engine and topology. There are **zero tests** for:

- `Ewma`, `Sma`, `Rsi`, `Macd`, `HistVol`, `ZScore`
- `Beta`, `Correlation`
- `RollingMax`, `RollingMin`
- `LogReturn`, `Diff`, `TriangularArbSpread`, `WeightedAverage`

These are the functions that produce prices and signals. Without tests, regressions in financial math will go undetected. This is not acceptable for production.

---

## HIGH — Serious Financial or Operational Defects

### 5. `HistVol` Uses Population Variance, Not Sample Variance (`HistVol.java:57-58`)

```java
double mean     = sum / count;
double variance = (sumSq / count) - (mean * mean);  // biased (population)
```

Financial convention for historical volatility universally uses **Bessel's correction** (sample variance, denominator `n-1`). For a window of 10 the bias is ~10%, for a window of 5 it is ~25%. This systematically underestimates volatility.
Same bug exists in `ZScore.java:56`.

---

### 6. `HistVol` Has No Annualization Factor (`HistVol.java:64-66`)

```java
// The original code did not have an annualizationFactor.
// Assuming it should return the standard deviation directly if not provided.
return Math.sqrt(variance);
```

The comment acknowledges this was never implemented. The output is raw daily/tick standard deviation of log returns, not annualized volatility. Every consumer of this node expecting annualized vol (which is the market standard) will receive the wrong number by a factor of `sqrt(252)` ≈ 15.87 for daily data.

---

### 7. `RSI` Warmup Is Mathematically Incorrect (`Rsi.java:38`)

```java
// avgGain and avgLoss start at 0.0 (Java default)
avgGain = alpha * gain + (1.0 - alpha) * avgGain;  // seeded from 0
avgLoss = alpha * loss + (1.0 - alpha) * avgLoss;
```

Wilder's original RSI seeds `avgGain` and `avgLoss` with a **simple average of the first N gains/losses**. Starting from 0 produces a biased RSI for the first ~3×period ticks. In a fast market feed, your RSI signal will be wrong until it "warms up", potentially triggering false entries/exits.

---

### 8. `MACD` Does Not Validate `fastPeriod < slowPeriod` (`Macd.java:17`)

```java
public Macd(int fastPeriod, int slowPeriod) {
    this.fast = new Ewma(2.0 / (fastPeriod + 1));
    this.slow = new Ewma(2.0 / (slowPeriod + 1));
}
```

Passing `Macd(26, 12)` (fast > slow, a common copy-paste error) produces the **negative of the intended MACD** with no error thrown. The standard convention is `fastPeriod < slowPeriod`. An `IllegalArgumentException` should guard this.

---

### 9. Floating-Point Drift in Running Sums (`Sma.java`, `HistVol.java`, `ZScore.java`, `Beta.java`, `Correlation.java`)

All rolling-window functions use a running sum that accumulates floating-point error with every tick. Over millions of ticks, the sum drifts from the true value. For a price like 10000.50, each add/remove cycle introduces ~1 ULP of error. After 1M ticks this can be material. Periodic full recalculation or Kahan summation is needed.

Additionally, the formula `(sumSq / N) - mean²` is subject to **catastrophic cancellation** when variance is small relative to magnitude (e.g., a price series clustered tightly around 100.0). Welford's online algorithm avoids this entirely.

---

### 10. `RollingMax` Contains an Ineffective Empty Loop (`RollingMax.java:36-51`)

```java
for (int i = 0; i < size; i++) {
    // ... only comments, no code ...
}
// Re-implementing existing logic but safely inside try-catch
for (int i = 0; i < count; i++) { ... }
```

The first loop executes `size` iterations doing nothing. On a hot path called millions of times per second, this is measurable overhead and indicates the implementation was left in an unfinished state. The confusing comment structure suggests the author was unsure of their own algorithm.

---

## MEDIUM — Correctness and Operational Concerns

### 11. `Correlation` and `Beta` Return `0.0` for Undefined Cases Instead of `NaN`

```java
if (denX <= 1e-9 || denY <= 1e-9)
    return 0.0;  // Correlation.java:81
if (varY <= 1e-9)
    return 0.0;  // Beta.java:67
```

Zero correlation and undefined correlation have completely different meanings to a trading strategy. When a series has no variance (e.g., a stuck feed), the statistic is **undefined**, not zero. Returning `NaN` would propagate correctly through the graph and be caught by monitors. Returning `0.0` silently feeds incorrect values to downstream nodes.

---

### 12. `Correlation` Threshold `1e-9` Is Absolute, Not Relative

The guard `denX <= 1e-9` fails for prices in the 1e-8 range (common for crypto micro-prices or FX pip-level movements). Should use a relative threshold.

---

### 13. `Correlation` Output Can Exceed `[-1.0, 1.0]` Due to Floating-Point Errors

No clamping is applied. With catastrophic cancellation, `num / sqrt(denX * denY)` can produce values like `1.000000000001` or `-1.0000000000002`. Downstream nodes that branch on `corr > 1.0` will have undefined behavior.

---

### 14. Dashboard Has No Authentication or Rate Limiting (`GraphDashboardServer.java`)

The WebSocket endpoint `/ws/graph` is open to any TCP connection. In a trading environment, graph state (current prices, spreads, volatility signals) is sensitive. A misconfigured firewall would expose live position data to anyone on the network. Additionally, there is no throttle on `broadcast()`: at 1M stabilizations/second, 1M WebSocket frames would be sent per second per client.

---

### 15. `ErrorRateLimiter` Per-Instance Rate Limits Mask Cascading Failures

```java
private final ErrorRateLimiter limiter = new ErrorRateLimiter(log, 1000);
```

Each function instance has its own 1-second rate limiter. In a graph with 100 EWMA nodes all failing simultaneously, each fires independently and logs up to 100 messages/second — but individually each only logs once/second, so the aggregated alert volume is hard to reason about. Worse, if all 100 nodes start throwing, only 1 message per node per second is logged while prices are being corrupted.

---

### 16. `WeightedAverage` Requires Interleaved `[val, weight, val, weight...]` Format With No Compile-Time Safety

The ordering of dependencies in the JSON definition determines which inputs are values and which are weights. A wrong ordering (`[weight, val, ...]`) produces silently incorrect results. There is no validation at graph construction time.

---

### 17. `HarmonicMean` Does Not Handle Negative Inputs

```java
if (val == 0.0) return Double.NaN;
sumInverse += 1.0 / val;
```

Negative inputs are accepted and produce a mathematically valid but financially nonsensical result (harmonic mean of -5.0 and 5.0 is undefined/infinite). If used for P/E ratios (as suggested in the Javadoc), negative earnings would corrupt the result.

---

## LOW — Code Quality and Maintainability

### 18. `Correlation.java` Has a Deliberately Empty `else` Block with Misleading Comment

```java
} else {
    // Logic error in previous snippet: count++ should happen if NOT full.
    // But let's be cleaner.
}
```

This reads as code that is still being debugged. The comment refers to "previous snippet" which doesn't exist in this file. Production code should not contain self-referential development notes.

---

### 19. `RollingMin` and `RollingMax` Have Inconsistent Internal Algorithms

`RollingMax` scans `window[0..count-1]` with a direct index.
`RollingMin` uses computed circular buffer indices: `(head - count + i + size) % size`.
Both produce correct results, but the algorithms are fundamentally different for no stated reason. This is a maintenance hazard.

---

### 20. `Logger` in `AbstractFn*` Classes Is an Instance Field, Not `static`

```java
private final Logger log = LogManager.getLogger(this.getClass());
```

Log4j caches loggers by class, so this is functionally equivalent to a static field, but the declaration wastes memory (one field reference per node instance). Minor, but worth noting.

---

### 21. Duplicate Import in `JsonGraphCompiler.java:3,8`

```java
import com.trading.drg.api.*;
// ... other imports ...
import com.trading.drg.api.*;
```

Line 8 is a duplicate of line 3. Harmless but indicates insufficient code review.

---

### 22. `CoreGraph.update(int, double)` Silently Does Nothing for Non-Source Nodes

```java
public void update(int nodeId, double value) {
    if (nodeId < 0 || nodeId >= sourceNodes.length) return;
    Node<?> node = sourceNodes[nodeId];
    if (node instanceof ScalarSourceNode sn) { ... }
}
```

If a caller passes the ID of a calculation node (not a source), the update silently does nothing. In production, this should throw to alert the programmer of misuse rather than silently dropping a market data update.

---

## Architecture Assessment

### What Is Well-Designed

- **Stabilization Engine**: The bitset-based dirty propagation, topological ordering, and CSR layout are sound and efficient. The algorithm is correct.
- **Cycle detection**: Kahn's algorithm implementation correctly detects all cycles including self-loops.
- **Fail-safe NaN propagation**: Error containment via NaN propagation is the right default.
- **`ScalarCutoffs`**: The `EXACT` cutoff using `doubleToRawLongBits` correctly handles all IEEE 754 cases.
- **`GraphBuilder` DSL**: Clean, type-safe, and hard to misuse at the structural level.
- **`CompositeStabilizationListener`**: Good composition pattern; adding observers doesn't overwrite existing ones.

### What Is Missing for Production

1. **Monitoring / alerting API** for circuit breaker state
2. **Warmup detection** — know when stateful functions have enough data to be trusted
3. **Snapshot/restore** — ability to serialize and reload graph state
4. **Benchmark harness** — the README quotes benchmark numbers but there is no reproducible benchmark in the codebase
5. **Tick replay / backtesting mode** — verify financial functions against known-good reference implementations
6. **Dependency version pinning** — `pom.xml` uses `1.0-SNAPSHOT` as the project version with no BOM; dependency upgrades could break behavior silently

---

## Priority Fix Order

| # | File | Issue | Severity |
|---|------|--------|----------|
| 1 | `Beta.java:65-70` | Wrong denominator in beta formula | CRITICAL |
| 2 | `VectorSourceNode.java:68` | Array aliasing — copy, don't assign | CRITICAL |
| 3 | `StabilizationEngine.java` | Implement circuit breaker | CRITICAL |
| 4 | All `fn/finance/` | Write tests for every financial function | CRITICAL |
| 5 | `HistVol.java:57-58` | Use sample variance `/(n-1)` | HIGH |
| 6 | `HistVol.java:66` | Add configurable annualization factor | HIGH |
| 7 | `Rsi.java` | Seed avgGain/avgLoss from first N values | HIGH |
| 8 | `Macd.java:15` | Validate `fastPeriod < slowPeriod` | HIGH |
| 9 | `RollingMax.java:36-51` | Remove dead empty loop | MEDIUM |
| 10 | `Correlation.java:81`, `Beta.java:67` | Return NaN not 0.0 for undefined | MEDIUM |
| 11 | `GraphDashboardServer.java` | Add auth and broadcast rate limiting | MEDIUM |
