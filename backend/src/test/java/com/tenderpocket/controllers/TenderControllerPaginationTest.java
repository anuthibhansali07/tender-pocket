package com.tenderpocket.controllers;

import com.tenderpocket.models.Tender;
import com.tenderpocket.repositories.ActivityLogRepository;
import com.tenderpocket.repositories.StatusHistoryRepository;
import com.tenderpocket.repositories.TenderRepository;
import com.tenderpocket.repositories.TenderWorkflowCommentRepository;
import com.tenderpocket.services.DocumentGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class TenderControllerPaginationTest {

    @Mock
    private TenderRepository tenderRepository;

    @Mock
    private TenderWorkflowCommentRepository commentRepository;

    @Mock
    private ActivityLogRepository activityLogRepository;

    @Mock
    private StatusHistoryRepository statusHistoryRepository;

    @Mock
    private DocumentGeneratorService documentGeneratorService;

    @InjectMocks
    private TenderController tenderController;

    private List<Tender> createSampleTenders(int count) {
        List<Tender> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Tender t = new Tender();
            t.setId("TND-" + i);
            t.setTitle("Medical Supply Tender " + i);
            t.setRefNo("REF-" + i);
            t.setAuthority("Hospital Authority " + i);
            t.setEstimatedCost(10000.0 * i);
            t.setLocation("Delhi");
            t.setSector("Healthcare");
            t.setSource("GeM");
            t.setStatus("Issued");
            t.setScrapedAt("2026-09-01T10:00:0" + (i % 10));
            list.add(t);
        }
        return list;
    }

    @BeforeEach
    void setUp() {
        when(tenderRepository.findUniqueLocations()).thenReturn(List.of("Delhi", "Mumbai"));
        when(tenderRepository.findUniqueSectors()).thenReturn(List.of("Healthcare", "IT"));
    }

    @Test
    @DisplayName("1. Page 0 returns correct records and metadata")
    void testPage0ReturnsCorrectRecords() {
        List<Tender> content = createSampleTenders(20);
        Page<Tender> page = new PageImpl<>(content, PageRequest.of(0, 20), 100);
        when(tenderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        ResponseEntity<?> response = tenderController.getTenders(
                "Admin", "admin", 0, 20, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));
        assertEquals(0, body.get("currentPage"));
        assertEquals(20, body.get("pageSize"));
        assertEquals(100L, body.get("totalElements"));
        assertEquals(5, body.get("totalPages"));
        assertTrue((Boolean) body.get("first"));
        assertFalse((Boolean) body.get("last"));
        assertTrue((Boolean) body.get("hasNext"));
        assertFalse((Boolean) body.get("hasPrevious"));

        List<?> tenders = (List<?>) body.get("tenders");
        assertEquals(20, tenders.size());
    }

    @Test
    @DisplayName("2. Page 1 returns the next records and metadata")
    void testPage1ReturnsNextRecords() {
        List<Tender> content = createSampleTenders(20);
        Page<Tender> page = new PageImpl<>(content, PageRequest.of(1, 20), 100);
        when(tenderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        ResponseEntity<?> response = tenderController.getTenders(
                "Admin", "admin", 1, 20, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null
        );

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(1, body.get("currentPage"));
        assertFalse((Boolean) body.get("first"));
        assertTrue((Boolean) body.get("hasPrevious"));
        assertTrue((Boolean) body.get("hasNext"));
    }

    @Test
    @DisplayName("3. Requested page size 5 is respected")
    void testPageSize5Respected() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        Page<Tender> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 5), 0);
        when(tenderRepository.findAll(any(Specification.class), captor.capture())).thenReturn(emptyPage);

        tenderController.getTenders(
                "Admin", "admin", 0, 5, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null
        );

        assertEquals(5, captor.getValue().getPageSize());
    }

    @Test
    @DisplayName("4. Requested page size 10 is respected")
    void testPageSize10Respected() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        Page<Tender> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(tenderRepository.findAll(any(Specification.class), captor.capture())).thenReturn(emptyPage);

        tenderController.getTenders(
                "Admin", "admin", 0, 10, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null
        );

        assertEquals(10, captor.getValue().getPageSize());
    }

    @Test
    @DisplayName("5. Last page behaves correctly with last=true and hasNext=false")
    void testLastPage() {
        List<Tender> lastPageContent = createSampleTenders(5);
        Page<Tender> lastPage = new PageImpl<>(lastPageContent, PageRequest.of(4, 20), 85);
        when(tenderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(lastPage);

        ResponseEntity<?> response = tenderController.getTenders(
                "Admin", "admin", 4, 20, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null
        );

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(4, body.get("currentPage"));
        assertEquals(5, body.get("totalPages"));
        assertTrue((Boolean) body.get("last"));
        assertFalse((Boolean) body.get("hasNext"));
        assertTrue((Boolean) body.get("hasPrevious"));
    }

    @Test
    @DisplayName("6. Out-of-range empty page behaves correctly")
    void testEmptyPageOutOfRange() {
        Page<Tender> emptyPage = new PageImpl<>(List.of(), PageRequest.of(10, 20), 50);
        when(tenderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(emptyPage);

        ResponseEntity<?> response = tenderController.getTenders(
                "Admin", "admin", 10, 20, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null
        );

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        List<?> list = (List<?>) body.get("tenders");
        assertTrue(list.isEmpty());
        assertEquals(10, body.get("currentPage"));
        assertEquals(50L, body.get("totalElements"));
    }

    @Test
    @DisplayName("7. Negative page index is clamped to 0")
    void testNegativePageClampedToZero() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        Page<Tender> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(tenderRepository.findAll(any(Specification.class), captor.capture())).thenReturn(emptyPage);

        tenderController.getTenders(
                "Admin", "admin", -5, 20, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null
        );

        assertEquals(0, captor.getValue().getPageNumber());
    }

    @Test
    @DisplayName("8. Excessive page size is clamped to 500")
    void testExcessiveSizeClamped() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        Page<Tender> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 500), 0);
        when(tenderRepository.findAll(any(Specification.class), captor.capture())).thenReturn(emptyPage);

        tenderController.getTenders(
                "Admin", "admin", 0, 9999, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null
        );

        assertEquals(500, captor.getValue().getPageSize());
    }

    @Test
    @DisplayName("9. Sorting order ascending and descending on allowed fields")
    void testSortingParameters() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        Page<Tender> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(tenderRepository.findAll(any(Specification.class), captor.capture())).thenReturn(emptyPage);

        // Ascending on due_date
        tenderController.getTenders(
                "Admin", "admin", 0, 20, null, null, null, null, null,
                null, null, null, null, null, "due_date", null, "asc", null
        );

        Sort.Order order = captor.getValue().getSort().getOrderFor("dueDate");
        assertNotNull(order);
        assertTrue(order.isAscending());

        // Descending on estimated_cost via camelCase alias
        tenderController.getTenders(
                "Admin", "admin", 0, 20, null, null, null, null, null,
                null, null, null, null, null, null, "estimatedCost", null, "desc"
        );

        Sort.Order costOrder = captor.getValue().getSort().getOrderFor("estimatedCost");
        assertNotNull(costOrder);
        assertTrue(costOrder.isDescending());
    }

    @Test
    @DisplayName("10. Invalid sort field falls back safely to scrapedAt")
    void testInvalidSortFieldFallback() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        Page<Tender> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(tenderRepository.findAll(any(Specification.class), captor.capture())).thenReturn(emptyPage);

        tenderController.getTenders(
                "Admin", "admin", 0, 20, null, null, null, null, null,
                null, null, null, null, null, "NON_EXISTENT_COLUMN; DROP TABLE", null, "desc", null
        );

        Sort.Order defaultOrder = captor.getValue().getSort().getOrderFor("scrapedAt");
        assertNotNull(defaultOrder);
    }

    @Test
    @DisplayName("11. Min and Max cost alias parameters are parsed correctly")
    void testCostAliasParameters() {
        Page<Tender> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(tenderRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(emptyPage);

        // snake_case min_cost and max_cost
        ResponseEntity<?> res1 = tenderController.getTenders(
                "Admin", "admin", 0, 20, null, null, null, null, null,
                1000.0, null, 5000.0, null, null, null, null, null, null
        );
        assertEquals(HttpStatus.OK, res1.getStatusCode());

        // camelCase minCost and maxCost
        ResponseEntity<?> res2 = tenderController.getTenders(
                "Admin", "admin", 0, 20, null, null, null, null, null,
                null, 2000.0, null, 8000.0, null, null, null, null, null
        );
        assertEquals(HttpStatus.OK, res2.getStatusCode());
    }
}
