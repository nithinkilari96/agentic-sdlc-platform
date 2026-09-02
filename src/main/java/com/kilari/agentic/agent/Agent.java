package com.kilari.agentic.agent;

import com.kilari.agentic.orchestration.AgentType;

/**
 * One step of engineering work.
 *
 * <p>Agents are pure with respect to orchestration: they read context, do their
 * job, and return an outcome. They never mutate the graph, never decide their
 * own retries, and never write to the filesystem directly — the deterministic
 * layer owns all three.
 */
public interface Agent {

    AgentType type();

    AgentOutcome execute(AgentExecution execution);
}
