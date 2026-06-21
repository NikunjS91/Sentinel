package com.sentinel.incident;

import com.sentinel.api.IncidentFilter;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class IncidentSpecifications {
    private IncidentSpecifications() {}

    public static Specification<Incident> matching(IncidentFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.state() != null && !filter.state().isEmpty()) {
                List<IncidentState> states = filter.state().stream()
                    .map(IncidentSpecifications::parseStateOrThrow)
                    .toList();
                predicates.add(root.get("state").in(states));
            }

            if (filter.service() != null && !filter.service().isBlank()) {
                predicates.add(cb.equal(root.get("source"), filter.service()));
            }

            if (filter.before() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), filter.before()));
            }

            Join<Incident, IncidentReport> reportJoin = null;
            if (filter.hasReportJoin()) {
                reportJoin = root.join("report", JoinType.LEFT);
            }

            if (filter.decision() != null) {
                if ("undecided".equals(filter.decision())) {
                    predicates.add(cb.isNull(reportJoin.get("humanDecision")));
                } else {
                    predicates.add(cb.equal(
                        reportJoin.get("humanDecision"),
                        filter.decision().toUpperCase()));
                }
            }

            if (filter.q() != null && !filter.q().isBlank()) {
                String pattern = "%" + filter.q().toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(reportJoin.get("summary")), pattern),
                    cb.like(cb.lower(reportJoin.get("rootCause")), pattern),
                    cb.like(cb.lower(reportJoin.get("recommendedAction")), pattern)
                ));
            }

            assert query != null;
            query.orderBy(cb.desc(root.get("createdAt")));

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static IncidentState parseStateOrThrow(String s) {
        try {
            return IncidentState.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown state: " + s);
        }
    }
}
