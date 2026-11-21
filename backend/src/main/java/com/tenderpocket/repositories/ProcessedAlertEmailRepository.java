package com.tenderpocket.repositories;

import com.tenderpocket.models.ProcessedAlertEmail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Repository
public interface ProcessedAlertEmailRepository extends JpaRepository<ProcessedAlertEmail, String> {

    @Modifying
    @Transactional
    void deleteByProcessedAtBefore(LocalDateTime cutoff);
}
