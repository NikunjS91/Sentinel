package com.sentinel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "sentinel.incident")
public class IncidentDeadlineConfig {

    private int defaultDeadlineSeconds = 60;

    public int getDefaultDeadlineSeconds() { return defaultDeadlineSeconds; }
    public void setDefaultDeadlineSeconds(int s) { this.defaultDeadlineSeconds = s; }
}
