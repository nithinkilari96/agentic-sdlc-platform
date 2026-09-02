package com.kilari.agentic.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Resumes interrupted workflows at startup.
 *
 * <p>This is what makes durability real rather than decorative. Persisting state
 * matters only if something reads it back: without this runner, a crash mid-run
 * would leave a perfectly recorded workflow that nothing ever picks up again.
 *
 * <p>Failures here are logged, not rethrown. A single unrecoverable run must not
 * prevent the application from starting — that would turn one broken workflow
 * into a total outage.
 */
@Component
public class RecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryRunner.class);

    private final WorkflowService workflowService;

    public RecoveryRunner(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int resumed = workflowService.recoverInterruptedRuns();
            if (resumed > 0) {
                log.info("Startup recovery resumed {} interrupted workflow(s)", resumed);
            }
        } catch (Exception e) {
            log.error("Startup recovery failed; the application will continue. "
                    + "Interrupted runs remain in storage and can be resumed manually.", e);
        }
    }
}
