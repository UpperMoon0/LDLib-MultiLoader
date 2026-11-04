# LDLib upstream vs downstream CPU benchmark

This is an **informational** performance experiment for the Minecraft 1.20.1
integration branch. It runs both compiled Forge JARs on the **same GitHub-hosted
runner**, one after the other:

- **Upstream**: pristine tracking commit
  1d4e4390ae58d16038845c651e122185c29f334b (LDLib 1.0.52.a).
- **Fork**: the workflow checkout, ordinarily 1.20.1-nstut or a PR against it.

Each version receives a fresh, identically configured Forge 47.4.13,
GTCEu 7.5.3, Configuration 3.1.0 disposable world with fixed seed. CI
builds both JARs from source; it does not rely on an unverified external
fork binary or on the live TFG installation.

## Workloads and metrics

Place 64, 256, then 1,024 **idle** GTCEu LV macerators in forceloaded
chunks. Per stage, warm up for 20 seconds and measure 40 one-second samples.

The Python harness uses Linux process and thread CPU-time counters:

- LDLib async thread mean and P95 CPU **% of one logical core**.
- Entire Java server process mean and P95 CPU **% of one logical core**.
- Last observed resident memory (RSS), from /proc.
- Counts of targeted async errors and concurrent modification exceptions
  in server logs.

The generated Markdown table shows CPU use and percentage *reduction* for
the fork relative to the pristine source baseline. Negative reductions
mean the fork consumed **more** CPU. A tiny baseline is reported as N/A
rather than a misleading large percentage.

Raw JSON contains all per-second observations. GitHub Actions uploads
results, console logs, server logs, JAR SHA-256 identities, and source SHAs
as a downloadable artifact with 14-day retention.

**Not measured:** true gameplay MSPT percentiles, active recipe/multiblock
throughput, client packet rate or latency, full TFG modpack performance,
and real-world allocations/GC. The console runs forge tps for audit
logs only; it is *not* parsed as validated per-tick MSPT. Do not call
the results a general "LDLib is X% faster" claim.

## CI triggers

- Pushes to the downstream integration branch affecting benchmark code or
  async/syncdata sources.
- Pull requests targeting the integration branch affecting the same paths.
- Pushes to bench/** touching benchmark paths (to exercise a new harness).

The default repository branch 1.20.1 is intentionally **pristine upstream**.
GitHub only exposes workflow_dispatch and scheduled workflows if their
workflow file also exists on the default branch. We deliberately do not
modify that pristine branch: the supported automated trigger is a push
or PR, not a manual Run Workflow button. For an on-demand run, push a
benchmark-only PR/branch update instead.

This job is **not required for merge**, and has no statistical regression
threshold. It requires basic fixture integrity and a live LDLib async
thread, failing closed instead of reporting fabricated zero CPU.

## Interpretation

- Compare the same profile/runner, not separate dates or hardware.
- GitHub-hosted runners introduce scheduling noise and CPU variability.
- The 64 -> 256 -> 1,024 stages accumulate within one server run and
  may see different JIT/GC behavior. Run order alternates between CI
  run numbers to reduce but not eliminate order bias.
- Repeat the benchmark and check spread/variance before reporting an
  improvement. For a credible *real TFG gameplay* claim, pair this with
  an identical saved TFG world, active GTCEu machinery, repeatable
  inputs, client sync validation and multiple JFR/MSPT recordings.

This workflow never connects to or restarts the production TFG server.
