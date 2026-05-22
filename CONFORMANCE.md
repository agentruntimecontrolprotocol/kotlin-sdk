# Conformance

Implemented versus deferred protocol surfaces are summarized in **README.md** (Status section). Source modules cite RFC sections in doc comments (e.g. `RFC §8`).

For cross-language conformance tracking, use the monorepo `spec/` tree and shared issue milestones.

## ARCP v1.1

- `session.list_jobs` / `session.jobs` (§6.6): implemented with principal-scoped in-memory inventory and cursor pagination.
- Agent versioning (§7.5): `name@version` parsing, advertised descriptors, and `AGENT_VERSION_NOT_AVAILABLE`.
- `result_chunk` (§8.4): wire payloads plus client-side chunk assembly.
- `cost.budget` (§9.6): budget parser, counters, subset checks, and `BUDGET_EXHAUSTED`.
- `model.use` and provisioned credentials (§9.7, §9.8): lease matching, credential wire types, provisioner interface, in-memory implementation, redaction, issue/revoke hooks, and rotation status events.
- Error taxonomy (§12): `BUDGET_EXHAUSTED`, `AGENT_VERSION_NOT_AVAILABLE`, and `LEASE_SUBSET_VIOLATION` are recognized wire codes.
