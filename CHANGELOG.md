## v1.0.52.a-nstut.2 (UpperMoon0 Forge/Fabric 1.20.1 fork)
* Preserve the RPC managed-object index on the wire; reject legacy frames and malformed or out-of-range indices so an RPC cannot silently target the wrong managed object.
* Harden dynamic GUI slot removal and index reuse: ignore stale/unmapped slots during clicks, quick moves, merge destinations and pick-all operations.
* Add targeted regression coverage for managed-object dispatch and removed/reused slot selection; validate both loader builds and the isolated Forge/GTCEu smoke run.
* Compatibility: Forge LDLib channel protocol is now `0.0.2`. Update client and server LDLib together. Live TFG rollout still requires paired client/server RPC and UI acceptance.

## v1.0.52.a
* Added javac `-parameters` for kjs