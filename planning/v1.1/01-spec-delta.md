# 01 — Spec Delta: ARCP v1.0 → v1.1

Source: [`../spec/docs/draft-arcp-02.1.md`](../../../spec/docs/draft-arcp-02.1.md).
Scope: every additive change against v1.0 a Kotlin client/runtime must
implement to be v1.1 conformant. Breakage column is from the perspective
of an existing v1.0 Kotlin client/runtime moving to v1.1; the spec
itself is wire-additive ("v1.1 is a backward-compatible additive
revision", §"Changes from v1.0").

## A. Additions table

| Spec § | Feature                                                                          | Wire impact                                                                                                          | Conformance | v1.0 → v1.1 break? |
| ------ | -------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- | ----------- | ------------------ |
| 6.2    | `session.hello.payload.capabilities.features` array; rich `agents` object in `session.welcome` | New optional field on hello; new shape on welcome; effective feature set = intersection                              | SHOULD send features; MUST honor intersection | Additive on wire. Kotlin types: `Hello.Capabilities` gains `features: List<String>`; `Welcome.Capabilities.agents` becomes a sealed/poly type to absorb both `List<String>` (v1.0) and `List<AgentDescriptor>` (v1.1). Internal API shape changes — non-breaking only if our v1.0 model already used a polymorphic deserializer for `agents`. |
| 6.4    | `session.ping` / `session.pong`; `heartbeat_interval_sec` in welcome             | Two new message types; new field in welcome payload                                                                  | MUST respond to `ping` with `pong`; SHOULD send `ping` when idle; MAY close on 2× silence | Additive. Need scheduled coroutine timer per session; runtime MUST NOT terminate jobs on `HEARTBEAT_LOST`. |
| 6.5    | `session.ack { last_processed_seq }`                                             | New message type, client → runtime                                                                                   | Client MAY send; runtime MAY use for early eviction; MUST NOT evict unacked unless capacity forces | Additive. Buffer/eviction policy in runtime gains an explicit "ack high-water-mark" knob. |
| 6.6    | `session.list_jobs` / `session.jobs`                                             | Request/response pair with filter (`status`, `agent`, `created_after`), `limit`, `cursor`; response carries `next_cursor` and per-job lease + `last_event_seq` | MUST scope by authorized principal; MUST NOT leak existence cross-principal | Additive. `Session` API gains `listJobs(filter, limit, cursor): JobsPage`. |
| 7.5    | Agent versioning: `agent ::= name | name "@" version`; `AGENT_VERSION_NOT_AVAILABLE` | `agent` field accepts versioned form on `job.submit`; `job.accepted.agent` echoes resolved version                   | Bare name MUST resolve to advertised `default` (or runtime's choice); pinned MUST be exact-match or error | Additive. Parse/validate against the BNF in §7.5; carry version through job model; runtime registry keyed by `(name, version)`. |
| 7.6    | `job.subscribe` / `job.subscribed` / `job.unsubscribe`                           | Three new message types; subscribed jobs interleave events in subscriber's `event_seq` space                         | MUST authorize; subscribers do NOT carry cancel authority; replay bounded by buffer window | Additive but architectural: the runtime's per-job event fan-out gains a many-readers-per-job model. Client `subscribe(jobId)` returns `Flow<Event>`. |
| 8.2.1  | `progress` event kind                                                            | New `kind` value with `{ current, total?, units?, message? }` body                                                   | Advisory; `current` MUST be non-negative; `current SHOULD ≤ total` when total present | Additive. Add `Event.Progress` variant to sealed message hierarchy. |
| 8.4    | `result_chunk` event kind + streamed `job.result`                                | New `kind` with `{ result_id, chunk_seq, data, encoding(utf8|base64), more }`; `job.result.payload` extends with `result_id`, `result_size`, `summary` | MUST emit chunks in order per `result_id`; MUST NOT mix inline + chunked; once chunked, terminal `job.result` MUST carry `result_id` | Additive. Add `Event.ResultChunk` variant; runtime API for streaming result via `Flow<ByteArray>` or chunk emitter. |
| 9.5    | `lease_constraints.expires_at` on `job.submit` and echoed on `job.accepted`      | New optional payload field (ISO 8601 UTC, future); two-state authority (`active`, `expired`)                         | MUST be UTC `Z` suffix and future at submit (else `INVALID_REQUEST`); MUST check on every authority-bearing op; MUST emit `LEASE_EXPIRED` when violated; NO renewal | Additive. Runtime lease checker gains a deadline; runtime SHOULD use a monotonic clock with bounded grace. |
| 9.6    | `cost.budget` capability: amount-string patterns `currency:decimal`               | New lease namespace; per-currency counter; `metric` events with `name = cost.*` decrement; runtime MAY emit `cost.budget.remaining` proactively | MUST decrement per matching `metric`; MUST reject negative `value`; MUST fail with `BUDGET_EXHAUSTED` when counter ≤ 0; SHOULD surface as `tool_result` error (not job-fatal) | Additive. Lease grammar gains a new namespace; lease subsetting (§9.4) gains a "remaining ≥ delegated" check. |
| 11     | Span attributes `arcp.lease.expires_at`, `arcp.budget.remaining`                 | None on wire; tracing-only                                                                                            | SHOULD set on spans                                                                                                       | Additive on the OTel adapter (see Phase 5). |
| 12     | Three new error codes (see §B)                                                   | Carried in existing error envelope                                                                                    | All three MUST be `retryable: false`                                                                                       | Additive. Three new subclasses of `ArcpException`. |

## B. New error codes (§12)

| Code                          | Trigger                                                              | Surfacing                                                | `retryable` |
| ----------------------------- | -------------------------------------------------------------------- | -------------------------------------------------------- | ----------- |
| `AGENT_VERSION_NOT_AVAILABLE` | `name@version` requested but version unregistered (§7.5)             | `session.error` on `job.submit` (rejection, not started) | `false`     |
| `LEASE_EXPIRED`               | Authority-bearing op attempted at/after `expires_at` (§9.5)          | First on the offending `tool_result.body.error`; then `job.error` with `final_status: "error"` | `false`     |
| `BUDGET_EXHAUSTED`            | `cost.budget` counter ≤ 0 when authorizing an op (§9.6)              | SHOULD prefer `tool_result.body.error` (lets agent unwind); MAY surface as fatal `job.error`   | `false`     |

All three join the v1.0 set of 12 for a total of 15 canonical codes.
The `retryable: false` constraint is normative — "naive retry will fail
identically" (§12). Treat them as terminal in client retry policy.

## C. Capability negotiation (§6.2) — the unifying mechanism

Every v1.1-only feature is gated by a named flag. The full set:

```
heartbeat, ack, list_jobs, subscribe,
lease_expires_at, cost.budget,
progress, result_chunk, agent_versions
```

Rules a Kotlin implementation cannot fudge:

1. **Effective set = `hello.features ∩ welcome.features`.** Code paths
   for any v1.1 feature MUST check the negotiated set before sending
   or accepting that message. Implementation idiom: store the
   intersection in an `EnumSet<Feature>` on the `Session`, and gate
   each feature call behind `session.features.contains(...)`.
2. **Silent on the wire when absent.** A peer that did not advertise
   a feature MUST NOT emit it. A peer that receives one outside the
   intersection MAY treat it as `INVALID_REQUEST`. (Spec §5.1 unknown-
   field rule covers v1.0 clients seeing v1.1 messages; that's a
   forward-compat fallback, not a license to skip negotiation.)
3. **`agents` shape is polymorphic.** A v1.1 client connecting to a
   v1.0 runtime may see flat `["name", ...]`; the client MUST
   interpret that as "no version info; submit bare names only" — not
   as a parse error. The Kotlin model needs a custom serializer or
   `JsonContentPolymorphicSerializer` that branches on element shape
   (string vs object).

## D. Backward compatibility matrix

| Direction                       | What works without negotiation                                                                 | What requires negotiation |
| ------------------------------- | ---------------------------------------------------------------------------------------------- | -------------------------- |
| v1.1 client → v1.0 runtime      | All v1.0 features. v1.1 client refrains from sending `ping`/`ack`/`list_jobs`/`subscribe`/etc. | Every v1.1 feature.        |
| v1.0 client → v1.1 runtime      | All v1.0 features. v1.1 runtime ignores absent `features`, still accepts v1.0 messages.        | Every v1.1 feature (client never asks). |
| v1.1 ↔ v1.1                     | None (everything except v1.0 baseline is negotiated)                                           | All flagged features.      |

Practical consequence for the SDK: there is no global "v1.1 mode"
toggle. There is a per-session capability set, and every code path
that touches a v1.1 feature consults it.

## E. Non-changes (explicitly unchanged from v1.0)

These are called out so Phase 2 does not chase them as gaps:

- §4 Transport, §5 Wire Format envelope, §6.1 Authentication, §6.3
  Resume (token rotation, `RESUME_WINDOW_EXPIRED`), §6.7 Close.
- §7.2 Idempotency, §7.3 Lifecycle terminal states (`success`,
  `error`, `cancelled`, `timed_out` — v1.1 adds no new states; the
  two new errors land in `error`), §7.4 Cancellation.
- §8.1 Event Envelope, §8.3 Ordering and sequence numbers.
- §9.1 Capability Model, §9.2 Lease Grammar (only the reserved-
  namespace table is appended), §9.3 Enforcement.
- §10 Delegation (the two constraints in §9.4 cover the v1.1 add).
- §11 Trace Propagation (only the two new recommended attributes).

## F. Explicit non-goals for v1.1 (spec "Not in v1.1")

Do not plan SDK surface for: job pause/unpause; priority/scheduling
hints; federation across runtimes; streaming-token LLM surface.
Phase 6 (examples) MUST NOT introduce these as samples.
