# Working in this repository

Conventions and context for anyone contributing here, human or AI assistant.

## Build and test

```bash
./gradlew build              # compile + all tests
./gradlew test               # 41 tests
./gradlew bootRun            # start on :8080
```

**JDK 25 is required** and pinned via the Gradle toolchain — the build does not
inherit whatever JDK is on `PATH`. The Gradle wrapper (9.7.1) is vendored; do not
rely on a system `gradle`, which may be too old to run JDK 25.

Integration tests run real Gradle builds in temporary workspaces and take
30–60s. That is expected — they are the tests that prove generated code actually
compiles.

## Architectural invariants

These are load-bearing. Changing any of them changes what the system guarantees,
so they need a deliberate decision rather than an incidental edit.

1. **The model never controls flow.** Graphs are built in `WorkflowPlanner` in
   Java. Do not add a code path where model output determines what task runs
   next, what a retry budget is, or whether an approval gate applies.

2. **`PatchApplier` is the only component that writes agent-authored content.**
   If you need to write generated files somewhere new, route it through there
   rather than adding a second write site.

3. **`BuildValidator` takes no command from callers of the agent layer.** The
   command is fixed at construction. Do not add a parameter that lets a caller —
   and therefore, transitively, a prompt — choose what executes.

4. **Agents do not mutate workflow state.** They return an `AgentOutcome`; the
   engine interprets it. This keeps every state transition in one auditable
   place.

5. **Completed graph nodes are never superseded or rewritten.** Their side
   effects already happened; the lineage must reflect what occurred.

6. **`PathPolicy` is an allow-list.** New capabilities are granted explicitly.
   Never widen it to "everything except X".

## Code style

- Records for immutable value types. Classes only when there is genuine mutable
  state (e.g. `UrlMapping`'s click counter, `TaskNode`'s execution state).
- Constructor injection. No field injection, no Lombok.
- Comments explain *why*, not *what*. If a comment restates the code, delete it.
  Where a non-obvious alternative was rejected, say what and why — that is the
  most valuable thing a comment can carry here.
- Test names are sentences describing the behaviour asserted:
  `a_truncated_patch_is_refused_rather_than_partially_applied`.
- Exceptions carry actionable messages. `"path escapes the workspace: X resolves
  outside Y"` beats `"invalid path"`.

## Testing expectations

Three layers, and a change should land in the right one:

- **Unit** — orchestration logic, fast and deterministic (`WorkflowGraphTest`).
- **Adversarial** — guardrails, written as attacks (`GuardrailTest`). New
  security controls need a test that tries to defeat them, plus one asserting the
  case that should *not* be blocked.
- **Integration** — real workspaces and real builds (`*IT`).

If a control cannot be tested, restructure it until it can. Credential stripping
was private and unreachable from a test; it was made package-private and static
for exactly this reason.

## Secrets

Never commit credentials. `.env` is gitignored; `ANTHROPIC_API_KEY` is read from
the environment only. The platform runs fully without any key — the deterministic
provider is the default, not a fallback for failure.

## Working with the fixtures

`GreenfieldFixture.IMPLEMENTATION` is the canonical URL shortener source. The
brownfield and ambiguous scenarios seed their workspaces from it, so editing it
affects all three scenarios. If you change it, run the full integration suite —
`GeneratedProjectBuildsIT` will catch code that no longer compiles.

Fixture matching order in `DeterministicModelProvider` is significant: specific
scenarios are matched before general ones, and matching is done against the
requirement alone, never the accumulated prompt (which contains repository text
that will otherwise misroute the match).
