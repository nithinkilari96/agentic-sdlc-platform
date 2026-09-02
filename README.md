# Agentic SDLC Platform

**Given a requirement and a repository, this system understands the requirement,
plans the change, generates the implementation, tests and documentation, applies
the patch under policy control, runs the build, repairs what fails, and presents
a human with executable evidence to approve.**

The URL shortener is the workload, not the product. It exists to prove the
platform works on something real — a greenfield build, a brownfield enhancement,
and a requirement too vague to act on.

---

## The one idea

**The model reasons and proposes. Deterministic Java decides what actually
happens.**

| The model does | The platform does |
|---|---|
| Interpret intent, score its own ambiguity | Decide whether that score is good enough to proceed |
| Read the repository and infer conventions | Choose which files the model is allowed to see |
| Propose a design and its trade-offs | Own the task graph, gates and ordering |
| Generate code, tests, docs, repairs | Decide what may touch the filesystem, and verify it landed |
| Diagnose a build failure | Decide whether a retry is permitted at all |

No agent has shell access, filesystem access, or the ability to choose a command.
The single executable capability is one fixed build invocation the model cannot
parameterise.

### Why the Anthropic SDK directly, and not Spring AI

The model boundary is three methods (`ModelProvider`), sitting on the official
`com.anthropic:anthropic-java` SDK.

Spring AI's principal feature is making **model-invoked tool calling** easy — and
that is precisely the thing this architecture refuses to do. The model here does
not get tools; it returns proposals, and deterministic Java decides what executes.
Adopting the framework whose value proposition is "let the model call your
functions" would work directly against the property the system exists to
demonstrate, while adding a second abstraction over an API the seam already
isolates.

The boundary is also the security control. It is small enough to own outright,
and small enough for a reviewer to audit by reading one file.

---

## Architecture

```
                        Requirement
                             │
                             ▼
                     Requirement Agent ──── confidence < 0.60 ──┐
                             │                                  │
                        (clear enough)                           ▼
                             │                          AWAITING_CLARIFICATION
                             ▼                                  │
                    Repository Analysis                    human answers
                             │                                  │
                             ▼                            context revision++
                     Architecture Agent                    graph re-planned
                             │                                  │
                             ▼                                  │
                   Implementation Agent  ◄─────────────────────-┘
                             │
                 ┌───────────┴───────────┐     parallel: no dependency
                 ▼                       ▼      between these branches
            Test Agent            Documentation Agent
                 │                       │
                 └───────────┬───────────┘     join: patch-apply cannot
                             ▼                  start until both finish
                    Controlled Patch Apply
                      (snapshot taken first)
                             │
                             ▼
                      Build Validation
                        ╱          ╲
                    passed        failed
                       │             │
                       │      rollback to snapshot
                       │      context revision++
                       │      graph re-planned:
                       │      repair → apply → validate
                       │             │
                       │      (bounded: 2 rounds)
                       ▼             ▼
                    Approval Gate ◄──┘
                    (separate role)
                       │
              ┌────────┴────────┐
              ▼                 ▼
         approved          rejected
              │                 │
         COMPLETED      rollback + FAILED
```

Order is never written down. It is derived from declared dependencies, which is
why tests and documentation run concurrently and `patch-apply` is a real
synchronisation point.

**Full design rationale, including rejected alternatives:**
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

---

## Running it

Requires **JDK 25**. Nothing else — the Gradle wrapper is vendored.

```bash
./gradlew bootRun            # starts on :8080
./gradlew test               # 46 tests, including real builds in sandboxed workspaces
```

**No API key is needed.** The platform ships a deterministic model provider, so
every scenario runs end to end with zero credentials. To use the real model:

```bash
export ANTHROPIC_API_KEY=sk-...
./gradlew bootRun
```

Nothing else changes — the agents, graph, gates and guardrails are identical.
See [Deterministic vs live](#deterministic-vs-live-provider) for why this
distinction matters.

### The three scenarios

```bash
# 1. Greenfield — build the service from an empty repository
curl -sX POST localhost:8080/api/v1/workflows \
  -H 'Content-Type: application/json' -H 'X-Role: OPERATOR' \
  -d '{"requirement":"Build a URL shortener service with create, resolve and stats APIs, click analytics and reliability controls."}'

# 2. Brownfield — modify the existing service
curl -sX POST localhost:8080/api/v1/workflows \
  -H 'Content-Type: application/json' -H 'X-Role: OPERATOR' \
  -d '{"requirement":"Add per-client rate limiting to link creation so one caller cannot exhaust the service.","brownfield":true}'

# 3. Ambiguous — under-specified, must stop and ask
curl -sX POST localhost:8080/api/v1/workflows \
  -H 'Content-Type: application/json' -H 'X-Role: OPERATOR' \
  -d '{"requirement":"Improve analytics","brownfield":true,"expectAmbiguity":true}'

# 4. Repair loop — first build genuinely fails, system re-plans and recovers
curl -sX POST localhost:8080/api/v1/workflows \
  -H 'Content-Type: application/json' -H 'X-Role: OPERATOR' \
  -d '{"requirement":"Build a URL shortener with a click counter, using a seeded compile failure to exercise the repair path."}'
```

The fourth is worth watching on `/graph`: the plan gains `repair-1`,
`patch-apply-1`, `validate-1` and `release-readiness-1`, and the original
approval gate is superseded — so no human is ever shown the failed build.

Then inspect and act:

```bash
curl -s localhost:8080/api/v1/workflows/{id}          # state, revision, counters
curl -s localhost:8080/api/v1/workflows/{id}/graph    # task states, incl. superseded
curl -s localhost:8080/api/v1/workflows/{id}/lineage  # who decided what, and when

# Answer an ambiguous requirement — watch the graph reshape
curl -sX POST localhost:8080/api/v1/workflows/{id}/clarify \
  -H 'Content-Type: application/json' -H 'X-Role: OPERATOR' \
  -d '{"clarification":"Break clicks down by country using the X-Client-Country header, on a new per-link analytics endpoint."}'

# Approve — requires the APPROVER role, which OPERATOR cannot self-assign
curl -sX POST localhost:8080/api/v1/workflows/{id}/approve \
  -H 'Content-Type: application/json' -H 'X-Role: APPROVER' -H 'X-User: you@example.com' \
  -d '{"comment":"verified"}'
```

Generated code lands in `workspaces/{workflowId}/`.

---

## Guardrails

Every control assumes agent output is hostile — not from distrust of the model,
but because the prompts producing it contain text the platform did not write:
requirement descriptions, repository contents, build errors. Any of those can
carry an instruction.

| Control | Enforced in | Refuses |
|---|---|---|
| Path containment | `PathPolicy` | Traversal escapes, absolute and `~` paths, null bytes — checked *after* resolution, not by substring |
| Protected build surface | `PathPolicy` | Rewriting `gradlew` or repointing the wrapper's distribution URL |
| File-type allow-list | `PathPolicy` | Shell scripts and anything not on the list |
| Size and count limits | `PathPolicy` | Oversized files, oversized patches, duplicate paths in one patch |
| Single write choke point | `PatchApplier` | Every other route to the filesystem — there is one method to audit |
| Optimistic locking | `PatchApplier` | Overwriting a file that changed since the agent read it |
| Verified rollback | `WorkspaceSnapshot` | A restore that did not fully land — every file is re-hashed afterwards |
| Fixed executable capability | `BuildValidator` | Model-supplied commands, arguments or working directories — they are not parameters |
| Credential stripping | `BuildValidator` | The build inheriting anything credential-shaped |
| Entry gate | `PolicyGuard` | Runs exceeding the wall-clock ceiling or growing past the task limit |
| Separation of duties | `Role` | An operator approving their own run |
| Plan integrity | `WorkflowPlanner` | A model-authored graph — so injection cannot emit a plan with no approval step |
| Capability allow-list | `AgentType` | Agents the platform does not already know how to authorize and audit |

17 tests attack these directly (`GuardrailTest`), including the case that must
*not* be blocked: a `..` that normalises back inside the workspace is fine,
because the control is containment, not pattern matching.

---

## Reliability metrics

Exposed at `/actuator/prometheus`. Each of the five required measures maps to a
specific instrument:

| Measure | Instrument |
|---|---|
| Success rate | `workflow.outcome{state,succeeded}` — safe-stops counted separately from failures, since a system that correctly refused has not malfunctioned |
| Retry frequency | `task.retry{agent}` — tagged so it shows *which* stage is unreliable |
| Rollback frequency | `workspace.rollback` |
| MTTR | `workflow.mttr` — first validation failure to successful completion, recorded only for runs that actually recovered |
| End-to-end latency | `workflow.duration{state}` |

---

## Testing approach

46 tests, in three layers:

**Unit — orchestration logic** (`WorkflowGraphTest`, 14). Cycle rejection,
parallel readiness, join synchronisation, bounded retries, transitive blocking,
graph reshaping. Fast and deterministic.

**Adversarial — guardrails** (`GuardrailTest`, 17). Described above.

**Integration — the real thing** (`ScenarioIT`, `RepairLoopIT`,
`CrashRecoveryIT`, `GeneratedProjectBuildsIT`, 12). Real workspaces, real Gradle
builds, real subprocess execution. `GeneratedProjectBuildsIT` proves the
generated URL shortener actually compiles and passes its own 12 tests rather
than merely looking plausible; `RepairLoopIT` drives a genuinely failing
compile through rollback, re-planning and recovery.

These tests earned their keep — they found four real bugs during development:
snapshots reading binary files as UTF-8, scenario selection matching the
accumulated prompt rather than the requirement, path policy rooted at the shared
workspaces directory instead of per run, and a greenfield workspace reading as an
existing codebase because the installed build wrapper was in the digest.

### Deterministic vs live provider

The deterministic provider is **a bounded, repeatable test double — not evidence
of model reasoning.**

It exists so two different things can be verified independently. Orchestration
has a great deal worth testing exhaustively: dependency ordering, gate
evaluation, retry budgets, rollback verification, approval routing, crash
recovery. Validating that against a nondeterministic model would mean a failing
test leaves you unable to tell whether the orchestrator broke or the model simply
answered differently.

Real open-ended generation is `ClaudeModelProvider`, reached through the
identical `ModelProvider` interface. The orchestrator cannot tell them apart.

---

## Limitations

Stated plainly, because a documented gap is a known risk and an undocumented one
is a surprise in production.

- **The live provider path is wired and compiles against the official Anthropic
  SDK, but has not been executed against the real API.** No credential was
  available during the build. The deterministic path is fully exercised; the live
  path should be smoke-tested before being relied on.
- **Single-process orchestration.** State is durable and crash-recoverable, but
  there is no distributed workflow ownership. Running multiple replicas needs
  leasing — Postgres advisory locks, or a durable workflow engine like Temporal.
  The `@Version` column on the checkpoint entity is in place for this reason.
- **H2 file storage.** Correct for a prototype and genuinely durable across
  restarts. Production would use Postgres; the store is behind an interface.
- **Role checks are header-based.** A prototype boundary, not authentication.
  What it establishes is that the two capabilities are *distinct by design*; a
  real deployment substitutes the identity provider and keeps the distinction.
- **Whole-file replacement, not diffs.** Fuzzy patch application against
  model-authored hunks fails in ways that are hard to detect and easy to
  half-apply. Whole-file writes guarded by an optimistic lock either land
  completely or are refused. The cost is token usage on large files.
- **The generated shortener stores mappings in memory.** It is the workload, not
  the deliverable; its own limitations are documented in its README.

---

## Layout

```
orchestration/   graph, gates, context, engine, planner — the control plane
agent/           the ten agents, and the patch envelope they emit
provider/        the model seam: Anthropic adapter + deterministic fixtures
tools/           path policy, patch applier, snapshots, build validator
governance/      entry gate, roles
persistence/     durable checkpoints + append-only audit lineage
metrics/         the five reliability measures
api/             operator and approver REST surface
```
