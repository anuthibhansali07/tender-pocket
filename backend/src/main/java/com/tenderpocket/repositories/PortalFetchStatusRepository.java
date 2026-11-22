package com.tenderpocket.repositories;

import com.tenderpocket.models.PortalFetchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PortalFetchStatusRepository extends JpaRepository<PortalFetchStatus, String> {
}
