package app.monthlyspend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class StartupChecks {
    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);
    private final AppProperties properties;

    public StartupChecks(AppProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void verifyConfiguration() {
        if ("change-me-before-running".equals(properties.accessToken())) {
            log.warn("APP_ACCESS_TOKEN is using the development default. Set a strong secret before deployment.");
        }
    }
}

