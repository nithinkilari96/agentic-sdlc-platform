package com.kilari.agentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DecisionRecordRepository extends JpaRepository<DecisionRecordEntity, Long> {

    List<DecisionRecordEntity> findByWorkflowIdOrderByRecordedAtAsc(String workflowId);

    long countByWorkflowId(String workflowId);
}
