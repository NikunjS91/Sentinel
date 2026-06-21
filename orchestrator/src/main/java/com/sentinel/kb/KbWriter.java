package com.sentinel.kb;

import com.sentinel.incident.IncidentReport;
import com.sentinel.incident.Incident;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class KbWriter {

    private static final Logger log = LoggerFactory.getLogger(KbWriter.class);

    private final KbDocumentRepository docs;

    public KbWriter(KbDocumentRepository docs) {
        this.docs = docs;
    }

    /** Insert a resolved incident into kb_documents as a past_incident row.
     *  Embedding is left NULL — the Python backfill task fills it in asynchronously.
     *  This method is non-fatal: a write failure logs the error but does not
     *  derail the incident's already-completed resolution. */
    public void writePastIncident(Incident inc, IncidentReport report) {
        try {
            KbDocument doc = new KbDocument();
            doc.setId(UUID.randomUUID());
            doc.setSourceType("past_incident");
            doc.setTitle(report.getSummary() == null
                ? "Incident " + inc.getId() : report.getSummary());
            doc.setBody(buildBody(inc, report));

            Map<String, Object> meta = new HashMap<>();
            meta.put("incident_id", inc.getId().toString());
            meta.put("service", inc.getSource());
            meta.put("severity", inc.getSeverity());
            doc.setMetadata(meta);

            docs.save(doc);
            log.info("wrote past_incident {} for incident {}", doc.getId(), inc.getId());
        } catch (Exception e) {
            log.error("KbWriter failed to write past_incident for {}", inc.getId(), e);
        }
    }

    private String buildBody(Incident inc, IncidentReport report) {
        StringBuilder sb = new StringBuilder();
        if (report.getSummary() != null) {
            sb.append(report.getSummary()).append("\n\n");
        }
        if (report.getRootCause() != null) {
            sb.append("Root cause: ").append(report.getRootCause()).append("\n");
        }
        if (report.getRecommendedAction() != null) {
            sb.append("Recommended action: ").append(report.getRecommendedAction());
        }
        return sb.toString().trim();
    }
}
