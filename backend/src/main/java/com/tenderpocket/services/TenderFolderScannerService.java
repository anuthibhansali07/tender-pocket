package com.tenderpocket.services;

import com.tenderpocket.models.Tender;
import com.tenderpocket.models.TenderDocumentManifest;
import com.tenderpocket.repositories.TenderDocumentManifestRepository;
import com.tenderpocket.repositories.TenderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * TenderFolderScannerService
 * Recursively scans the physical working directory / documents path for a tender,
 * indexes every file into TenderDocumentManifest, categorizes them, and provides
 * full directory verification capabilities for MIS audit (Track B).
 */
@Service
public class TenderFolderScannerService {

    @Value("${file.upload-dir:public/documents}")
    private String uploadDir;

    @Autowired
    private TenderDocumentManifestRepository manifestRepository;

    @Autowired
    private TenderRepository tenderRepository;

    /**
     * Determines category based on filename and relative path.
     */
    public String categorizeFile(String fileName, String relativePath) {
        String lower = (fileName + " " + relativePath).toLowerCase();
        if (lower.contains("notice") || lower.contains("bid_document") || lower.contains("rfp") || lower.contains("tender_doc")) {
            return "ORIGINAL_NOTICE";
        }
        if (lower.contains("spec") || lower.contains("compliance") || lower.contains("technical")) {
            return "FINAL_TECH_SPEC";
        }
        if (lower.contains("emd") || lower.contains("receipt") || lower.contains("payment") || lower.contains("utr") || lower.contains("bank")) {
            return "EMD_RECEIPT";
        }
        if (lower.contains("quote") || lower.contains("pricing") || lower.contains("tpc") || lower.contains("oem_cost")) {
            return "QUOTATION";
        }
        if (lower.contains("annexure") || lower.contains("declaration") || lower.contains("undertaking") || lower.contains("bid_doc") || lower.contains("package")) {
            return "BID_PACKAGE";
        }
        return "OTHER";
    }

    /**
     * Scans both the server documents folder and custom working path if provided.
     */
    public List<TenderDocumentManifest> scanAndSyncFolder(String tenderId) {
        Optional<Tender> tenderOpt = tenderRepository.findById(tenderId);
        if (tenderOpt.isEmpty()) {
            return Collections.emptyList();
        }
        Tender tender = tenderOpt.get();

        Set<String> scannedFiles = new HashSet<>();
        List<File> fileList = new ArrayList<>();

        // 1. Scan default public upload folder: {uploadDir}/{tenderId}
        Path tenderFolderPath = Paths.get(uploadDir, tenderId);
        if (Files.exists(tenderFolderPath) && Files.isDirectory(tenderFolderPath)) {
            try (Stream<Path> stream = Files.walk(tenderFolderPath)) {
                stream.filter(Files::isRegularFile).forEach(p -> fileList.add(p.toFile()));
            } catch (IOException ignored) {}
        }

        // 2. Scan custom working_path if configured and exists
        String workingPath = tender.getWorkingPath();
        if (workingPath != null && !workingPath.trim().isEmpty()) {
            try {
                Path customPath = Paths.get(workingPath.trim());
                if (Files.exists(customPath) && Files.isDirectory(customPath)) {
                    try (Stream<Path> stream = Files.walk(customPath)) {
                        stream.filter(Files::isRegularFile).forEach(p -> {
                            File f = p.toFile();
                            if (!scannedFiles.contains(f.getName())) {
                                fileList.add(f);
                            }
                        });
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Sync into TenderDocumentManifest table
        List<TenderDocumentManifest> existing = manifestRepository.findByTenderIdOrderByCreatedAtAsc(tenderId);
        Map<String, TenderDocumentManifest> map = new HashMap<>();
        for (TenderDocumentManifest m : existing) {
            map.put(m.getFileName(), m);
        }

        List<TenderDocumentManifest> results = new ArrayList<>();

        for (File file : fileList) {
            String name = file.getName();
            scannedFiles.add(name);

            // Compute relative web URL
            String relativeUrl = "/documents/" + tenderId + "/" + name;
            String category = categorizeFile(name, file.getAbsolutePath());
            String ext = "";
            int dotIdx = name.lastIndexOf('.');
            if (dotIdx > 0 && dotIdx < name.length() - 1) {
                ext = name.substring(dotIdx + 1).toLowerCase();
            }

            boolean isConfidential = category.equals("EMD_RECEIPT") || category.equals("QUOTATION");
            boolean isAuthoritative = name.equalsIgnoreCase(tender.getFinalTechSpecFilename())
                    || (category.equals("FINAL_TECH_SPEC") && name.toLowerCase().contains("final"));

            TenderDocumentManifest manifest = map.get(name);
            if (manifest == null) {
                manifest = new TenderDocumentManifest(
                        tenderId,
                        name,
                        generateDisplayName(name, category),
                        relativeUrl,
                        category,
                        file.length(),
                        ext,
                        isConfidential,
                        isAuthoritative
                );
            } else {
                manifest.setFileSizeBytes(file.length());
                manifest.setFileExtension(ext);
                manifest.setFileCategory(category);
                if (isAuthoritative) {
                    manifest.setIsAuthoritative(true);
                }
            }
            manifestRepository.save(manifest);
            results.add(manifest);
        }

        return results.isEmpty() ? existing : results;
    }

    private String generateDisplayName(String filename, String category) {
        String clean = filename.replace('_', ' ').replace('-', ' ');
        int dot = clean.lastIndexOf('.');
        if (dot > 0) clean = clean.substring(0, dot);
        return switch (category) {
            case "ORIGINAL_NOTICE" -> "Tender Notice / RFP (" + clean + ")";
            case "FINAL_TECH_SPEC" -> "Authoritative Technical Specification (" + clean + ")";
            case "BID_PACKAGE" -> "Bid Document (" + clean + ")";
            case "EMD_RECEIPT" -> "EMD Payment Receipt / Proof (" + clean + ")";
            case "QUOTATION" -> "Cost / Quotation Sheet (" + clean + ")";
            default -> clean;
        };
    }

    public TenderDocumentManifest verifyDocument(Long manifestId, String username) {
        Optional<TenderDocumentManifest> opt = manifestRepository.findById(manifestId);
        if (opt.isPresent()) {
            TenderDocumentManifest doc = opt.get();
            doc.setVerifiedByMis(true);
            doc.setVerifiedByUser(username);
            doc.setVerifiedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            return manifestRepository.save(doc);
        }
        return null;
    }

    public List<TenderDocumentManifest> verifyAllDocuments(String tenderId, String username) {
        List<TenderDocumentManifest> docs = scanAndSyncFolder(tenderId);
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        for (TenderDocumentManifest d : docs) {
            d.setVerifiedByMis(true);
            d.setVerifiedByUser(username);
            d.setVerifiedAt(now);
            manifestRepository.save(d);
        }
        return docs;
    }
}
