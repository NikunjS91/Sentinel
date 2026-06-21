package com.sentinel.kb;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface KbRunbookRepository extends JpaRepository<KbRunbook, UUID> {

    @Query(value = """
            SELECT * FROM knowledge_base.kb_runbooks
            WHERE to_tsvector('english', title || ' ' || body) @@ plainto_tsquery('english', :q)
               OR :q = ANY(tags)
            ORDER BY ts_rank(to_tsvector('english', title || ' ' || body),
                             plainto_tsquery('english', :q)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<KbRunbook> search(@Param("q") String q, @Param("limit") int limit);
}
