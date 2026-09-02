package com.kilari.agentic.config;

import com.kilari.agentic.agent.Agent;
import com.kilari.agentic.agent.ArchitectureAgent;
import com.kilari.agentic.agent.BuildValidationAgent;
import com.kilari.agentic.agent.ContextKeys;
import com.kilari.agentic.agent.PatchApplyAgent;
import com.kilari.agentic.agent.PatchGeneratingAgent;
import com.kilari.agentic.agent.ReleaseReadinessAgent;
import com.kilari.agentic.agent.RepositoryAnalysisAgent;
import com.kilari.agentic.agent.RequirementAgent;
import com.kilari.agentic.governance.PolicyGuard;
import com.kilari.agentic.metrics.WorkflowMetrics;
import com.kilari.agentic.orchestration.AgentType;
import com.kilari.agentic.orchestration.WorkflowEngine;
import com.kilari.agentic.persistence.DecisionRecordRepository;
import com.kilari.agentic.persistence.JpaWorkflowStore;
import com.kilari.agentic.persistence.WorkflowCheckpointRepository;
import com.kilari.agentic.persistence.WorkflowStore;
import com.kilari.agentic.provider.ClaudeModelProvider;
import com.kilari.agentic.provider.DeterministicModelProvider;
import com.kilari.agentic.provider.ModelProvider;
import com.kilari.agentic.service.WorkflowService;
import com.kilari.agentic.tools.BuildValidator;
import com.kilari.agentic.tools.SnapshotStore;
import com.kilari.agentic.tools.WorkspaceFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

@Configuration
public class AgenticConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgenticConfiguration.class);

    /**
     * Selects the model provider.
     *
     * <p>Defaults to the deterministic fixture so the platform runs end-to-end
     * with no credentials — a reviewer can clone and execute every scenario
     * without an account. Setting {@code ANTHROPIC_API_KEY} switches the same
     * agents to the live model through the identical interface; nothing else in
     * the system changes, which is the point of having the seam.
     */
    @Bean
    public ModelProvider modelProvider(
            @Value("${agentic.model.api-key:${ANTHROPIC_API_KEY:}}") String apiKey,
            @Value("${agentic.model.id:claude-opus-5}") String modelId) {

        if (apiKey == null || apiKey.isBlank()) {
            log.info("No ANTHROPIC_API_KEY present - using the deterministic provider. "
                    + "Orchestration, guardrails and recovery all execute normally; "
                    + "agent responses come from fixtures rather than a model.");
            return new DeterministicModelProvider();
        }

        log.info("ANTHROPIC_API_KEY present - using the live provider on {}", modelId);
        return new ClaudeModelProvider(apiKey, modelId);
    }

    @Bean
    public WorkspaceFactory workspaceFactory(
            @Value("${agentic.workspaces.root:workspaces}") String root) {
        // The wrapper is copied from this installation, so a generated project
        // builds with the same toolchain the platform itself was verified on.
        return new WorkspaceFactory(Path.of(root), Path.of("."));
    }

    @Bean
    public BuildValidator buildValidator() {
        return new BuildValidator();
    }

    /**
     * Snapshots live beside the workspaces rather than inside them: a restore
     * walks the workspace tree, so a snapshot stored within it would be walking
     * its own backup.
     */
    @Bean
    public SnapshotStore snapshotStore(
            @Value("${agentic.workspaces.root:workspaces}") String root) {
        return new SnapshotStore(Path.of(root).resolveSibling(
                Path.of(root).getFileName() + "-snapshots"));
    }

    @Bean
    public PolicyGuard policyGuard() {
        return new PolicyGuard();
    }

    @Bean
    public WorkflowMetrics workflowMetrics(MeterRegistry registry) {
        return new WorkflowMetrics(registry);
    }

    @Bean
    public WorkflowStore workflowStore(WorkflowCheckpointRepository checkpoints,
                                       DecisionRecordRepository decisions) {
        return new JpaWorkflowStore(checkpoints, decisions);
    }

    /**
     * The agent registry.
     *
     * <p>A fixed map rather than classpath scanning: the set of capabilities the
     * platform will execute is a governance decision, and it should be visible in
     * one place rather than emerging from whatever happens to be on the classpath.
     *
     * <p>Path policy is not configured here: it is derived per run from that
     * run's own workspace, so one workflow can never be granted write access to
     * another's directory.
     */
    @Bean
    public Map<AgentType, Agent> agentRegistry(ModelProvider provider,
                                               BuildValidator validator) {
        Map<AgentType, Agent> agents = new EnumMap<>(AgentType.class);
        agents.put(AgentType.REQUIREMENT, new RequirementAgent(provider));
        agents.put(AgentType.REPOSITORY_ANALYSIS, new RepositoryAnalysisAgent(provider));
        agents.put(AgentType.ARCHITECTURE, new ArchitectureAgent(provider));
        agents.put(AgentType.IMPLEMENTATION, new PatchGeneratingAgent(
                AgentType.IMPLEMENTATION, ContextKeys.PATCH_IMPLEMENTATION,
                PatchGeneratingAgent.IMPLEMENTATION_PROMPT, provider));
        agents.put(AgentType.TEST, new PatchGeneratingAgent(
                AgentType.TEST, ContextKeys.PATCH_TESTS,
                PatchGeneratingAgent.TEST_PROMPT, provider));
        agents.put(AgentType.DOCUMENTATION, new PatchGeneratingAgent(
                AgentType.DOCUMENTATION, ContextKeys.PATCH_DOCUMENTATION,
                PatchGeneratingAgent.DOCUMENTATION_PROMPT, provider));
        agents.put(AgentType.REPAIR, new PatchGeneratingAgent(
                AgentType.REPAIR, ContextKeys.PATCH_REPAIR,
                PatchGeneratingAgent.REPAIR_PROMPT, provider));
        agents.put(AgentType.PATCH_APPLY, new PatchApplyAgent());
        agents.put(AgentType.BUILD_VALIDATION, new BuildValidationAgent(validator));
        agents.put(AgentType.RELEASE_READINESS, new ReleaseReadinessAgent());
        return agents;
    }

    @Bean
    public WorkflowEngine workflowEngine(Map<AgentType, Agent> agentRegistry,
                                         WorkflowStore store,
                                         WorkflowMetrics metrics,
                                         PolicyGuard policyGuard,
                                         SnapshotStore snapshotStore) {
        return new WorkflowEngine(agentRegistry, store, metrics, policyGuard, snapshotStore);
    }

    @Bean
    public WorkflowService workflowService(WorkflowEngine engine,
                                           WorkflowStore store,
                                           WorkspaceFactory workspaces,
                                           WorkflowMetrics metrics) {
        return new WorkflowService(engine, store, workspaces, metrics);
    }
}
