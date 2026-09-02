package com.kilari.agentic.orchestration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The dependency graph a workflow executes.
 *
 * <p>This is the difference between an agentic system and a prompt chain. Order
 * is not written down anywhere: it is derived from declared dependencies, which
 * means branches with no dependency between them are free to run in parallel,
 * and a node with several dependencies is a synchronisation point that cannot
 * start until all of them have succeeded.
 *
 * <p>The graph is also mutable in a controlled way. {@link #applyRevision} lets
 * the planner reshape the remaining work when upstream outputs change, without
 * disturbing what has already completed.
 */
public class WorkflowGraph {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, TaskNode> nodes = new LinkedHashMap<>();

    public WorkflowGraph() {
    }

    public WorkflowGraph(List<TaskNode> initialNodes) {
        initialNodes.forEach(this::addInternal);
        validateAcyclic();
    }

    private void addInternal(TaskNode node) {
        if (nodes.containsKey(node.id())) {
            throw new IllegalArgumentException("duplicate task id: " + node.id());
        }
        nodes.put(node.id(), node);
    }

    public void add(TaskNode node) {
        lock.lock();
        try {
            addInternal(node);
            validateAcyclic();
        } finally {
            lock.unlock();
        }
    }

    public Optional<TaskNode> node(String id) {
        lock.lock();
        try {
            return Optional.ofNullable(nodes.get(id));
        } finally {
            lock.unlock();
        }
    }

    public List<TaskNode> nodes() {
        lock.lock();
        try {
            return List.copyOf(nodes.values());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Verifies every dependency exists and the graph has no cycles, using
     * Kahn's algorithm. Called on construction and after every revision so a
     * re-planning pass can never produce a graph that would deadlock at runtime.
     */
    public void validateAcyclic() {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (TaskNode node : nodes.values()) {
            indegree.putIfAbsent(node.id(), 0);
            for (String dep : node.dependsOn()) {
                if (!nodes.containsKey(dep)) {
                    throw new IllegalStateException(
                            "task %s depends on unknown task %s".formatted(node.id(), dep));
                }
                indegree.merge(node.id(), 1, Integer::sum);
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.id());
            }
        }

        Deque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                queue.add(id);
            }
        });

        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.poll();
            visited++;
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                if (indegree.merge(dependent, -1, Integer::sum) == 0) {
                    queue.add(dependent);
                }
            }
        }

        if (visited != nodes.size()) {
            Set<String> cycle = new HashSet<>(nodes.keySet());
            indegree.forEach((id, degree) -> {
                if (degree == 0) {
                    cycle.remove(id);
                }
            });
            throw new IllegalStateException("workflow graph contains a cycle involving: " + cycle);
        }
    }

    /**
     * Tasks eligible to start right now: not yet terminal, not currently running,
     * with every dependency already succeeded.
     *
     * <p>Returning a list rather than a single task is what enables parallelism —
     * the executor decides how many to run at once, and the graph simply reports
     * everything that is currently unblocked.
     */
    public List<TaskNode> readyTasks() {
        lock.lock();
        try {
            return nodes.values().stream()
                    .filter(n -> n.state() == TaskState.PENDING || n.state() == TaskState.FAILED)
                    .filter(this::dependenciesSatisfied)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    private boolean dependenciesSatisfied(TaskNode node) {
        return node.dependsOn().stream()
                .map(nodes::get)
                .allMatch(dep -> dep.state().isSuccessful());
    }

    /**
     * Propagates terminal failure downstream: anything depending on a task that
     * can never succeed is marked BLOCKED rather than left waiting forever.
     *
     * @return the tasks newly blocked by this pass
     */
    public List<TaskNode> propagateBlocked() {
        lock.lock();
        try {
            List<TaskNode> newlyBlocked = new ArrayList<>();
            boolean changed = true;
            while (changed) {
                changed = false;
                for (TaskNode node : nodes.values()) {
                    if (node.state().isTerminal() || node.state() == TaskState.RUNNING) {
                        continue;
                    }
                    Optional<TaskNode> deadDependency = node.dependsOn().stream()
                            .map(nodes::get)
                            .filter(dep -> dep.state().isTerminalFailure())
                            .findFirst();
                    if (deadDependency.isPresent()) {
                        node.markBlocked("upstream task %s did not succeed".formatted(deadDependency.get().id()));
                        newlyBlocked.add(node);
                        changed = true;
                    }
                }
            }
            return newlyBlocked;
        } finally {
            lock.unlock();
        }
    }

    /** True when no task can make further progress, whether or not all succeeded. */
    public boolean isSettled() {
        lock.lock();
        try {
            return nodes.values().stream().allMatch(n -> n.state().isTerminal())
                    || (readyTasks().isEmpty() && runningCount() == 0);
        } finally {
            lock.unlock();
        }
    }

    public boolean allSucceeded() {
        lock.lock();
        try {
            return nodes.values().stream()
                    .filter(n -> n.state() != TaskState.SUPERSEDED)
                    .allMatch(n -> n.state().isSuccessful());
        } finally {
            lock.unlock();
        }
    }

    public long runningCount() {
        lock.lock();
        try {
            return nodes.values().stream().filter(n -> n.state() == TaskState.RUNNING).count();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reshapes the remaining graph in response to new information.
     *
     * <p>This is real re-planning rather than a retry: nodes that have not yet
     * run can be superseded and replaced with a different set, so the shape of
     * the work changes. Completed nodes are deliberately left alone — their side
     * effects already happened, and rewriting history would make the audit
     * lineage a lie.
     *
     * @return a description of what actually changed, for the lineage
     */
    public PlanDelta applyRevision(PlanRevision revision) {
        lock.lock();
        try {
            List<String> superseded = new ArrayList<>();
            for (String id : revision.supersede()) {
                TaskNode node = nodes.get(id);
                if (node == null) {
                    continue;
                }
                if (node.state().isSuccessful()) {
                    // Already produced output that downstream work may depend on.
                    continue;
                }
                if (node.state() == TaskState.RUNNING) {
                    throw new IllegalStateException(
                            "cannot supersede task %s while it is running".formatted(id));
                }
                node.markSuperseded();
                superseded.add(id);
            }

            List<String> added = new ArrayList<>();
            for (TaskNode node : revision.add()) {
                addInternal(node);
                added.add(node.id());
            }

            validateAcyclic();
            return new PlanDelta(added, superseded, revision.reason());
        } finally {
            lock.unlock();
        }
    }

    /** A requested reshaping of the graph. */
    public record PlanRevision(List<TaskNode> add, List<String> supersede, String reason) {
        public PlanRevision {
            add = add == null ? List.of() : List.copyOf(add);
            supersede = supersede == null ? List.of() : List.copyOf(supersede);
        }
    }

    /** What a revision actually changed, after conflicts with completed work were resolved. */
    public record PlanDelta(List<String> added, List<String> superseded, String reason) {
        public boolean isEmpty() {
            return added.isEmpty() && superseded.isEmpty();
        }
    }

    /** Renders the graph as an adjacency listing, used in the reviewable outcome. */
    public String describe() {
        lock.lock();
        try {
            StringBuilder sb = new StringBuilder();
            for (TaskNode node : nodes.values()) {
                sb.append(node.id())
                        .append(" (").append(node.agentType()).append(") [").append(node.state()).append("]");
                if (!node.dependsOn().isEmpty()) {
                    sb.append(" <- ").append(String.join(", ", node.dependsOn()));
                }
                sb.append('\n');
            }
            return sb.toString();
        } finally {
            lock.unlock();
        }
    }
}
