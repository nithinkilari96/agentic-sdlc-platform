# Agentic SDLC Platform

**Given a requirement and a repository, this system understands the requirement,
plans the change, generates the implementation, tests and documentation, applies
the patch under policy control, runs the build, repairs what fails, and presents
a human with executable evidence to approve.**

The URL shortener is the workload, not the product. It exists to prove the
platform works on something real — a greenfield build, a brownfield enhancement,
a requirement too vague to act on, and a build that fails and has to be
repaired.

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
./gradlew test               # includes real builds in sandboxed workspaces
```

**No API key is needed.** The platform ships a deterministic model provider, so
every scenario runs end to end with zero credentials. To use the real model:

```bash
export ANTHROPIC_API_KEY=sk-...
./gradlew bootRun
```

Nothing else changes — the agents, graph, gates and guardrails are identical.
See [Deterministic vs live](#deterministic-vs-live-provider) for why the
separation exists.

#### Using the live provider

A single greenfield run exercises the full agent chain against the real model —
requirement analysis, repository reasoning, architecture, code generation, tests
and documentation:

```bash
export ANTHROPIC_API_KEY=sk-...     # from console.anthropic.com, not a claude.ai subscription
./gradlew bootRun
curl -sX POST localhost:8080/api/v1/workflows \
  -H 'Content-Type: application/json' -H 'X-Role: OPERATOR' \
  -d '{"requirement":"Build a URL shortener service with create, resolve and stats APIs."}'
```

The startup log reads `using the live provider on claude-opus-5` rather than
`using the deterministic provider`, confirming which adapter is active.

### The scenarios

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

## Worked example

Captured from a running instance, not written by hand.

### Ambiguity stops the system, and the answer reshapes the plan

`POST /api/v1/workflows` with `"Improve analytics"` — deliberately under-specified:

```
state: AWAITING_CLARIFICATION   tasks: 1   revision: 0
questions:
  - Which dimension should analytics break down by — time, geography, referrer, or device?
  - Is this about collecting data the service does not yet capture, or presenting data it already has?
  - Should historical clicks be backfilled, or does the new breakdown start from deployment?
  - Is per-link granularity sufficient, or is an account-level roll-up needed?
```

Nothing was designed, generated or written. The graph is one node:

```
requirement              AWAITING_HUMAN <- (root)
```

After `POST /{id}/clarify` with *"Break clicks down by country using the
X-Client-Country header, on a new per-link analytics endpoint"*:

```
state: AWAITING_APPROVAL   tasks: 10   revision: 1

requirement              AWAITING_HUMAN <- (root)
requirement-clarified    SUCCEEDED      <- (root)
repository-analysis      SUCCEEDED      <- requirement-clarified
architecture             SUCCEEDED      <- repository-analysis
implementation           SUCCEEDED      <- architecture
tests                    SUCCEEDED      <- implementation
documentation            SUCCEEDED      <- implementation
patch-apply              SUCCEEDED      <- tests, documentation
validate                 SUCCEEDED      <- patch-apply
release-readiness        AWAITING_HUMAN <- validate
```

One task became ten and the revision advanced. The original `requirement` node
stays `AWAITING_HUMAN` rather than being rewritten — it genuinely did stop, and
the lineage should say so.

### A failing build re-plans rather than retries

A run whose first implementation does not compile:

```
final state: AWAITING_APPROVAL
repair rounds: 1   rollbacks: 1   revision: 1

...
validate                 SUCCEEDED      <- patch-apply
release-readiness        SUPERSEDED     <- validate
repair-1                 SUCCEEDED      <- (root)
patch-apply-1            SUCCEEDED      <- repair-1
validate-1               SUCCEEDED      <- patch-apply-1
release-readiness-1      AWAITING_HUMAN <- validate-1
```

The graph changed shape. `validate` stays `SUCCEEDED` — it ran the build and
returned a truthful answer, so the *task* succeeded; the *change* is what failed.
The approval gate hanging off it is superseded, which is the load-bearing part:
without that, a human would be asked to approve a build that failed.

The lineage records the sequence:

```
[ORCHESTRATOR] VALIDATION_RESULT      rev0  Build failed (exit 1)
[ORCHESTRATOR] ROLLBACK_PERFORMED     rev0  restoring workspace before repair round 1:
                                            restored 1 files, removed 15 (verified)
[ORCHESTRATOR] PLAN_REVISED           rev1  validation failed; repair round 1 planned
                                            from build evidence
[ORCHESTRATOR] PLAN_REVISED           rev1  added [repair-1, patch-apply-1, validate-1,
                                            release-readiness-1]
[ORCHESTRATOR] APPROVAL_REQUESTED     rev1  Change is ready for review.
```

Note the rollback happens *before* the repair, and its restore is verified.

### Governance is enforced, not documented

```
OPERATOR approving their own run   -> 403  this action requires the APPROVER role,
                                           but the caller is OPERATOR
no X-Role header at all            -> 403  no role supplied; set the X-Role header
APPROVER approving                 -> COMPLETED
```

### The five reliability metrics, populated

From `/actuator/prometheus` after the runs above:

```
workflow_outcome_total{state="COMPLETED",succeeded="true"}  1.0
workflow_duration_seconds_sum{state="COMPLETED"}           19.555
workflow_mttr_seconds_sum                                  11.117
workspace_rollback_total                                    1.0
validation_failure_total                                    1.0
workflow_clarification_total                                1.0
```

The MTTR of 11.1s is real: measured from the first validation failure to the run
reaching a good terminal state — not from process start, and not recorded for
runs that never broke.

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
| Durable rollback | `SnapshotStore` | Losing the restore point with the process — snapshots are on disk before the mutation, so an interrupted patch is restored rather than re-baselined |
| Fixed executable capability | `BuildValidator` | Model-supplied commands, arguments or working directories — they are not parameters |
| Credential stripping | `BuildValidator` | The build inheriting anything credential-shaped |
| Entry gate | `PolicyGuard` | Runs exceeding the wall-clock ceiling or growing past the task limit |
| Separation of duties | `Role` | An operator approving their own run, and any caller who sends no role at all — the header is required, with no default |
| Serialised decisions | `WorkflowService` | Two approvers, or an approve racing a reject, both taking effect on one run |
| Plan integrity | `WorkflowPlanner` | A model-authored graph — so injection cannot emit a plan with no approval step |
| Capability allow-list | `AgentType` | Agents the platform does not already know how to authorize and audit |

`GuardrailTest` attacks these directly, including the case that must *not* be
blocked: a `..` that normalises back inside the workspace is fine, because the
control is containment, not pattern matching.

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

Three layers:

**Unit — orchestration logic** (`WorkflowGraphTest`). Cycle rejection, parallel
readiness, join synchronisation, bounded retries, transitive blocking, graph
reshaping. Fast and deterministic.

**Adversarial — guardrails** (`GuardrailTest`). Described above.

**Integration — the real thing** (`ScenarioIT`, `RepairLoopIT`,
`CrashRecoveryIT`, `GeneratedProjectBuildsIT`). Real workspaces, real Gradle
builds, real subprocess execution. `GeneratedProjectBuildsIT` proves the
generated URL shortener actually compiles and passes its own tests rather than
merely looking plausible; `RepairLoopIT` drives a genuinely failing compile
through rollback, re-planning and recovery.

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

- **Single-process orchestration.** State is durable and crash-recoverable, and
  human decisions are serialised per workflow, but there is no distributed
  workflow ownership. Running multiple replicas needs leasing — Postgres
  advisory locks, or a durable workflow engine like Temporal. The `@Version`
  column on the checkpoint entity is in place for this reason.
- **Workflow start is synchronous.** `POST /api/v1/workflows` blocks until the
  run settles or parks, which for a greenfield build means holding the
  connection through a real Gradle build. Production would return `202` with a
  location header and execute on a queue; the engine is already structured for
  it, since `drive()` takes a run and does not care who called it.
- **H2 file storage.** Genuinely durable across restarts, which is what the
  recovery tests exercise. Production would use Postgres; the store is behind an
  interface.
- **Role checks are header-based.** A prototype boundary, not authentication.
  What it establishes is that the two capabilities are *distinct by design*; a
  real deployment substitutes the identity provider and keeps the distinction.
- **The repository digest is capped** at 40 files and 4KB each. Enough for the
  agent to infer conventions from a service of this size, and a hard limit on
  prompt cost — but a large repository is only partly seen. Selecting the
  relevant subset, rather than the first N alphabetically, is the next step.
- **No model-provider fallback.** If the live provider fails, the task fails and
  the retry budget applies. There is no automatic downgrade to a second model.
- **Whole-file replacement, not diffs.** Fuzzy patch application against
  model-authored hunks fails in ways that are hard to detect and easy to
  half-apply. Whole-file writes guarded by an optimistic lock either land
  completely or are refused. The cost is token usage on large files.
- **No Docker image or OpenAPI schema.** The build needs only a JDK and the
  vendored wrapper, and the API surface is small enough to read from the
  controller, so neither was worth the time against the deliverables.
- **The generated shortener stores mappings in memory.** It is the workload, not
  the deliverable; its own limitations are documented in its README.

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
