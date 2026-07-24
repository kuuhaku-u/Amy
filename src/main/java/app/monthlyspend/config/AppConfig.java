package app.monthlyspend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import app.monthlyspend.sheet.SheetsClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.util.List;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {
    @Bean
    GoogleCredentials googleCredentials(AppProperties properties) throws IOException {
        var inline = properties.googleCredentialsJson();
        var stream = inline != null && !inline.isBlank()
                ? new ByteArrayInputStream(inline.getBytes(StandardCharsets.UTF_8))
                : new FileInputStream(properties.googleCredentialsFile());
        try (stream) {
            return GoogleCredentials.fromStream(stream)
                    .createScoped(List.of(
                            "https://www.googleapis.com/auth/spreadsheets",
                            "https://www.googleapis.com/auth/drive"
                    ));
        }
    }

    @Bean
    HttpClient httpClient() {
        return HttpClient.newBuilder().build();
    }

    @Bean
    SheetsClient sheetsClient(HttpClient httpClient, ObjectMapper objectMapper,
                              GoogleCredentials credentials, AppProperties properties) {
        return new SheetsClient(httpClient, objectMapper, credentials, properties);
    }
}
