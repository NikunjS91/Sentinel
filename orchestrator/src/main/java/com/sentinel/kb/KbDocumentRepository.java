package com.sentinel.kb;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface KbDocumentRepository extends JpaRepository<KbDocument, UUID> {

    @Query(value = """
            SELECT id, source_type, title, body, metadata, created_at,
                   embedding <=> CAST(:embedding AS vector) AS distance
            FROM knowledge_base.kb_documents
            WHERE (:sourceType IS NULL OR source_type = :sourceType)
            ORDER BY embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findSimilar(@Param("embedding") String embedding,
                               @Param("sourceType") String sourceType,
                               @Param("limit") int limit);
}
