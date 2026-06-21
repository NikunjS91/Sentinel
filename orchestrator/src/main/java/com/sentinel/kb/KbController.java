package com.sentinel.kb;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/kb")
public class KbController {

    private final KbDocumentRepository docs;
    private final KbLinkRepository links;
    private final KbRunbookRepository runbooks;

    public KbController(KbDocumentRepository docs,
                        KbLinkRepository links,
                        KbRunbookRepository runbooks) {
        this.docs = docs;
        this.links = links;
        this.runbooks = runbooks;
    }

    @GetMapping("/search")
    public List<Map<String, Object>> searchGet(
            @RequestParam String embedding,
            @RequestParam(required = false) String source_type,
            @RequestParam(defaultValue = "5") int limit) {
        return search(embedding, source_type, limit);
    }

    @PostMapping("/search")
    public List<Map<String, Object>> searchPost(@RequestBody SearchRequest req) {
        return search(req.embedding(), req.source_type(), req.limit() > 0 ? req.limit() : 5);
    }

    @GetMapping("/topology/{service}")
    public Map<String, Object> topology(@PathVariable String service) {
        var out = links.findByFromService(service);
        var in  = links.findByToService(service);
        return Map.of("service", service, "outgoing", out, "incoming", in);
    }

    @GetMapping("/runbooks")
    public List<KbRunbook> runbooksSearch(
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int limit) {
        if (q == null || q.isBlank()) throw new IllegalArgumentException("q must not be blank");
        return runbooks.search(q, limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    private List<Map<String, Object>> search(String embedding, String sourceType, int limit) {
        if (embedding == null || embedding.isBlank())
            throw new IllegalArgumentException("embedding must not be blank");
        return docs.findSimilar(embedding, sourceType, limit)
                   .stream()
                   .map(this::rowToMap)
                   .toList();
    }

    private Map<String, Object> rowToMap(Object[] row) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id",          row[0]);
        m.put("source_type", row[1]);
        m.put("title",       row[2]);
        m.put("body",        row[3]);
        m.put("metadata",    row[4]);
        m.put("created_at",  row[5]);
        m.put("distance",    row[6]);
        return m;
    }

    record SearchRequest(String embedding, String source_type, int limit) {}
}
