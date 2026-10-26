package com.tenderpocket.controllers;

import com.tenderpocket.models.Tender;
import com.tenderpocket.repositories.TenderRepository;
import com.tenderpocket.repositories.TenderRepository.TenderAnalyticsProjection;
import com.tenderpocket.services.TenderStatusResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class AnalyticsControllerTest {

    @Mock
    private TenderRepository tenderRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query nativeQuery;

    @InjectMocks
    private AnalyticsController analyticsController;

    private static class MockProjection implements TenderAnalyticsProjection {
        private String id;
        private String status;
        private Double estimatedCost;
        private String dueDate;
        private String scrapedAt;
        private String publishDate;
        private String assignedAt;
        private String entryDate;
        private String misExecutive;
        private String currentStage;
        private Double tpcPurchasePrice;
        private String specVerificationStatus;
        private String assignedMisMemberSpec;
        private String pricingRoute;

        MockProjection(String id, String status, Double cost, String dueDate, String scrapedAt,
                       String publishDate, String assignedAt, String entryDate, String misExecutive,
                       String currentStage, Double tpcPrice, String specStatus, String assignedSpec) {
            this.id = id;
            this.status = status;
            this.estimatedCost = cost;
            this.dueDate = dueDate;
            this.scrapedAt = scrapedAt;
            this.publishDate = publishDate;
            this.assignedAt = assignedAt;
            this.entryDate = entryDate;
            this.misExecutive = misExecutive;
            this.currentStage = currentStage;
            this.tpcPurchasePrice = tpcPrice;
            this.specVerificationStatus = specStatus;
            this.assignedMisMemberSpec = assignedSpec;
        }

        @Override public String getId() { return id; }
        @Override public String getStatus() { return status; }
        @Override public Double getEstimatedCost() { return estimatedCost; }
        @Override public String getDueDate() { return dueDate; }
        @Override public String getScrapedAt() { return scrapedAt; }
        @Override public String getPublishDate() { return publishDate; }
        @Override public String getAssignedAt() { return assignedAt; }
        @Override public String getEntryDate() { return entryDate; }
        @Override public String getMisExecutive() { return misExecutive; }
        @Override public String getCurrentStage() { return currentStage; }
        @Override public Double getTpcPurchasePrice() { return tpcPurchasePrice; }
        @Override public Double getMisFinalPrice() { return null; }
        @Override public String getSpecVerificationStatus() { return specVerificationStatus; }
        @Override public String getAssignedMisMemberSpec() { return assignedMisMemberSpec; }
        @Override public String getPricingRoute() { return pricingRoute; }
    }

    private MockProjection proj1;
    private MockProjection proj2;
    private MockProjection proj3;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        SecurityContextHolder.clearContext();

        proj1 = new MockProjection("T1", "Participating", 1500000.0, "2026-11-01 10:00:00",
                "2026-10-15 10:00:00", "2026-10-14", "2026-10-16", "2026-10-15",
                "exec1", "SPEC_CLEARANCE", 1200000.0, "Approved", "clearance");

        proj2 = new MockProjection("T2", "Submitted", 3500000.0, "2026-11-15 10:00:00",
                "2026-10-18 10:00:00", "2026-10-17", "2026-10-19", "2026-10-18",
                "exec2", "TPC_PRICING", 0.0, "Pending", "spec_member");

        proj3 = new MockProjection("T3", "Issued", 5000000.0, "2026-11-20 10:00:00",
                java.time.LocalDate.now().toString() + " 10:00:00", java.time.LocalDate.now().toString(), null, java.time.LocalDate.now().toString(),
                null, "NEW", null, "None", null);

        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.getResultList()).thenReturn(Collections.emptyList());
    }

    private void authenticate(String username, String role) {
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role.toUpperCase().replace(" ", "_"));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                username, null, Collections.singletonList(authority));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void testAdminAnalyticsUsesFindAllForAnalytics() {
        authenticate("admin", "Admin");
        when(tenderRepository.findAllForAnalytics()).thenReturn(List.of(proj1, proj2, proj3));

        ResponseEntity<?> response = analyticsController.getAnalytics("Admin", "admin");
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));

        Map<?, ?> metrics = (Map<?, ?>) body.get("metrics");
        assertEquals(3, metrics.get("totalTenders"));
        assertEquals(1, metrics.get("participatingCount"));
        assertEquals(1, metrics.get("filedCount"));
        assertEquals(1, metrics.get("issuedCount"));

        verify(tenderRepository, times(1)).findAllForAnalytics();
        verify(tenderRepository, never()).findForAnalyticsByExecutive(anyString());
    }

    @Test
    void testExecutiveAnalyticsUsesFindForAnalyticsByExecutive() {
        authenticate("exec1", "Tender Executive");
        when(tenderRepository.findForAnalyticsByExecutive("exec1")).thenReturn(List.of(proj1));

        ResponseEntity<?> response = analyticsController.getAnalytics("Tender Executive", "exec1");
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));

        Map<?, ?> metrics = (Map<?, ?>) body.get("metrics");
        assertEquals(1, metrics.get("totalTenders"));
        assertEquals(1, metrics.get("participatingCount"));

        verify(tenderRepository, times(1)).findForAnalyticsByExecutive("exec1");
        verify(tenderRepository, never()).findAllForAnalytics();
    }

    @Test
    void testClearanceTeamAnalyticsUsesFindForAnalyticsBySpecMember() {
        authenticate("clearance", "Clearance Team");
        when(tenderRepository.findForAnalyticsBySpecMember("clearance")).thenReturn(List.of(proj1));

        ResponseEntity<?> response = analyticsController.getAnalytics("Clearance Team", "clearance");
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));

        Map<?, ?> metrics = (Map<?, ?>) body.get("metrics");
        assertEquals(1, metrics.get("totalTenders"));
        assertEquals(1, metrics.get("specApprovedCount"));

        verify(tenderRepository, times(1)).findForAnalyticsBySpecMember("clearance");
    }

    @Test
    void testExecutivePerformanceCalculations() {
        authenticate("exec1", "Tender Executive");
        when(tenderRepository.findForAnalyticsByExecutive("exec1")).thenReturn(List.of(proj1));

        ResponseEntity<?> response = analyticsController.getExecutivePerformance("exec1", "Tender Executive");
        assertEquals(HttpStatus.OK, response.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));
        assertEquals("exec1", body.get("username"));

        Map<?, ?> dist = (Map<?, ?>) body.get("statusDistribution");
        assertEquals(1, dist.get("Participating"));

        verify(tenderRepository, times(1)).findForAnalyticsByExecutive("exec1");
    }

    @Test
    void testTenderStatusResolverWithProjection() {
        String todayIST = "2026-10-22";

        // Active participating
        assertEquals("Participating", TenderStatusResolver.resolve(proj1, todayIST));

        // Submitted
        assertEquals("Submitted", TenderStatusResolver.resolve(proj2, todayIST));

        // Overdue deadline
        MockProjection overdue = new MockProjection("T4", "Participating", 100.0, "2026-10-01 10:00:00",
                "2026-09-01 10:00:00", "2026-09-01", "2026-09-02", "2026-09-02",
                "exec1", "SPEC_CLEARANCE", null, "None", null);
        assertEquals("Missed Deadline", TenderStatusResolver.resolve(overdue, todayIST));
    }
}
