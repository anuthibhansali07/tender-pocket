package com.tenderpocket.repositories;

import com.tenderpocket.models.ActivityLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long>, JpaSpecificationExecutor<ActivityLog> {
    List<ActivityLog> findAllByOrderByTimestampDesc();
    List<ActivityLog> findByActionOrderByTimestampDesc(String action);
    Page<ActivityLog> findAllByOrderByTimestampDesc(Pageable pageable);
    Page<ActivityLog> findByActionOrderByTimestampDesc(String action, Pageable pageable);
}
