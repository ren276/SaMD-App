## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## Backend conventions

- Any test verifying that a write survived a failure path must assert the persisted DB row, never only the HTTP response. Reading back through the ORM or a raw SQL query is required; checking status code and response body alone is not sufficient, because a request-scoped transaction can roll back a write that a handler already returned success for. This rule is what caught the `_fail()` rollback bug in the Phase 5 ABDM adapter, and would have caught the three prior instances of the same trap (Phase 1 login-audit, Phase 3 kernel_call_log, latent in Phase 4).
