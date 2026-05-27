package com.sentinel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinel.incident.IncidentClassifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassifierUnitTest {

    private final IncidentClassifier classifier = new IncidentClassifier(new ObjectMapper());

    // TC-1.6.1: classification assigns correct severity from raw alert JSON
    @Test
    void tc_1_6_1_classification_assigns_severity() {
        assertThat(classifier.classify("{\"severity\":\"critical\"}")).isEqualTo("p1");
        assertThat(classifier.classify("{\"severity\":\"error\"}")).isEqualTo("p2");
        assertThat(classifier.classify("{\"severity\":\"warning\"}")).isEqualTo("p3");
        assertThat(classifier.classify("{\"severity\":\"info\"}")).isEqualTo("p4");
        assertThat(classifier.classify("{\"severity\":\"unknown\"}")).isEqualTo("p3");
        assertThat(classifier.classify("{}")).isEqualTo("p3");
        assertThat(classifier.classify("not-json")).isEqualTo("p3");
    }
}
