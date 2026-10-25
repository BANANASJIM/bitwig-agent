# Architecture and ownership

Bitwig Agent is a **thin, bounded control bridge**, not a music-planning service, autonomous session manager or complete project model. The agent chooses a plan within the user's permission, discovers current targets, issues a request and interprets the observed result. Bitwig owns project state and the audio engine. The bridge owns transport, validation, bounded observations and domain-specific admission/completion checks.

This guide describes the public Python/Java implementation. [PROTOCOL.md](../PROTOCOL.md) remains the detailed contract for fields, limits, side effects and recovery boundaries; [VERIFICATION.md](../VERIFICATION.md) describes evidence rather than architecture promises.

## Interfaces and process boundaries

| Boundary | Interface | Owner and responsibility |
|---|---|---|
| Human to agent | Task, scope and explicit permissions | Human owns acceptance and permission; agent owns planning and evidence interpretation |
| MCP client to Python adapter | MCP over stdio | Client launches `bitwig-agent`; adapter exposes the shared catalog and returns structured/text envelopes |
| CLI to Python client | `bitwig-agent-rpc` | Same catalog/validation, with offline list/describe/help and host-facing calls |
| Python to Java extension | One newline-delimited JSON request/response per loopback TCP connection | Correlation, bounds, deadlines and error/outcome preservation; no replay |
| Network handler to controller host | Scheduled domain operation | Java validates and schedules; network threads do not operate the host model |
| Controller/private native model | Public Controller API or exact-version native adapters | Domain-specific target checks, observation and write admission; Bitwig remains the state authority |
| User to extension installation | `bitwig-agent-install` | Separate filesystem operation, matching release assets, checksum check and backup; not a DAW operation |

The extension binds `127.0.0.1:61172`; the Python client connects to IPv4 loopback. The endpoint is **unauthenticated**. Any local process able to connect can control the host. Context tokens and expected names protect against some stale targeting; they are not credentials. Do not expose or forward the port to a network. `BITWIG_AGENT_PORT` changes the Python connection port, not the Java listener.

## Python ownership

| Public source | Responsibility |
|---|---|
| [`schema.py`](https://github.com/BANANASJIM/bitwig-agent/blob/main/src/bitwig_agent/schema.py) | Strict typed request models and cross-field constraints |
| [`catalog.py`](https://github.com/BANANASJIM/bitwig-agent/blob/main/src/bitwig_agent/catalog.py) | One method catalog, descriptions, examples, read-only hints and validation used by both adapters |
| [`client.py`](https://github.com/BANANASJIM/bitwig-agent/blob/main/src/bitwig_agent/client.py) | Bounded strict JSON, one-shot loopback requests, unique correlation IDs, response validation and conservative outcomes |
| [`server.py`](https://github.com/BANANASJIM/bitwig-agent/blob/main/src/bitwig_agent/server.py) | MCP tool discovery and invocation over stdio; preserves the JSON envelope as structured content and text |
| [`cli.py`](https://github.com/BANANASJIM/bitwig-agent/blob/main/src/bitwig_agent/cli.py) | CLI discovery/schema/help and dispatch through the shared client |
| [`install.py`](https://github.com/BANANASJIM/bitwig-agent/blob/main/src/bitwig_agent/install.py) | Release-version extension download/copy, integrity check, backups and printed MCP configuration |

The catalog is not a planner. Tool examples are schema examples, not live target IDs. Tool annotations are hints, not authority or a proof that a call has no side effects. For example, `notes.read` selects a clip.

Python rejects invalid arguments before dispatch, including unknown fields and inappropriate value types. Java independently validates the transport and domain request; calling TCP directly does not remove host-side checks. The maintained Python dependency range is Python 3.11+, `mcp>=1.29.1,<2` and `pydantic>=2.11,<3`; the source lockfile selects exact development versions. Package dependency resolution and native host compatibility are different concerns.

## Java ownership

The main sources are under [`extension/src/main/java/dev/bitwigagent`](https://github.com/BANANASJIM/bitwig-agent/tree/main/extension/src/main/java/dev/bitwigagent).

- `BitwigAgentExtension` creates the bridge/listener, forwards controller flushes and closes services on exit.
- `RpcServer` owns transport framing, admission and bounded response delivery. It does not own project state, selection or domain business logic.
- `HostBridge` composes modules, keeps dispatch and validation registries aligned, validates requests and schedules each `HostOperation` through `HostServices`.
- `HostServices` owns shared host proxies and scheduling. `HostOperation` tracks deadlines and completion. Host API work crosses the scheduler boundary rather than running on the network thread.
- `DeviceSources` owns fixed root/layer/child addresses and continuity observations. `ParameterModule` owns continuously observed source-specific parameter data and the single shared active binding/request. Switching flat or nested targets invalidates the prior binding.
- Discovery, Actions, transport, mix, devices and Launcher/MIDI have separate domain modules and observations. Their result semantics differ: a dispatch-only Action is not equivalent to an acknowledged parameter change.
- `GridModule` separates target/RPC guards from bounded graph serialization and scalar, boolean-panel and topology writers. `GridTopologyQuarantine` shares native lifetime admission across topology editors.
- `ModulatorModule` and `ModulatorSnapshotReader` own bounded, nonloading primary-modulator reads. They share root target/read-context machinery, not Grid write authority.

`Bitwig613Runtime` is lazy and shared per `HostServices`. It checks the exact host version, loaded JAR CodeSource/hash/ClassLoader and mapped descriptors before private dispatch. Native target/read-context helpers corroborate ownership and lifecycle. Grid and modulator availability remains independently reported; successful read binding does not enable a writer.

The unnamed-package [`BitwigAgent613GridInsertion.java`](https://github.com/BANANASJIM/bitwig-agent/blob/main/extension/src/main/java/BitwigAgent613GridInsertion.java) is intentional: the native superclass is in that package and the loader expects the exact class name. It is a narrow insertion boundary, not another transport, planner or generic transaction framework.

## Request lifecycle and outcomes

1. The agent inspects the installed method schema and obtains fresh discovery/target data. The user, not a token or tool description, supplies authorization.
2. Python validates and creates a bounded JSON request with a unique `id` and `timeoutMs`. IDs correlate replies; they are not persistent idempotency keys.
3. The loopback transport admits the request and Java validates/routes it. Host work is scheduled with a monotonic deadline; deferred mutations check that deadline too.
4. The domain rechecks the relevant target, context and safety observations. If eligible, it dispatches the operation and observes the method-specific completion evidence.
5. The reply carries a result or an error with its outcome. MCP/CLI preserve that distinction. The agent must inspect per-field verification, mutation/dispatch flags and completion—not merely whether a JSON result exists.

`not_dispatched` means the client did not dispatch a request; `not_applied` is a host/domain rejection before the relevant operation was applied. `unknown` means the operation may have occurred. A transport timeout after dispatch, late response failure or stale target after dispatch must not become "failed, therefore retry." Some successful envelopes also report `completion: unknown`; an `observed` status does not cancel that uncertainty.

The deadline is not cancellation or rollback. The client adds a short response-delivery grace without extending/replaying the host request. It never retries automatically. One call can have several native effects, and undo-aware native dispatch does not establish one-call/one-Undo grouping.

On uncertainty, stop the writer, retain the response and ownership record, and reconcile through authorized inspection. Do not blindly retry through either the API or GUI. Unresolved Launcher mutations and native topology lifetimes have specific quarantine rules. Topology admission can survive extension classloader replacement in the same JVM; a required full host restart and inspection does not prove rollback. Follow the exact [protocol recovery boundaries](../PROTOCOL.md#shared-topology-lifecycle-admission), not an improvised reset.

## What a target means

Public track UUIDs, root device indices and exact names address the **currently observed bank**. Direct parameters add source-bound context tokens; Remote Controls have an observed-page context; one-hop layer targets add discovery/context tokens. Grid controls require discovered module/port/control IDs and fresh raw compare-and-set preconditions. These mechanisms reject important stale-target cases, but are not persistent physical identity, a project revision lock or atomic exclusion of all host/user activity.

Names can change, same-metadata replacement can be ambiguous, and module/modulator UUIDs can change on reopen. Reacquire all relevant bindings when targets or project lifetimes change. `null`/unreadable/unsupported data is unknown, never an empty collection, zero, false or permission by default.

## Bounded coverage and compatibility

- Tracks, root devices and Launcher windows are 16 each; sends are eight. Layers expose one hop with two layers and two children per layer. There is no general project paging or arbitrary nested traversal.
- A Grid page's `window.complete` is page coverage. Whole-result coverage may still be partial, and stored connections are not proof of the active DSP graph.
- MIDI reads use explicit channel/grid/window bounds and do not establish lossless arbitrary-clip export; selection/grid settling is not a general acknowledgement barrier.
- String panels are read-only. Modulator loading/mapping/writes, arranger clip editing, automation curves and dedicated master/cue tools are outside the structured surface.
- Native evidence is limited to **Linux / Bitwig Studio 6.1.3 / API 25**. Private Grid/modulator access additionally requires the exact loaded host-JAR hash. A 6.1.4 host package or a successful macOS/Windows Python install does not extend that evidence. Do not bypass the compatibility gate.

The agent can explicitly use separately authorized Computer Use for an unsupported operation, but there is no implicit GUI fallback. The scratch music recipe's Pad VOICES 8 step is one such explicit gap, not a hidden voice-count API. See [usage](../USAGE.md#make-grid-instruments-without-preset-files).

## Evidence is layered

Schema validation, package consumption, MCP startup, host connectivity, dispatch, model readback, GUI observation, audio rendering/listening, Undo/Redo and save/reopen are distinct observations. None automatically promotes the next layer to passed. The bridge intentionally reports bounded results rather than claiming a plan is musically correct or persisted. The human still owns acceptance.
