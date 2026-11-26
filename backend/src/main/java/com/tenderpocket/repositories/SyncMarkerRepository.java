package com.tenderpocket.repositories;

import com.tenderpocket.models.SyncMarker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SyncMarkerRepository extends JpaRepository<SyncMarker, String> {
}
