package com.tenderpocket.repositories.specifications;

import com.tenderpocket.models.ActivityLog;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public class ActivityLogSpecification {

    public static Specification<ActivityLog> filterLogs(
            String action,
            String role,
            String tenderId,
            String search) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (action != null && !action.trim().isEmpty()) {
                predicates.add(cb.equal(cb.lower(root.get("action")), action.trim().toLowerCase()));
            }

            if (role != null && !role.trim().isEmpty()) {
                predicates.add(cb.equal(cb.lower(root.get("role")), role.trim().toLowerCase()));
            }

            if (tenderId != null && !tenderId.trim().isEmpty()) {
                predicates.add(cb.equal(root.get("tenderId"), tenderId.trim()));
            }

            if (search != null && !search.trim().isEmpty()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                Predicate searchPred = cb.or(
                        cb.like(cb.lower(root.get("username")), pattern),
                        cb.like(cb.lower(root.get("role")), pattern),
                        cb.like(cb.lower(root.get("action")), pattern),
                        cb.like(cb.lower(root.get("details")), pattern),
                        cb.like(cb.lower(root.get("tenderId")), pattern)
                );
                predicates.add(searchPred);
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
