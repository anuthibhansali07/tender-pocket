package com.tenderpocket.repositories.specifications;

import com.tenderpocket.models.Tender;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class TenderSpecification {

    public static Specification<Tender> filterTenders(
            String search,
            String status,
            String location,
            String sector,
            String source,
            Double minCost,
            Double maxCost,
            String misExecutive,
            String userRole,
            String username,
            String todayIST) {

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Role-based restrictions
            if ("MIS Executive".equalsIgnoreCase(userRole) || "Tender Executive".equalsIgnoreCase(userRole)) {
                if (username != null && !username.trim().isEmpty()) {
                    predicates.add(cb.equal(cb.lower(root.get("misExecutive")), username.trim().toLowerCase()));
                }
            } else if ("Specification Team".equalsIgnoreCase(userRole)) {
                if (username != null && !username.trim().isEmpty()) {
                    predicates.add(cb.equal(cb.lower(root.get("assignedMisMemberSpec")), username.trim().toLowerCase()));
                }
            }

            // 2. Filter by specific MIS Executive parameter
            if (misExecutive != null && !misExecutive.trim().isEmpty()) {
                predicates.add(cb.equal(cb.lower(root.get("misExecutive")), misExecutive.trim().toLowerCase()));
            }

            // 3. Search query across title, id, ref_no, authority, and source_id
            if (search != null && !search.trim().isEmpty()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                Predicate searchPred = cb.or(
                        cb.like(cb.lower(root.get("title")), pattern),
                        cb.like(cb.lower(root.get("id")), pattern),
                        cb.like(cb.lower(root.get("refNo")), pattern),
                        cb.like(cb.lower(root.get("authority")), pattern),
                        cb.like(cb.lower(root.get("sourceId")), pattern)
                );
                predicates.add(searchPred);
            }

            // 4. Location filter
            if (location != null && !location.trim().isEmpty()) {
                predicates.add(cb.equal(cb.lower(root.get("location")), location.trim().toLowerCase()));
            }

            // 5. Sector filter
            if (sector != null && !sector.trim().isEmpty()) {
                predicates.add(cb.equal(cb.lower(root.get("sector")), sector.trim().toLowerCase()));
            }

            // 6. Source filter
            if (source != null && !source.trim().isEmpty()) {
                predicates.add(cb.equal(cb.lower(root.get("source")), source.trim().toLowerCase()));
            }

            // 7. Cost range filters
            if (minCost != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("estimatedCost"), minCost));
            }
            if (maxCost != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("estimatedCost"), maxCost));
            }

            // 8. Status filter
            if (status != null && !status.trim().isEmpty()) {
                String trimmedStatus = status.trim();

                LocalDate todayDate;
                try {
                    todayDate = LocalDate.parse(todayIST);
                } catch (Exception e) {
                    todayDate = LocalDate.now();
                }
                String tomorrowDate = todayDate.plusDays(1).toString();
                String threeDaysLater = todayDate.plusDays(3).toString();
                String lapsedCutoffDate = todayDate.minusDays(3).toString();

                Expression<String> dueDateStr = root.get("dueDate");
                Expression<String> dueDateSub = cb.substring(dueDateStr, 1, 10);
                Expression<String> publishDateStr = root.get("publishDate");
                Expression<String> publishDateSub = cb.substring(publishDateStr, 1, 10);

                if ("Pending".equalsIgnoreCase(trimmedStatus) || "Approved".equalsIgnoreCase(trimmedStatus) || "Rejected".equalsIgnoreCase(trimmedStatus)) {
                    predicates.add(cb.equal(cb.lower(root.get("specVerificationStatus")), trimmedStatus.toLowerCase()));
                } else if ("Won".equalsIgnoreCase(trimmedStatus)) {
                    predicates.add(cb.equal(cb.lower(root.get("status")), "awarded"));
                } else if ("Lost".equalsIgnoreCase(trimmedStatus)) {
                    predicates.add(cb.equal(cb.lower(root.get("status")), "not awarded"));
                } else if ("Submitted".equalsIgnoreCase(trimmedStatus)) {
                    predicates.add(cb.equal(cb.lower(root.get("status")), "filed"));
                } else if ("Participating".equalsIgnoreCase(trimmedStatus)) {
                    predicates.add(cb.and(
                            cb.equal(cb.lower(root.get("status")), "participating"),
                            cb.or(cb.isNull(dueDateStr), cb.greaterThanOrEqualTo(dueDateSub, todayIST))
                    ));
                } else if ("Not Participating".equalsIgnoreCase(trimmedStatus)) {
                    predicates.add(cb.and(
                            cb.equal(cb.lower(root.get("status")), "not participating"),
                            cb.or(cb.isNull(dueDateStr), cb.greaterThanOrEqualTo(dueDateSub, todayIST))
                    ));
                } else if ("Missed Deadline".equalsIgnoreCase(trimmedStatus)) {
                    predicates.add(cb.and(
                            cb.isNotNull(dueDateStr),
                            cb.lessThan(dueDateSub, todayIST),
                            cb.or(
                                    cb.isNull(root.get("status")),
                                    cb.equal(cb.lower(root.get("status")), "issued"),
                                    cb.equal(cb.lower(root.get("status")), "participating")
                            )
                    ));
                } else if ("Missed Opportunity".equalsIgnoreCase(trimmedStatus)) {
                    predicates.add(cb.and(
                            cb.isNotNull(dueDateStr),
                            cb.lessThan(dueDateSub, todayIST),
                            cb.equal(cb.lower(root.get("status")), "not participating")
                    ));
                } else if ("Missed".equalsIgnoreCase(trimmedStatus)) {
                    Predicate missedOpp = cb.and(
                            cb.isNotNull(dueDateStr),
                            cb.lessThan(dueDateSub, todayIST),
                            cb.equal(cb.lower(root.get("status")), "not participating")
                    );
                    Predicate missedDead = cb.and(
                            cb.isNotNull(dueDateStr),
                            cb.lessThan(dueDateSub, todayIST),
                            cb.or(
                                    cb.isNull(root.get("status")),
                                    cb.equal(cb.lower(root.get("status")), "issued"),
                                    cb.equal(cb.lower(root.get("status")), "participating")
                            )
                    );
                    Predicate lapsed = cb.and(
                            cb.or(cb.isNull(root.get("status")), cb.equal(cb.lower(root.get("status")), "issued")),
                            cb.isNotNull(publishDateStr),
                            cb.notEqual(publishDateStr, "N/A"),
                            cb.lessThan(publishDateSub, lapsedCutoffDate),
                            cb.or(cb.isNull(dueDateStr), cb.greaterThanOrEqualTo(dueDateSub, todayIST))
                    );
                    predicates.add(cb.or(missedOpp, missedDead, lapsed));
                } else if ("T2".equalsIgnoreCase(trimmedStatus) || "Due Today".equalsIgnoreCase(trimmedStatus)) {
                    predicates.add(cb.and(
                            cb.isNotNull(dueDateStr),
                            cb.equal(dueDateSub, todayIST)
                    ));
                } else if ("T2-3 days".equalsIgnoreCase(trimmedStatus) || "Due in 3 Days".equalsIgnoreCase(trimmedStatus)) {
                    predicates.add(cb.and(
                            cb.isNotNull(dueDateStr),
                            cb.greaterThanOrEqualTo(dueDateSub, tomorrowDate),
                            cb.lessThanOrEqualTo(dueDateSub, threeDaysLater)
                    ));
                } else if ("Lapsed".equalsIgnoreCase(trimmedStatus)) {
                    predicates.add(cb.and(
                            cb.or(cb.isNull(root.get("status")), cb.equal(cb.lower(root.get("status")), "issued")),
                            cb.isNotNull(publishDateStr),
                            cb.notEqual(publishDateStr, "N/A"),
                            cb.lessThan(publishDateSub, lapsedCutoffDate),
                            cb.or(cb.isNull(dueDateStr), cb.greaterThanOrEqualTo(dueDateSub, todayIST))
                    ));
                } else if ("New".equalsIgnoreCase(trimmedStatus)) {
                    predicates.add(cb.and(
                            cb.or(cb.isNull(root.get("status")), cb.equal(cb.lower(root.get("status")), "issued")),
                            cb.or(cb.isNull(dueDateStr), cb.greaterThanOrEqualTo(dueDateSub, todayIST)),
                            cb.or(
                                    cb.isNull(publishDateStr),
                                    cb.equal(publishDateStr, "N/A"),
                                    cb.greaterThanOrEqualTo(publishDateSub, lapsedCutoffDate)
                            )
                    ));
                } else {
                    predicates.add(cb.equal(cb.lower(root.get("status")), trimmedStatus.toLowerCase()));
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
