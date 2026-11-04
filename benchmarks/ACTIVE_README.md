# Active GTCEu / LDLib benchmark (CI only)

The original benchmark exercises idle GTCEu machines. This **separate**
informational workflow exercises real recipe logic and the LDLib async
managed-field polling path under main-thread mutations.

## What is actually exercised

- Build **pristine pinned upstream LDLib 1.0.52.a** and the fork from source.
- Inject the *same* test-only Forge subscriber into both builds. The helper
  source is in `benchmarks/injected-src`; it is not packaged in released JARs.
- Use the same disposable Forge 1.20.1 / GTCEu 7.5.3 /
  Configuration 3.1.0 environment for both variants.
- Place 64, 256, and 1,024 actual GTCEu LV macerators. The helper uses
  GTCEu's real `MetaMachine.getMachine`, `WorkableTieredMachine.importItems`,
  `NotifiableEnergyContainer.setEnergyStored`, and
  `RecipeLogic.getProgress` APIs, rather than fabricated NBT or a
  mock scheduling loop. Each machine receives copper ingots and EU.
- **Fail closed** unless >=25% of expected machines demonstrate recipe
  progress and the total output count increases during the timed sample.
- Measure full Java-process CPU, LDLib async thread CPU, RSS,
  and the Forge server-tick START-to-END interval on the instrumented JVM.
  P50/P95 and raw per-tick samples are retained. Capture one JFR trace per
  variant with Java 17 Flight Recorder using profile settings.
- Upload log, raw trace, JFR, artifact SHA-256 and exact source SHA.

## Interpretation boundaries

**Not full MSPT:** the timing is wall time between Forge server tick events,
which excludes portions of the outer Minecraft server tick. Do not advertise
it as certified gameplay MSPT or equate it to the uninstrumented baseline.

**No connected modded client:** GUI interactions, real network traffic, actual
tracking-player packet delivery and latency are unverified in this workflow.
An end-to-end connected Forge client + server harness is a separate acceptance
gate. This workflow cannot validate or compare client sync correctness.

**Not the full TFG pack:** it is the specified pinned GTCEu environment,
not a copied player world, not live factories and not a production topology.
No production server or client is accessed or restarted.

**Known measurement caveats:** identical instrumentation affects both
comparands, but can still perturb observed timings. A GitHub-hosted runner
is shared, with noisy scheduling and GC/JIT variance. Run order alternates
between CI attempts and raw samples are published. Results remain
informational; no arbitrary threshold auto-blocks merges.

The benchmark validates actual output and progress because updating
managed fields without running a recipe would otherwise produce
convincing but meaningless CPU measurements. If GTCEu changes its API
or the pinned recipe stops working, the job must fail instead of quietly
reporting zero processing.

## Reproduction

On the PR/push branch, GitHub Actions runs
`.github/workflows/ldlib-active-benchmark.yml`.
Read the job summary and download `ldlib-active-gtceu-jfr-benchmark`.
The upstream source is pinned to
`1d4e4390ae58d16038845c651e122185c29f334b`.

Do **not** merge solely because the CI Python and Forge builds pass: the
completed active run must contain verified nonzero progress, output, and JFR.
The pristine upstream branch remains untouched.
