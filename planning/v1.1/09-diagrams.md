# 09 — Diagrams Plan (ARCP v1.1 Kotlin SDK)

This is the planning document for the Graphviz diagram set that ships
with the Kotlin SDK docs site. No `.dot` files yet; this file defines
the set, the style, the build wiring, the embed convention, and the
update rule.

Convention source: the sister SDK at
`/Users/nficano/code/arpc/typescript-sdk/diagrams/` already ships a
paired light/dark `.dot`+`.svg` workflow with a slate palette, two
named anchors per diagram (ENTRY blue, HUB amber), and asymmetric-
padding cluster labels. The Kotlin SDK mirrors that convention so the
two SDKs' docs read as one project. The Kotlin docs site renders SVG
natively; GitHub auto-switches via `<picture>` and
`prefers-color-scheme`. We do not invent a parallel style.

Output root: `docs/diagrams/` under the Kotlin SDK repo. Each diagram
is a pair of `.dot` files (light + dark) committed alongside the
rendered `.svg` pair.

## 1. Diagram set

Six diagrams, each carrying a spec § citation in its purpose line.

### 1.1 `subprojects.dot` (pair: light + dark)

- Files: `docs/diagrams/subprojects-light.dot`,
  `docs/diagrams/subprojects-dark.dot`, plus rendered `.svg` pair.
- Render: `dot -Tsvg subprojects-light.dot -o subprojects-light.svg`
  (same for dark; `-Gdpi=192` for the 2× pass — see §2).
- Audience: someone arriving at the docs site index. They want to
  know "what are the Gradle modules and how do they depend on each
  other".
- Spec §: none directly — this is SDK-internal architecture. Cite the
  audit's Phase 4 split decision (`02-current-audit.md` §H) and the
  layout in `04-architecture.md` as the source of truth instead of a
  spec section.
- Content: nodes for `:core`, `:client`, `:runtime`, `:sdk`,
  `:middleware:ktor-server`, `:middleware:spring-webflux`,
  `:middleware:otel`, `:samples`, `:tests`, `:cli`. Edges are Gradle
  `api`/`implementation` dependency direction (dependent → dependency).
  Solid edges for `api`, dashed for `implementation`. Group `:core`,
  `:client`, `:runtime`, `:sdk` in an outer cluster; group the three
  `:middleware:*` modules in a separate cluster.
- Anchors: `:sdk` is the HUB (everything routes through the umbrella);
  `:samples` is the ENTRY (the reader-facing consumer that pulls in
  the public surface).
- Embed target: `docs/index.md` (architecture overview at the top of
  the docs site).

### 1.2 `session-fsm.dot` (pair)

- Files: `docs/diagrams/session-fsm-light.dot` + dark + SVGs.
- Audience: contributor or integrator reading "how do sessions work".
- Spec §: §6.2 (hello/welcome), §6.3 (resume), §6.4 (heartbeats —
  v1.1), §6.5 (ack — v1.1), §6.6 (list_jobs — v1.1), §6.7 (close).
- States (ellipses, not boxes — this is an FSM): `idle`,
  `hello_sent`, `welcome_received`, `open`, `bye`, `dropped`,
  `closed`. `open` is the HUB anchor. `idle` is the ENTRY.
- Transitions:
  - `idle → hello_sent` on `client sends session.hello`.
  - `hello_sent → welcome_received` on `runtime sends session.welcome`.
  - `welcome_received → open` on `client receives welcome` (no
    additional wire; this is a local transition).
  - `open → open` self-loops for: `ping/pong` (§6.4), `ack` (§6.5,
    one-way client→runtime), `list_jobs/jobs` (§6.6, request/response),
    `job.submit` / `job.subscribe` (referenced; the job-lifecycle
    detail lives in §1.3 below). Label each self-loop with the message
    pair.
  - `open → bye` on either-side `session.bye` (§6.7).
  - `open → dropped` on transport loss or `HEARTBEAT_LOST` (§6.4).
  - `bye → closed`, `dropped → closed`.
- v1.1-new transitions get the "v1.1-new" treatment per the style
  rules below (dashed-pink feedback color is reserved for async
  return; use a node-level marker — see §2).
- Embed target: `docs/guides/sessions.md`.

### 1.3 `job-fsm.dot` (pair)

- Files: `docs/diagrams/job-fsm-light.dot` + dark + SVGs.
- Audience: someone implementing or debugging a job.
- Spec §: §7.3 (lifecycle), §9.5 (lease expiration → `LEASE_EXPIRED`),
  §9.6 (budget → `BUDGET_EXHAUSTED`), §7.6 (subscribe attaches without
  changing state).
- States (ellipses): `pending`, `running`, `success`, `error`,
  `cancelled`, `timed_out`. `running` is the HUB. `pending` is the
  ENTRY. `success`, `error`, `cancelled`, `timed_out` are the four
  terminal `final_status` values — group them in an inner cluster
  labelled "terminal `final_status` (§7.3)".
- Transitions (v1.0 baseline):
  - `pending → running` on first non-`pending` `status` event.
  - `running → success` on `job.result`.
  - `running → error` on `job.error`.
  - `running → cancelled` on `job.cancel` + 30s grace.
  - `running → timed_out` on `max_runtime_sec` lapse.
- v1.1 overlay:
  - Subscribe arrow: a separate `subscribed_reader` actor outside the
    FSM, with a dashed feedback edge into `running` labelled
    "job.subscribe attaches; no state change (§7.6)". The arrow does
    not enter or leave a state — it documents that subscribe is an
    observer relation, not a transition.
  - Lease arrow: an annotated edge `running → error` labelled
    "LEASE_EXPIRED (§9.5)" rendered alongside the v1.0 `job.error`
    edge, not replacing it. Same source/target nodes; both labels
    visible.
  - Budget arrow: a second annotated edge `running → error` labelled
    "BUDGET_EXHAUSTED (§9.6)". Two routes into `error` are correct
    and intended.
- Embed target: `docs/guides/jobs.md`.

### 1.4 `capability-negotiation.dot` (pair)

- Files: `docs/diagrams/capability-negotiation-light.dot` + dark + SVGs.
- Audience: anyone reading "how does v1.1 stay backward compatible
  with v1.0".
- Spec §: §6.2.
- Shape: a sequence-style diagram with `rankdir=LR`. Two lifelines:
  `Client` (ENTRY anchor, left) and `Runtime` (HUB anchor, right).
  Three sequential frames between them, drawn as `shape=record`
  message nodes laid out left-to-right:
  1. `Client → Runtime`: `session.hello { capabilities.features: [...] }`.
  2. `Runtime → Client`: `session.welcome { capabilities.features: [...] }`.
  3. A computed-set frame in the centre (a `shape=note` box) labelled
     "effective = client.features ∩ runtime.features".
- Sidebar: a single `shape=note` block listing the full v1.1 feature
  set verbatim (`heartbeat`, `ack`, `list_jobs`, `subscribe`,
  `lease_expires_at`, `cost.budget`, `progress`, `result_chunk`,
  `agent_versions`) — same nine entries as `01-spec-delta.md` §C.
  No emojis, no decoration; just the names.
- Embed target: `docs/guides/sessions.md` (same page as the session
  FSM; the two diagrams together cover §6.2.).

### 1.5 `heartbeat-ack-flow.dot` (pair)

- Files: `docs/diagrams/heartbeat-ack-flow-light.dot` + dark + SVGs.
- Audience: anyone debugging idle-session behaviour or slow consumers.
- Spec §: §6.4 (ping/pong + `heartbeat_interval_sec`), §6.5 (ack),
  §8.2 (status event body — the `back_pressure` flag lives here in
  the published spec).
- Shape: sequence over time, `rankdir=LR`. Two lifelines as above.
  Time flows left to right; events as labelled edges. Suggested
  sequence:
  1. `Runtime → Client`: `session.welcome { heartbeat_interval_sec: 30 }`.
  2. Idle gap (a dashed time-axis edge labelled "idle ≥ interval").
  3. `Client → Runtime`: `session.ping`.
  4. `Runtime → Client`: `session.pong`.
  5. (event stream gap — a few `job.event` arrows labelled
     `[event_seq: N, N+1, N+2]`)
  6. `Client → Runtime`: `session.ack { last_processed_seq: N+1 }`.
  7. `Runtime → Client`: `job.event { kind: status, body: { back_pressure: true } }`
     when lag exceeds the runtime's threshold.
- The `back_pressure` edge gets the feedback dashed-pink style — it
  is the runtime telling the client "you are falling behind", which
  is exactly the async-return semantics the pink edges already mean.
- Embed target: `docs/guides/heartbeats-and-ack.md`.

### 1.6 `result-chunk-and-progress.dot` (pair)

- Files: `docs/diagrams/result-chunk-and-progress-light.dot` + dark + SVGs.
- Audience: anyone implementing or consuming streamed results.
- Spec §: §8.2.1 (`progress` body), §8.4 (`result_chunk` + streamed
  `job.result`).
- Shape: sequence, `rankdir=LR`. Two lifelines: `Runtime` (left,
  emitting) and `Client` (right, receiving). Client is the ENTRY,
  Runtime is the HUB.
- Sequence:
  1. `Runtime → Client`: `job.event { kind: progress, body: { current: 1, total: 4 } }`.
  2. `Runtime → Client`: `job.event { kind: progress, body: { current: 2, total: 4 } }`.
     (Compress 2..N into a single edge labelled `× N` to keep the
     diagram readable; cite §8.2.1.)
  3. `Runtime → Client`: `job.event { kind: result_chunk, body: { result_id, chunk_seq: 0, more: true } }`.
  4. `Runtime → Client`: `job.event { kind: result_chunk, body: { result_id, chunk_seq: K, more: true } }` (× K).
  5. `Runtime → Client`: `job.event { kind: result_chunk, body: { result_id, chunk_seq: K+1, more: false } }`.
  6. `Runtime → Client`: `job.result { result_id, result_size, summary? }`.
- A note box anchored on the result_chunk sequence cites the spec
  invariant: "MUST emit chunks in order per `result_id`; MUST NOT mix
  inline + chunked; once chunked, terminal `job.result` MUST carry
  `result_id`" (§8.4). Same wording as the v1.1 spec, no embellishment.
- Embed target: `docs/guides/streaming-results.md`.

## 2. Shared style conventions

The canonical stylesheet is the typescript-sdk template at
`typescript-sdk/diagrams/diagram-template-light.dot` (and its dark
twin). The Kotlin SDK reuses that template verbatim. Concrete
properties that justify it:

- **Legibility on the docs site.** `fontname="Helvetica"` resolves
  to a system sans on every common docs-site host (GitHub Pages,
  Vercel, Cloudflare Pages, plain Nginx). No font fetch, no FOUT, no
  Graphviz fallback flicker. The text rasterises identically across
  the rendering boxes that build the docs site and the readers'
  browsers.
- **Dark-mode safety.** Each diagram ships two SVGs; GitHub and the
  docs site swap them via `<picture>` + `prefers-color-scheme`. Both
  variants render with `bgcolor="transparent"` so they sit on
  whatever page colour is active. We do not bake light-only assets
  into the docs.
- **Single SVG layer per file.** No external image refs, no
  embedded fonts, no `<foreignObject>`. Graphviz emits a single SVG
  with inline styles, which is the form GitHub and the docs site
  serve without rewrites.
- **Two anchors max.** One ENTRY (blue, `#3B82F6`), one HUB (amber,
  `#F59E0B`). If we highlight a third element, nothing is
  highlighted. This rule is enforced by the template's header
  comment; PR review checks it.

### Engine and direction

- `dot` engine for every diagram. No `neato`, `fdp`, or `circo`. The
  hierarchical layout is the right choice for FSMs (`subprojects`,
  `session-fsm`, `job-fsm`) and for the LR sequence diagrams.
- `rankdir=TB` for `subprojects` (dependency graph reads top-down,
  matching the typescript-sdk architecture diagram).
- `rankdir=LR` for the two FSMs and the three sequence diagrams.

### Colours

We use the typescript-sdk slate palette. The full palette is in
`typescript-sdk/diagrams/README.md` § "Palette"; do not redefine it
here. Highlights (light variant):

- node outline `#CBD5E1` / fill `white` / text `#1F2937`.
- ENTRY: `#3B82F6` fill, `#2563EB` border, white text.
- HUB: `#F59E0B` fill, `#D97706` border, white text.
- terminal/error nodes (job-fsm `error`, `timed_out`,
  session-fsm `dropped`): use the default node style — do not
  invent a red. The slate palette has no error red and adding one
  breaks the two-anchors rule.
- v1.1-new transitions: do not invent a fourth swatch. Instead, mark
  v1.1-new edges with the label suffix `(v1.1)` and v1.1-new states
  with the same suffix in the state label (e.g. `open (heartbeats v1.1)`
  — but applied only to the self-loop label, not the state name).
  Justification: every additional swatch costs a dark-mode pairing
  and a review item. Suffix labels are zero-cost and survive
  greyscale printing.

### Node shapes

- `box` (rounded, filled) for components in `subprojects`.
- `ellipse` for FSM states in `session-fsm` and `job-fsm`.
- `record` for sequence message frames in `capability-negotiation`,
  `heartbeat-ack-flow`, `result-chunk-and-progress`.
- `note` for the sidebar feature-list box in `capability-negotiation`
  and the spec-invariant box in `result-chunk-and-progress`.
- `cylinder` for any data store (the typescript-sdk template's
  default; we do not need it in the Kotlin set but the rule stands
  for future diagrams).

### Retina rendering

Each `.dot` renders twice:

```
dot -Tsvg foo-light.dot              -o foo-light.svg
dot -Tsvg -Gdpi=192 foo-light.dot    -o foo-light@2x.svg
```

The `@2x.svg` is referenced from the docs site via `srcset` for
high-DPI displays. The 1× SVG remains the default `src`. The Gradle
task in §3 emits both passes per `.dot`.

## 3. Build pipeline

### Gradle task

Phase 4 adds a `:docs` subproject (a non-published module that owns
the docs/diagrams pipeline). It exposes:

```
./gradlew :docs:diagrams
```

The task implementation:

- Scans `docs/diagrams/*.dot`.
- For each `.dot`, shells out to `dot` (resolved on `PATH`; the task
  fails fast with a clear error if `dot` is absent).
- Writes the sibling `.svg` and `.svg`-at-2× next to the source.
- Marks the `.dot` as `@InputFiles` and the `.svg` pair as
  `@OutputFiles` so Gradle's `UP-TO-DATE` check is exact. Only
  rebuilds when the `.dot` is newer than the `.svg`. No timestamp
  comparison in user code — Gradle's task graph already does this.
- Is wired into `check` only on CI (see below), not on every local
  `build`, so developers without Graphviz installed are not blocked.

### CI verification

`tests` workflow gains a `diagrams-verify` job:

- Installs `graphviz` (apt on Ubuntu runner, `brew` on macOS).
- Runs `./gradlew :docs:diagrams`.
- Runs `git diff --exit-code docs/diagrams/`. If the committed SVGs
  do not match the freshly rendered SVGs, the job fails with the
  diff inline. This catches the case where someone edits a `.dot`
  and forgets to rerender (or, the reverse, edits the SVG by hand).

CI compiles every `.dot` from scratch. It is not enough to render
locally — the rendered SVGs MUST be committed (the docs site build
does not run Graphviz). Treat `.dot` as source and `.svg` as a
checked-in build artifact for the docs.

### README diagrams are not part of this pipeline

The top-level `README.md` ships handwritten ASCII-art diagrams (per
the established Kotlin SDK README style). Those are NOT regenerated
by Graphviz and NOT verified by CI. Rationale: ASCII art renders in
plain-text README clients (e.g. `cat README.md` in a terminal, IDE
file previews, GitHub's plain-text view of the file source); SVG does
not. The Graphviz pipeline targets `docs/` only.

## 4. Cross-link from prose pages

Each diagram is referenced from at least one prose page in the docs
tree (Phase 8). Embed convention, two cases:

### 4.1 Simple light-only embed (default for docs site MDX)

Markdown:

```markdown
![Job FSM](../diagrams/job-fsm-light.svg)
```

This is what most docs pages use. The docs site renders SVG natively
and the light variant is the default. Used in `docs/guides/jobs.md`
referencing `job-fsm.dot`, in `docs/guides/sessions.md` referencing
`session-fsm.dot` and `capability-negotiation.dot`, in
`docs/guides/heartbeats-and-ack.md` referencing
`heartbeat-ack-flow.dot`, and in `docs/guides/streaming-results.md`
referencing `result-chunk-and-progress.dot`.

### 4.2 Paired light/dark embed (for GitHub-rendered pages)

For files served directly off GitHub (the repo's own README is the
canonical case, but here also `docs/index.md` if the docs theme does
not auto-detect prefers-color-scheme), use the `<picture>` element:

```markdown
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="../diagrams/subprojects-dark.svg">
  <img alt="Kotlin SDK subprojects" src="../diagrams/subprojects-light.svg">
</picture>
```

GitHub serves the matching SVG based on the viewer's theme. The
docs/index.md page uses this form for the architecture overview.

Diagram-to-page mapping is the canonical reference:

| Diagram                       | Embedded in                              |
| ----------------------------- | ---------------------------------------- |
| `subprojects`                 | `docs/index.md`                          |
| `session-fsm`                 | `docs/guides/sessions.md`                |
| `capability-negotiation`     | `docs/guides/sessions.md`                |
| `job-fsm`                     | `docs/guides/jobs.md`                    |
| `heartbeat-ack-flow`          | `docs/guides/heartbeats-and-ack.md`      |
| `result-chunk-and-progress`   | `docs/guides/streaming-results.md`       |

If Phase 8's docs tree renames any of those pages, this table is the
source of truth — update it in the same PR.

## 5. Update discipline

Rule: any wire-protocol change MUST update the affected diagrams in
the same PR.

Concretely, "wire-protocol change" means any of:

- new message type (e.g. a new `session.*` or `job.*` envelope name);
- new field on an existing message (envelope or payload);
- new error code (§12);
- new event `kind` (§8.2);
- new lease namespace (§9.2 reserved namespace table).

The PR template (see Phase 7) carries a checkbox: "diagrams in
`docs/diagrams/` updated, or explicitly documented as unchanged".

### CI soft-warning

Phase 7's `lint` workflow adds a `diagrams-touched` check:

```
changed_messages=$(git diff --name-only origin/main...HEAD -- 'core/src/main/kotlin/**/messages/' 'core/src/main/kotlin/**/events/' 'core/src/main/kotlin/**/error/')
changed_diagrams=$(git diff --name-only origin/main...HEAD -- 'docs/diagrams/*.dot')
if [[ -n "$changed_messages" && -z "$changed_diagrams" ]]; then
  echo "::warning::Message/event/error sources changed but no docs/diagrams/*.dot updated."
fi
```

Soft warning, not a hard fail. The author either updates a diagram
or annotates the PR description with "diagrams unchanged: the new
field is internal-only / does not appear on any diagram". The
reviewer sees both the warning and the annotation.

The exact paths above will be confirmed against Phase 4's package
layout; the principle is "git diff over message sources should also
touch a diagram". Phase 7 implements the check.

## 6. Out of scope

This plan does NOT cover:

- per-sample diagrams under `samples/` — Phase 6's examples plan
  decides whether any sample warrants its own diagram, separately;
- per-message JSON-shape diagrams — those belong in the spec, not
  the SDK docs;
- runtime-internal call graphs (e.g. coroutine scope hierarchies) —
  if Phase 4 needs them they go in `04-architecture.md` as inline
  ASCII, not in `docs/diagrams/`;
- animated SVG. Static only.

Six diagrams, one canonical stylesheet, one Gradle task, one CI
check, one update rule. Anything else is scope creep.
