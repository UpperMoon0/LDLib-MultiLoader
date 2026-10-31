# Maintaining the LDLib compatibility fork

This fork preserves a clean upstream line and a small, reviewable patch branch.
Do not replace whole upstream classes or change package names simply to add
compatibility work.

## Branch policy

- `1.20.1`: pristine upstream tracking branch, never put local fixes here.
- `1.20.1-nstut`: integration branch for verified downstream patches.
- `fix/*`: focused pull requests against the integration branch.

To update: fetch `https://github.com/Low-Drag-MC/LDLib-MultiLoader.git`
as `upstream`, fast-forward the pristine `1.20.1` branch to
`upstream/1.20.1`, and merge that into `1.20.1-nstut`. Resolve any
conflicts in the integration branch; preserve upstream's history. For a new
Minecraft version, start a new pristine upstream branch and cherry-pick the
isolated fixes only if they are still needed. Do not merge the 1.20.1 code
into an unrelated loader/version line.

## Downstream synchronization fixes

- One queued managed-payload dispatch per block entity, with failure-safe
  release of the queue slot.
- Position-staggered block-entity field polling. Default is one scan per
  100 ms per entity. Use `-Dldlib.asyncSyncPollInterval=1` for upstream's
  50 ms scan; values from 1 to 20 are supported.
- Collection snapshots during asynchronous change detection, avoiding
  fail-fast iteration when game logic modifies inventories or machine state.

The reduced polling frequency applies only to asynchronous block entities.
Other `IAsyncLogic` implementations retain the original 50 ms schedule.
Do not confuse change-detection polling with GTCEu's server-tick execution;
machine logic still ticks at its normal cadence.

## Verification

Run `./gradlew :ldlib-common:test :ldlib-forge:compileJava :ldlib-fabric:compileJava` on Java 17. Regression
tests cover pending-task coalescing, concurrent submissions, task failure,
executor rejection, polling cadence, collection changes and retry-on-mutation.
Before shipping a TFG server update, run an isolated Forge test server with
GTCEu and a client: verify initial machine state, changing energy/fluids,
machine and cover updates, chunk unload/reload, disconnect/reconnect, and
correct client sync under server lag. Compare repeatable JFR profiles for
CPU, allocation, garbage collection and tick time.

Do not copy a new fork jar directly to the live world without this verification.
