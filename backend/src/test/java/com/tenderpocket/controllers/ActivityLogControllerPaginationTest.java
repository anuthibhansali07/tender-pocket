package com.tenderpocket.controllers;

import com.tenderpocket.models.ActivityLog;
import com.tenderpocket.repositories.ActivityLogRepository;
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
public class ActivityLogControllerPaginationTest {

    @Mock
    private ActivityLogRepository activityLogRepository;

    @InjectMocks
    private ActivityLogController activityLogController;

    private List<ActivityLog> createSampleLogs(int count) {
        List<ActivityLog> logs = new ArrayList<>();
        for (long i = 1; i <= count; i++) {
            ActivityLog log = new ActivityLog(
                    "user" + i,
                    "Admin",
                    "create_tender",
                    "TND-" + i,
                    "2026-09-19T10:0" + (i % 10),
                    "Created tender TND-" + i
            );
            log.setId(i);
            logs.add(log);
        }
        return logs;
    }

    @Test
    @DisplayName("1. Audit Trail Page 0 returns correct records and pagination metadata")
    void testAuditPage0ReturnsCorrectRecords() {
        List<ActivityLog> content = createSampleLogs(20);
        Page<ActivityLog> page = new PageImpl<>(content, PageRequest.of(0, 20), 45);
        when(activityLogRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        ResponseEntity<?> response = activityLogController.getActivityLogs(
                "Admin", 0, 20, null, null, null, null, null, null, null, null, null
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertTrue((Boolean) body.get("success"));
        assertEquals(0, body.get("currentPage"));
        assertEquals(20, body.get("pageSize"));
        assertEquals(45L, body.get("totalElements"));
        assertEquals(3, body.get("totalPages"));
        assertTrue((Boolean) body.get("first"));
        assertFalse((Boolean) body.get("last"));
        assertTrue((Boolean) body.get("hasNext"));
        assertFalse((Boolean) body.get("hasPrevious"));

        List<?> logs = (List<?>) body.get("logs");
        assertEquals(20, logs.size());
    }

    @Test
    @DisplayName("2. Audit Trail Page 1 returns the next records")
    void testAuditPage1ReturnsNextRecords() {
        List<ActivityLog> content = createSampleLogs(20);
        Page<ActivityLog> page = new PageImpl<>(content, PageRequest.of(1, 20), 45);
        when(activityLogRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        ResponseEntity<?> response = activityLogController.getActivityLogs(
                "Admin", 1, 20, null, null, null, null, null, null, null, null, null
        );

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        assertEquals(1, body.get("currentPage"));
        assertFalse((Boolean) body.get("first"));
        assertTrue((Boolean) body.get("hasPrevious"));
        assertTrue((Boolean) body.get("hasNext"));
    }

    @Test
    @DisplayName("3. Page size parameter is respected")
    void testAuditPageSizeRespected() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        Page<ActivityLog> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(activityLogRepository.findAll(any(Specification.class), captor.capture())).thenReturn(emptyPage);

        activityLogController.getActivityLogs(
                "Admin", 0, 10, null, null, null, null, null, null, null, null, null
        );

        assertEquals(10, captor.getValue().getPageSize());
    }

    @Test
    @DisplayName("4. Default sort is timestamp descending")
    void testDefaultSortTimestampDesc() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        Page<ActivityLog> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(activityLogRepository.findAll(any(Specification.class), captor.capture())).thenReturn(emptyPage);

        activityLogController.getActivityLogs(
                "Admin", 0, 20, null, null, null, null, null, null, null, null, null
        );

        Sort.Order order = captor.getValue().getSort().getOrderFor("timestamp");
        assertNotNull(order);
        assertTrue(order.isDescending());
    }

    @Test
    @DisplayName("5. Action filter is passed to specification")
    void testActionFilter() {
        ArgumentCaptor<Specification<ActivityLog>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        Page<ActivityLog> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(activityLogRepository.findAll(specCaptor.capture(), any(Pageable.class))).thenReturn(emptyPage);

        activityLogController.getActivityLogs(
                "Admin", 0, 20, "export_csv", null, null, null, null, null, null, null, null
        );

        assertNotNull(specCaptor.getValue());
    }

    @Test
    @DisplayName("6. Empty result produces valid pagination response")
    void testEmptyResult() {
        Page<ActivityLog> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(activityLogRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(emptyPage);

        ResponseEntity<?> response = activityLogController.getActivityLogs(
                "Admin", 0, 20, "non_existent_action", null, null, null, null, null, null, null, null
        );

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertNotNull(body);
        List<?> logs = (List<?>) body.get("logs");
        assertTrue(logs.isEmpty());
        assertEquals(0L, body.get("totalElements"));
        assertEquals(0, body.get("totalPages"));
    }
}
