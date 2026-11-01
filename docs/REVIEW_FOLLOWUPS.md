# PR #1 follow-up: RPC and removed UI slot safety

These changes are intentionally separate from the merged async-sync PR #1.

- RPC payloads now carry a managed-object index and reject negative/out-of-range indices.
- The RPC frame starts with a v2 sentinel so a legacy Fabric payload is rejected rather than interpreted with the wrong index; Forge's LDLib channel version is bumped from 0.0.1 to 0.0.2. **All participating clients and servers must update together.**
- Removed or unmapped UI slots ignore click, quick-move and pick-all requests without dereferencing a missing widget.
- Both-loader compilation and JUnit regression coverage are required before merging.

The async collection snapshot atomicity concern from PR #1 remains separate: `Collection.toArray()` is not a synchronization guarantee. It requires owner-thread field tracing and contention testing before a correctness claim. Live TFG is not upgraded by this PR.
