package com.tenderpocket.repositories;

import com.tenderpocket.models.TenderDocumentManifest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TenderDocumentManifestRepository extends JpaRepository<TenderDocumentManifest, Long> {
    List<TenderDocumentManifest> findByTenderIdOrderByCreatedAtAsc(String tenderId);
    List<TenderDocumentManifest> findByTenderIdAndFileCategory(String tenderId, String fileCategory);
    Optional<TenderDocumentManifest> findByTenderIdAndFileName(String tenderId, String fileName);
    Optional<TenderDocumentManifest> findByTenderIdAndIsAuthoritativeTrue(String tenderId);
    void deleteByTenderId(String tenderId);
}
