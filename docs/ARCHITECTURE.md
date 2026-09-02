# Architecture

This document records the decisions that shaped the platform and, for each, what
was rejected. A decision without a rejected alternative is not a decision — it is
a default.

---

## 1. The model does not control the system

Everything else follows from this.

An agentic platform can be built two ways. Either the model drives — deciding
what to do next, which tools to call, when it is finished — or the model is
consulted at points a deterministic program controls. This platform is
emphatically the second.

**Why.** The properties this system is required to have are all properties of the
control flow: an explicit dependency graph, bounded retries, human approval at
high-impact points, rollback, safe-stop, audit lineage. If the model decides
control flow, then it decides all of those too — and a prompt injected through a
requirement description, a repository file, or a build error could produce a run
with no approval step in it. Making the graph deterministic means an attacker who
fully controls the model's output still cannot remove a gate.

**Rejected: model-driven tool-calling loops.** Simpler to build and more
impressive in a demo. But the governance guarantees become "the model usually
does the right thing", which is not a guarantee.

**Consequence.** `WorkflowPlanner` builds graphs in Java. The model can propose
*what a change should be*; it cannot propose *what steps are allowed to happen*.

---

## 2. A dependency graph, not a pipeline

Tasks declare what they depend on. Nothing declares order.

**Why.** Order derived from dependencies gives three things for free that a
sequential chain cannot. Independent work is parallelisable without anyone
deciding to parallelise it — tests and documentation both depend only on the
implementation, so they run concurrently. Synchronisation is a natural
consequence rather than a mechanism: `patch-apply` depends on both branches, so
it cannot start until both finish. And re-planning becomes tractable, because
replacing pending work is a graph operation rather than a rewrite of control
flow.

**Rejected: linear stage chaining.** Easier to follow, but parallelism has to be
hand-coded, joins become explicit barriers someone can forget, and dynamic
re-planning means editing the chain in place.

Kahn's algorithm validates acyclicity on construction *and* after every revision,
so a re-planning pass cannot produce a graph that deadlocks at runtime.

---

## 3. Orchestration, not choreography

A central coordinator owns the graph and decides what runs next. The alternative
— event choreography, where each stage consumes from a topic and publishes the
next event with no coordinator — is a well-established pattern and would have
scaled better.

**Why a coordinator.** Every governance property the brief asks for is a
statement about the *whole* run: an explicit dependency graph with entry and exit
gates, human approval before high-impact actions, bounded retries, safe-stop,
rollback. In choreography those guarantees are distributed across every consumer,
and each one has to independently not violate them. "No approval gate can be
bypassed" becomes a property you hope holds across eight services rather than one
you can check.

With a coordinator, the same claim is verifiable by reading `WorkflowEngine` and
`WorkflowPlanner`. When a re-plan inserts a repair round, one place decides that
the superseded approval gate must not fire; in a choreographed version, a stale
consumer holding an in-flight message could still deliver it. That is a subtle
bug with a serious consequence — a human approving a build that failed.

**Rejected: Kafka or JMS choreography.** Better throughput, better failure
isolation, and genuinely the right answer for a high-volume pipeline. But it
trades away the property this system exists to demonstrate: that the boundary
between what the model proposes and what the platform permits is small enough to
audit. It also moves ordering into broker semantics — partition keys, redelivery,
consumer-group rebalancing — which is a great deal of machinery to reason about
before you can answer "could this run have skipped its approval step?"

**What we gave up, and where it hurts.** Throughput is bounded by one process,
and horizontal scale needs distributed workflow ownership before a second replica
can safely exist. That is a real limitation, documented as such. The mitigation
is that the boundary is already drawn correctly: `drive()` takes a run and does
not care who called it, so moving execution behind a queue is an addition rather
than a redesign. The `@Version` column on the checkpoint entity exists for the
same reason.

The honest summary: choreography scales better, orchestration is *provable*, and
this assignment is about governed autonomy rather than throughput.

---

## 4. Re-planning is not retrying

When validation fails, the platform does not re-run the failed node. It bumps the
context revision, rolls the workspace back to its verified snapshot, and replaces
the remaining graph with a different shape:

```
before:   … → patch-apply → validate → release
after:    … → patch-apply → validate(superseded)
                     └→ repair-1 → patch-apply-1 → validate-1 → release-1
```

**Why.** A retry runs the same node against unchanged inputs and gets the same
answer. What changed is not the node — it is the *world*: there is now build
evidence that did not exist before. The repair agent consumes that evidence, so
the work being done is genuinely different, and the graph should say so.

**Why the rollback first.** Without it, each repair round layers edits on top of a
half-working previous attempt. By round two the diff stops being reviewable, and
the human at the approval gate is looking at an accumulation of guesses rather
than a change.

**Why completed nodes are never superseded.** Their side effects already happened.
Rewriting them would make the audit lineage a record of what someone later wished
had occurred.

Bounded at two rounds: an agent that cannot fix a failure in two informed
attempts is unlikely to fix it in ten, and an unbounded repair loop is an
unbounded bill.

---

## 5. Context revisions make staleness detectable

Agents never mutate each other's outputs. They publish artifacts at the current
revision. When new information arrives that invalidates earlier assumptions — a
human clarification, a validation failure — the revision is bumped, and every
artifact produced before it is marked stale.

**Why.** Without this, a resumed or re-planned run silently reuses work derived
from assumptions that no longer hold, and nothing about the artifact indicates
that. With it, staleness is a queryable property.

**Rejected: mutable shared context.** Cheaper, but the moment a clarification
arrives there is no way to tell which prior conclusions it invalidated.

---

## 6. Approval sits after validation, not before every step

The human gate is a single checkpoint, placed once executable evidence exists.

**Why.** Asking a person to approve each intermediate artifact produces
rubber-stamping. A reviewer has no basis on which to judge a design document in
isolation, so they approve it, and the approval carries no information. Asking
once — with a passing build, a complete diff, the design rationale and the
decision lineage in front of them — is a judgement a person can actually make.

**Rejected: per-step approval.** Looks more controlled. Produces less control,
because the approvals become reflexive.

**Separation of duties.** Starting a run requires `OPERATOR`; approving requires
`APPROVER`. An operator who could approve their own run would make the gate
ceremonial.

---

## 7. Ambiguity stops the system

`RequirementAgent` scores its own confidence. The *platform* compares that score
to a threshold it owns (0.60) and parks the run below it.

**Why the platform owns the threshold.** If the model decided whether it was
confident enough to proceed, then "am I sure?" would be answered by the same
process that produced the uncertainty, and the check would be decorative.

**Why stopping is the valuable behaviour.** Everything downstream inherits the
interpretation fixed at this step. An agent that resolves ambiguity by picking
the most likely reading produces work that looks complete and cannot be
reviewed — because no human ever agreed to the requirement it actually built. The
cost of asking is one round trip. The cost of guessing is a migration.

The ambiguous run also starts with a one-node graph, so an under-specified
requirement costs a single model call rather than a full plan that is immediately
abandoned.

---

## 8. One executable capability, and it takes no parameters

`BuildValidator` runs a fixed command in the workspace. The model supplies no
command, no arguments, no working directory — because none of those are
parameters of the class.

**Why this framing matters.** "The agent is not allowed to run arbitrary
commands" is a policy, and policies are enforced by code that can have gaps.
"There is no code path by which a command reaches the process builder" is a
structural property. The second is checkable by reading one file.

Credentials are stripped from the build environment: a generated build script
executes arbitrary code by design, so it must not inherit anything worth
stealing. Output is bounded to a tail — a runaway build must not exhaust heap,
and the end of the log is where failures are described.

---

## 9. One component writes to disk

`PatchApplier` is the only code in the platform that writes agent-authored
content.

**Why.** It makes the security story checkable. One method to audit, one policy
to satisfy, one snapshot taken before anything changes — rather than a dozen call
sites each deciding for themselves whether a path looks safe.

Patches are validated whole before any byte is written, so a rejected change
cannot leave a half-applied workspace that no snapshot describes and no agent
intended.

**Whole-file replacement, not diffs.** Fuzzy patch application against
model-authored hunks fails in ways that are hard to detect and easy to
half-apply. A whole-file write guarded by an optimistic lock on the file's prior
hash either lands completely or is refused.

---

## 10. Rollback is verified, not asserted

`WorkspaceSnapshot` re-hashes every file after a restore and raises if anything
mismatches.

**Why.** A rollback that silently half-succeeded is worse than no rollback,
because the run continues on a workspace nobody has an accurate description of.
"We restored the snapshot" is a claim; matching hashes are evidence.

Snapshots store bytes, not text. A workspace legitimately contains binaries, and
reading one as UTF-8 both corrupts it and throws — a snapshot that cannot
represent every file is not a restore point. *(This was found by a test, not by
inspection.)*

They are also written to disk before the mutation happens, outside the workspace
they describe. An in-memory-only snapshot means a crash after a patch lands
leaves the next process with a modified workspace and no record of the original.
The dangerous part is not the missing rollback: re-running the interrupted task
would capture the already-modified tree as its new baseline, so a later rollback
would faithfully restore the broken state **and report success**. A safety
control that silently lies is worse than an absent one. The manifest is written
last, so an interrupted write reads as absent rather than partial, and content is
verified against its hash on load so a corrupt backup is refused.

*(Both this and the lost recovery counters were raised by a review of the
submitted code, and confirmed with failing tests before being fixed.)*

---

## 11. Durable state, and a task that was running is not assumed finished

State is checkpointed after every batch of tasks. On startup, interrupted runs
are rehydrated and resumed.

**Why checkpoints and not just an audit log.** Recording what a run did is enough
to explain a crash afterwards. It is not enough to continue one. The distinction
is the difference between an audit trail and a recovery mechanism.

**The subtle part.** A task recorded as `RUNNING` when the process died has
*unknown* side effects — it may have written half a patch. Recovery resets it to
`PENDING` so it re-runs from a verified snapshot. Assuming completion is how a
recovery mechanism silently skips work, and that failure is close to
undiagnosable after the fact.

Parked runs are rehydrated but not driven. They are waiting on a person, and a
restart is not an answer.

---

## 12. The model seam is three methods

`ModelProvider` has `name()`, `isLive()` and `complete()`. Everything above it is
deterministic Java that behaves identically whichever implementation is wired in.

**Why not Spring AI or a similar framework.** Their principal feature is making
model-invoked tool calling easy — which is the opposite of this architecture's
thesis. The model here does not get tools. Adding a framework whose value
proposition is "let the model call your functions" would buy nothing and work
against the property being demonstrated. The seam is small enough to own.

**Why the deterministic provider exists.** So orchestration correctness can be
tested exhaustively without model nondeterminism. When a test fails, it is
unambiguous that the orchestrator broke — not that the model answered
differently. It is a test double, not a claim about reasoning.

---

## Where this would go next

Honest about the order these matter in:

1. **Distributed workflow ownership.** Multiple replicas need leasing before they
   can share a queue — Postgres advisory locks or row-level leases as the
   incremental step, Temporal if durable execution is wanted wholesale.

   Two seams already exist for this, which is why it is an addition rather than a
   rewrite. The `@Version` column on the checkpoint entity makes a concurrent
   write from a second replica fail loudly instead of silently winning. And
   `WorkflowEngine.drive()` takes a run and does not care who called it — it has
   no notion of an HTTP request, a queue or a scheduler — so the thing that
   decides *when* a run advances can be replaced without touching the thing that
   decides *how*.
2. **Postgres instead of H2.** Behind the `WorkflowStore` interface already.
3. **Real identity.** Replace header roles with the identity provider, keeping
   the operator/approver split.
4. **Richer re-planning triggers.** Today the graph reshapes on clarification and
   on validation failure. Architecture review rejecting a design, or a
   dependency-scan finding, are natural additional triggers using the same
   revision machinery.
