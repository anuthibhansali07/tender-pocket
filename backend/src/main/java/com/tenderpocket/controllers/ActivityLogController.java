package com.tenderpocket.controllers;

import com.tenderpocket.models.ActivityLog;
import com.tenderpocket.repositories.ActivityLogRepository;
import com.tenderpocket.repositories.specifications.ActivityLogSpecification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/activity-logs")
public class ActivityLogController {

    @Autowired
    private ActivityLogRepository activityLogRepository;

    @GetMapping
    public ResponseEntity<?> getActivityLogs(
            @RequestHeader(value = "x-user-role", required = false, defaultValue = "Admin") String userRole,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "tender_id", required = false) String tenderId1,
            @RequestParam(value = "tenderId", required = false) String tenderId2,
            @RequestParam(value = "sortBy", required = false) String sortBy1,
            @RequestParam(value = "sort_by", required = false) String sortBy2,
            @RequestParam(value = "order", required = false) String order1,
            @RequestParam(value = "sort_dir", required = false) String order2) {

        String tenderId = tenderId1 != null ? tenderId1 : tenderId2;
        String sortBy = sortBy1 != null ? sortBy1 : sortBy2;
        String order = order1 != null ? order1 : order2;

        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (size > 500) size = 500;

        Sort sort = resolveLogSort(sortBy, order);
        Pageable pageable = PageRequest.of(page, size, sort);

        Specification<ActivityLog> spec = ActivityLogSpecification.filterLogs(action, role, tenderId, search);

        Page<ActivityLog> logPage = activityLogRepository.findAll(spec, pageable);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("logs", logPage.getContent());
        response.put("currentPage", logPage.getNumber());
        response.put("pageSize", logPage.getSize());
        response.put("totalElements", logPage.getTotalElements());
        response.put("totalPages", logPage.getTotalPages());
        response.put("first", logPage.isFirst());
        response.put("last", logPage.isLast());
        response.put("hasNext", logPage.hasNext());
        response.put("hasPrevious", logPage.hasPrevious());

        return ResponseEntity.ok(response);
    }

    private Sort resolveLogSort(String sortBy, String order) {
        String property = "timestamp";
        if (sortBy != null && !sortBy.trim().isEmpty()) {
            String lower = sortBy.trim().toLowerCase();
            switch (lower) {
                case "action":
                    property = "action";
                    break;
                case "role":
                    property = "role";
                    break;
                case "username":
                    property = "username";
                    break;
                case "id":
                    property = "id";
                    break;
                case "timestamp":
                default:
                    property = "timestamp";
                    break;
            }
        }

        Sort.Direction direction = "asc".equalsIgnoreCase(order) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, property).and(Sort.by(Sort.Direction.DESC, "id"));
    }
}
