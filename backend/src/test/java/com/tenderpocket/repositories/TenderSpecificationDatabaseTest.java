package com.tenderpocket.repositories;

import com.tenderpocket.models.Tender;
import com.tenderpocket.repositories.specifications.TenderSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class TenderSpecificationDatabaseTest {

    @Autowired
    private TenderRepository tenderRepository;

    private Tender createTender(String id, String title, String refNo, String authority,
                                String sourceId, String location, String sector, String source,
                                Double cost, String status, String specStatus,
                                String misExec, String specMember, String dueDate, String publishDate) {
        Tender t = new Tender();
        t.setId(id);
        t.setTitle(title);
        t.setRefNo(refNo);
        t.setAuthority(authority);
        t.setSourceId(sourceId);
        t.setLocation(location);
        t.setSector(sector);
        t.setSource(source);
        t.setEstimatedCost(cost);
        t.setStatus(status);
        t.setSpecVerificationStatus(specStatus);
        t.setMisExecutive(misExec);
        t.setAssignedMisMemberSpec(specMember);
        t.setDueDate(dueDate);
        t.setPublishDate(publishDate);
        t.setScrapedAt("2026-09-19T10:00:00");
        t.setOriginalUrl("https://example.com/" + id);
        return t;
    }

    @BeforeEach
    void setUpTestData() {
        // Clear previous test-specific rows if any, then insert known fixture set
        Tender t1 = createTender("TEST-001", "Advanced CT Scanner System", "REF-CT-01", "AIIMS Hospital",
                "SRC-01", "New Delhi", "Healthcare", "GeM", 500000.0, "Issued", "Pending",
                "executive_john", "spec_alice", "2026-08-15 10:00:00", "2026-08-01");

        Tender t2 = createTender("TEST-002", "MRI Imaging Machine", "REF-MRI-02", "Safdarjung Hospital",
                "SRC-02", "New Delhi", "Healthcare", "Tender247", 1200000.0, "Participating", "Approved",
                "executive_john", "spec_bob", "2026-09-19 15:00:00", "2026-09-10");

        Tender t3 = createTender("TEST-003", "Hospital Ward Beds & Furniture", "REF-BED-03", "KEM Hospital",
                "SRC-03", "Mumbai", "Furniture", "GeM", 150000.0, "Awarded", "Approved",
                "executive_sarah", "spec_alice", "2026-09-21 11:00:00", "2026-09-15");

        Tender t4 = createTender("TEST-004", "IT Server Hardware & Networking", "REF-IT-04", "NIC Delhi",
                "SRC-04", "New Delhi", "Information Technology", "GeM", 800000.0, "Not Participating", "Rejected",
                "executive_sarah", "spec_carol", "2026-08-20 18:00:00", "2026-08-10");

        Tender t5 = createTender("TEST-005", "Surgical Instruments Sterile Kit", "REF-SURG-05", "Apollo Health",
                "SRC-05", "Bengaluru", "Healthcare", "GeM", 50000.0, "Filed", "Approved",
                "executive_mike", "spec_alice", "2026-09-25 12:00:00", "2026-09-18");

        tenderRepository.saveAll(List.of(t1, t2, t3, t4, t5));
    }

    @Test
    @DisplayName("1. Search across title")
    void testSearchAcrossTitle() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                "CT Scanner", null, null, null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-001")));
        assertFalse(results.stream().anyMatch(t -> t.getId().equals("TEST-003")));
    }

    @Test
    @DisplayName("2. Search across tender ID")
    void testSearchAcrossId() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                "TEST-002", null, null, null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertEquals(1, results.size());
        assertEquals("TEST-002", results.get(0).getId());
    }

    @Test
    @DisplayName("3. Search across reference number")
    void testSearchAcrossRefNo() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                "REF-BED-03", null, null, null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertEquals(1, results.size());
        assertEquals("TEST-003", results.get(0).getId());
    }

    @Test
    @DisplayName("4. Search across authority")
    void testSearchAcrossAuthority() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                "Safdarjung Hospital", null, null, null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-002")));
    }

    @Test
    @DisplayName("5. Search across source ID")
    void testSearchAcrossSourceId() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                "SRC-04", null, null, null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertEquals(1, results.size());
        assertEquals("TEST-004", results.get(0).getId());
    }

    @Test
    @DisplayName("6. Search with no matches returns empty list")
    void testSearchNoMatch() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                "NON_EXISTENT_KEYWORD_XYZ_999", null, null, null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("7. Status filter: Pending specification status")
    void testStatusPending() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, "Pending", null, null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-001")));
        assertFalse(results.stream().anyMatch(t -> t.getId().equals("TEST-002")));
    }

    @Test
    @DisplayName("8. Status filter: Won (Awarded)")
    void testStatusWon() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, "Won", null, null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-003")));
        assertFalse(results.stream().anyMatch(t -> t.getId().equals("TEST-001")));
    }

    @Test
    @DisplayName("9. Status filter: Submitted (Filed)")
    void testStatusSubmitted() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, "Submitted", null, null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-005")));
    }

    @Test
    @DisplayName("10. Status filter: Missed Deadline (due_date passed while Issued/Participating)")
    void testStatusMissedDeadline() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, "Missed Deadline", null, null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-001")));
    }

    @Test
    @DisplayName("11. Status filter: Missed Opportunity (due_date passed while Not Participating)")
    void testStatusMissedOpportunity() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, "Missed Opportunity", null, null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-004")));
    }

    @Test
    @DisplayName("12. Status filter: Due Today (T2)")
    void testStatusDueToday() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, "Due Today", null, null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-002")));
    }

    @Test
    @DisplayName("13. Status filter: Due in 3 Days (T2-3 days)")
    void testStatusDueIn3Days() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, "Due in 3 Days", null, null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-003")));
    }

    @Test
    @DisplayName("14. Location filter")
    void testLocationFilter() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, null, "Mumbai", null, null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-003")));
        assertFalse(results.stream().anyMatch(t -> t.getId().equals("TEST-001")));
    }

    @Test
    @DisplayName("15. Sector filter")
    void testSectorFilter() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, null, null, "Information Technology", null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertEquals(1, results.size());
        assertEquals("TEST-004", results.get(0).getId());
    }

    @Test
    @DisplayName("16. Source filter")
    void testSourceFilter() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, null, null, null, "Tender247", null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-002")));
        assertFalse(results.stream().anyMatch(t -> t.getId().equals("TEST-001")));
    }

    @Test
    @DisplayName("17. Min Cost filter only")
    void testMinCostFilter() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, null, null, null, null, 1000000.0, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-002")));
        assertFalse(results.stream().anyMatch(t -> t.getId().equals("TEST-001")));
    }

    @Test
    @DisplayName("18. Max Cost filter only")
    void testMaxCostFilter() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, null, null, null, null, null, 100000.0, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-005")));
        assertFalse(results.stream().anyMatch(t -> t.getId().equals("TEST-001")));
    }

    @Test
    @DisplayName("19. Min and Max Cost range filter")
    void testMinMaxCostRange() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, null, null, null, null, 400000.0, 900000.0, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-001")));
        assertTrue(results.stream().anyMatch(t -> t.getId().equals("TEST-004")));
        assertFalse(results.stream().anyMatch(t -> t.getId().equals("TEST-002")));
    }

    @Test
    @DisplayName("20. Combined filter: search + location + sector")
    void testCombinedSearchLocationSector() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                "Scanner", null, "New Delhi", "Healthcare", null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertEquals(1, results.size());
        assertEquals("TEST-001", results.get(0).getId());
    }

    @Test
    @DisplayName("21. Combined database query with sorting and pagination")
    void testDatabasePaginationAndSorting() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, null, null, "Healthcare", null, null, null, null, "Admin", "admin", "2026-09-19"
        );
        PageRequest pageRequest = PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "estimatedCost"));
        Page<Tender> page = tenderRepository.findAll(spec, pageRequest);

        assertEquals(2, page.getContent().size());
        assertTrue(page.getTotalElements() >= 3);
        assertTrue(page.getContent().get(0).getEstimatedCost() >= page.getContent().get(1).getEstimatedCost());
    }

    @Test
    @DisplayName("22. Role restriction: MIS Executive only sees assigned tenders")
    void testMisExecutiveRoleRestriction() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, null, null, null, null, null, null, null, "MIS Executive", "executive_john", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().allMatch(t -> "executive_john".equalsIgnoreCase(t.getMisExecutive())));
    }

    @Test
    @DisplayName("23. Role restriction: Specification Team only sees assigned tenders")
    void testSpecTeamRoleRestriction() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, null, null, null, null, null, null, null, "Specification Team", "spec_alice", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().allMatch(t -> "spec_alice".equalsIgnoreCase(t.getAssignedMisMemberSpec())));
    }

    @Test
    @DisplayName("24. MIS Executive filter parameter by Admin")
    void testMisExecutiveParameter() {
        Specification<Tender> spec = TenderSpecification.filterTenders(
                null, null, null, null, null, null, null, "executive_sarah", "Admin", "admin", "2026-09-19"
        );
        List<Tender> results = tenderRepository.findAll(spec);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().allMatch(t -> "executive_sarah".equalsIgnoreCase(t.getMisExecutive())));
    }
}
