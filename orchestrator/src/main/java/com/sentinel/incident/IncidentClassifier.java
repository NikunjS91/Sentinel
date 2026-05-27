package com.sentinel.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class IncidentClassifier {

    private final ObjectMapper json;

    public IncidentClassifier(ObjectMapper json) { this.json = json; }

    /**
     * Map an incident's raw alert to a severity: p1 (most severe) .. p4.
     * Rule-based and deterministic — never throws; unparseable input yields p3.
     */
    public String classify(String rawAlertJson) {
        try {
            JsonNode alert = json.readTree(rawAlertJson);
            String sev = alert.path("severity").asText("").toLowerCase();

            return switch (sev) {
                case "critical" -> "p1";
                case "error"    -> "p2";
                case "warning"  -> "p3";
                case "info"     -> "p4";
                default         -> "p3";
            };
        } catch (Exception e) {
            return "p3";
        }
    }
}
