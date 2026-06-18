package com.sentinel.kb;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
public class KnowledgeBaseSeeder {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseSeeder.class);

    private final DataSource dataSource;
    private final boolean enabled;

    public KnowledgeBaseSeeder(DataSource dataSource,
                               @Value("${sentinel.kb.seed-on-startup:true}") boolean enabled) {
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    @PostConstruct
    public void seedIfEmpty() throws Exception {
        if (!enabled) {
            log.debug("KB seeder disabled — skipping");
            return;
        }
        var resource = new ClassPathResource("seed/knowledge_base.sql");
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, resource);
        }
        log.info("Knowledge-base seed script executed (ON CONFLICT DO NOTHING — safe to re-run)");
    }
}
