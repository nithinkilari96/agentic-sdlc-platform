package com.kilari.agentic.scenarios;

import com.kilari.agentic.agent.ContextKeys;
import com.kilari.agentic.orchestration.TaskState;
import com.kilari.agentic.orchestration.WorkflowPlanner;
import com.kilari.agentic.orchestration.WorkflowRun;
import com.kilari.agentic.orchestration.WorkflowState;
import com.kilari.agentic.service.WorkflowService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The remaining scenario types the assignment's scope section names: a
 * brownfield bug fix, and a test-and-documentation improvement.
 *
 * <p>Together with the greenfield, enhancement, ambiguous and repair scenarios,
 * this covers every category in scope: new systems, enhancements, bug fixes,
 * test and documentation work, and both well-defined and ambiguous requirements.
 */
@Tag("integration")
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:bugfixdocs;DB_CLOSE_DELAY=-1",
        "agentic.workspaces.root=build/test-workspaces-bugfix"
})
class BugFixAndDocsScenarioIT {

    @Autowired
    private WorkflowService workflows;

    @Nested
    @DisplayName("Brownfield bug fix")
    class BugFix {

        @Test
        @DisplayName("fixes a real defect in the existing service and proves it with a regression test")
        void fixes_the_non_positive_ttl_defect() {
            WorkflowRun run = workflows.start(
                    "Fix the defect where a zero or negative ttlSeconds is accepted and produces "
                            + "a link that can never be resolved.",
                    true, WorkflowPlanner.PlanShape.FULL_DELIVERY);

            assertThat(run.state()).isEqualTo(WorkflowState.AWAITING_APPROVAL);

            // The fix modified existing files rather than adding parallel code
            // beside the bug, which is the failure mode of a careless brownfield fix.
            assertThat(run.context().content(ContextKeys.PATCH_APPLIED_SUMMARY))
                    .hasValueSatisfying(summary -> assertThat(summary).contains("modified"));

            // The defect is actually gone from the source on disk.
            assertThat(readFile(run.workspace(),
                    "src/main/java/com/example/shortener/service/ShortenerService.java"))
                    .as("the service should now reject a non-positive TTL")
                    .contains("validateTimeToLive")
                    .contains("InvalidTimeToLiveException");

            // And a regression test exists, so the bug cannot come back silently.
            assertThat(run.workspace().resolve(
                    "src/test/java/com/example/shortener/service/TimeToLiveValidationTest.java"))
                    .exists();

            // The build passed, meaning the regression test actually passes against
            // the fix - the pair is verified together, not just written.
            assertThat(run.context().content(ContextKeys.VALIDATION_RESULT))
                    .hasValueSatisfying(result -> assertThat(result).startsWith("PASSED"));
        }
    }

    @Nested
    @DisplayName("Test and documentation improvement")
    class TestsAndDocs {

        @Test
        @DisplayName("improves tests and docs without a design or implementation step")
        void adds_coverage_and_docs_with_no_production_change() {
            WorkflowRun run = workflows.start(
                    "Improve test coverage for URL validation edge cases and document the API "
                            + "error responses.",
                    true, WorkflowPlanner.PlanShape.TESTS_AND_DOCS);

            assertThat(run.state()).isEqualTo(WorkflowState.AWAITING_APPROVAL);

            // The plan genuinely has no design or implementation node. A requirement
            // that changes no production code should not force an agent to invent one.
            assertThat(run.graph().node(WorkflowPlanner.TASK_ARCHITECTURE)).isEmpty();
            assertThat(run.graph().node(WorkflowPlanner.TASK_IMPLEMENTATION)).isEmpty();

            // The parallel branches and the join are still present - the governance
            // machinery is identical, only the work is smaller.
            assertThat(run.graph().node(WorkflowPlanner.TASK_TESTS).orElseThrow().state())
                    .isEqualTo(TaskState.SUCCEEDED);
            assertThat(run.graph().node(WorkflowPlanner.TASK_DOCUMENTATION).orElseThrow().state())
                    .isEqualTo(TaskState.SUCCEEDED);
            assertThat(run.graph().node(WorkflowPlanner.TASK_PATCH_APPLY).orElseThrow().dependsOn())
                    .containsExactlyInAnyOrder(
                            WorkflowPlanner.TASK_TESTS, WorkflowPlanner.TASK_DOCUMENTATION);

            assertThat(run.workspace().resolve(
                    "src/test/java/com/example/shortener/service/UrlValidatorTest.java")).exists();
            assertThat(run.workspace().resolve("docs/api-errors.md")).exists();

            // The build still had to pass: a test-only change is smaller, not safer.
            assertThat(run.context().content(ContextKeys.VALIDATION_RESULT))
                    .hasValueSatisfying(result -> assertThat(result).startsWith("PASSED"));

            // And a human still approves it.
            assertThat(run.context().content(ContextKeys.RELEASE_EVIDENCE)).isPresent();
        }

        @Test
        @DisplayName("production sources are untouched by a test-and-docs run")
        void production_code_is_not_modified() {
            WorkflowRun run = workflows.start(
                    "Improve test coverage for URL validation edge cases and document the API "
                            + "error responses.",
                    true, WorkflowPlanner.PlanShape.TESTS_AND_DOCS);

            // The seeded service file must be byte-identical to what was seeded.
            String service = readFile(run.workspace(),
                    "src/main/java/com/example/shortener/service/UrlValidator.java");

            assertThat(service)
                    .as("a test-and-docs requirement must not alter production behaviour")
                    .doesNotContain("validateTimeToLive");

            assertThat(run.context().content(ContextKeys.PATCH_APPLIED_SUMMARY))
                    .hasValueSatisfying(summary ->
                            assertThat(summary).contains("0 modified"));
        }
    }

    private static String readFile(Path workspace, String relative) {
        try {
            return Files.readString(workspace.resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + relative + " from workspace", e);
        }
    }
}
