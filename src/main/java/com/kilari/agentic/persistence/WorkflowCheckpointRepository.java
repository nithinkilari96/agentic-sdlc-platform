package com.kilari.agentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowCheckpointRepository extends JpaRepository<WorkflowCheckpointEntity, String> {
}
