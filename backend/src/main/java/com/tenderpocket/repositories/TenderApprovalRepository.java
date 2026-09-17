package com.tenderpocket.repositories;

import com.tenderpocket.models.TenderApprovalRequest;
import com.tenderpocket.models.TenderWorkflowStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TenderApprovalRepository extends JpaRepository<TenderApprovalRequest, Long> {

    // ── Core queries (existing — preserved) ───────────────────────────────────
    List<TenderApprovalRequest> findByTenderIdOrderByCreatedAtDesc(String tenderId);
    List<TenderApprovalRequest> findByAssignedToAndStatus(String assignedTo, String status);
    List<TenderApprovalRequest> findByAssignedToAndStatusOrderByCreatedAtDesc(String assignedTo, String status);
    List<TenderApprovalRequest> findByStatusOrderByCreatedAtDesc(String status);
    List<TenderApprovalRequest> findByAssignedToAndStageAndStatusOrderByCreatedAtDesc(String assignedTo, TenderWorkflowStage stage, String status);
    List<TenderApprovalRequest> findByStageAndStatusOrderByCreatedAtDesc(TenderWorkflowStage stage, String status);
    List<TenderApprovalRequest> findByTenderIdAndStageOrderByCreatedAtDesc(String tenderId, TenderWorkflowStage stage);

    // ── Efficient badge count queries (single DB round-trip using GROUP BY) ───
    // Returns rows of [stage_name (String), count (Long)] for a specific user
    @Query("SELECT a.stage, COUNT(a) FROM TenderApprovalRequest a " +
           "WHERE a.assignedTo = :assignedTo AND a.status = 'PENDING' " +
           "GROUP BY a.stage")
    List<Object[]> countPendingByStageForUser(@Param("assignedTo") String assignedTo);

    // MIS Team count variant: returns pending requests assigned to this user, assigned to 'misteam' / 'MIS Team', unassigned (null), or in MIS workflow stages.
    // Uses :misStages param (List<TenderWorkflowStage>) instead of inline class refs — inline refs are illegal JPQL and crash Hibernate.
    @Query("SELECT a.stage, COUNT(a) FROM TenderApprovalRequest a " +
           "WHERE a.status = 'PENDING' AND (" +
           "  LOWER(a.assignedTo) = LOWER(:assignedTo) " +
           "  OR LOWER(a.assignedTo) = 'misteam' " +
           "  OR LOWER(a.assignedTo) = 'mis team' " +
           "  OR a.assignedTo IS NULL " +
           "  OR a.stage IN :misStages" +
           ") GROUP BY a.stage")
    List<Object[]> countPendingByStageForMisTeam(@Param("assignedTo") String assignedTo,
                                                  @Param("misStages") List<TenderWorkflowStage> misStages);

    @Query("SELECT a FROM TenderApprovalRequest a " +
           "WHERE a.status = 'PENDING' AND (" +
           "  LOWER(a.assignedTo) = LOWER(:assignedTo) " +
           "  OR LOWER(a.assignedTo) = 'misteam' " +
           "  OR LOWER(a.assignedTo) = 'mis team' " +
           "  OR a.assignedTo IS NULL " +
           "  OR a.stage IN :misStages" +
           ") ORDER BY a.createdAt DESC")
    List<TenderApprovalRequest> findPendingForMisTeam(@Param("assignedTo") String assignedTo,
                                                       @Param("misStages") List<TenderWorkflowStage> misStages);

    @Query("SELECT a FROM TenderApprovalRequest a " +
           "WHERE a.status = 'PENDING' AND a.stage = :stage AND (" +
           "  LOWER(a.assignedTo) = LOWER(:assignedTo) " +
           "  OR LOWER(a.assignedTo) = 'misteam' " +
           "  OR LOWER(a.assignedTo) = 'mis team' " +
           "  OR a.assignedTo IS NULL " +
           "  OR a.stage IN :misStages" +
           ") ORDER BY a.createdAt DESC")
    List<TenderApprovalRequest> findPendingByStageForMisTeam(@Param("assignedTo") String assignedTo,
                                                              @Param("stage") TenderWorkflowStage stage,
                                                              @Param("misStages") List<TenderWorkflowStage> misStages);

    // Admin variant — counts across ALL assigned users
    @Query("SELECT a.stage, COUNT(a) FROM TenderApprovalRequest a " +
           "WHERE a.status = 'PENDING' " +
           "GROUP BY a.stage")
    List<Object[]> countPendingByStageForAdmin();
}
