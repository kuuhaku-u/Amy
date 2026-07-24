package app.monthlyspend.sheet;

import app.monthlyspend.api.ApiException;
import app.monthlyspend.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class SheetsClient {
    private static final String BASE_URL = "https://sheets.googleapis.com/v4/spreadsheets/";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GoogleCredentials credentials;
    private final AppProperties properties;

    public SheetsClient(HttpClient httpClient, ObjectMapper objectMapper,
                        GoogleCredentials credentials, AppProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.credentials = credentials;
        this.properties = properties;
    }

    public List<List<Object>> readValues(String range) {
        var path = encodePath(range);
        var response = send("GET", BASE_URL + properties.spreadsheetId() + "/values/" + path
                + "?valueRenderOption=UNFORMATTED_VALUE&dateTimeRenderOption=SERIAL_NUMBER", null);
        try {
            Map<String, Object> parsed = objectMapper.readValue(response, new TypeReference<>() {});
            Object values = parsed.get("values");
            if (values == null) return List.of();
            return objectMapper.convertValue(values, new TypeReference<>() {});
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Google Sheets returned an unreadable response.");
        }
    }

    public void appendRow(String range, List<Object> values) {
        var body = Map.of("majorDimension", "ROWS", "values", List.of(values));
        send("POST", BASE_URL + properties.spreadsheetId() + "/values/" + encodePath(range)
                + ":append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS", body);
    }

    public void batchUpdateValues(List<Map<String, Object>> data) {
        var body = Map.of("valueInputOption", "USER_ENTERED", "data", data);
        send("POST", BASE_URL + properties.spreadsheetId() + "/values:batchUpdate", body);
    }

    public void formatDateColumn(int startRowIndex, int endRowIndex) {
        var format = Map.of(
                "requests", List.of(Map.of("repeatCell", Map.of(
                        "range", Map.of(
                                "sheetId", 0,
                                "startRowIndex", startRowIndex,
                                "endRowIndex", endRowIndex,
                                "startColumnIndex", 0,
                                "endColumnIndex", 1
                        ),
                        "cell", Map.of("userEnteredFormat", Map.of(
                                "numberFormat", Map.of("type", "DATE", "pattern", "MM-dd")
                        )),
                        "fields", "userEnteredFormat.numberFormat"
                )))
        );
        send("POST", BASE_URL + properties.spreadsheetId() + ":batchUpdate", format);
    }

    private String send(String method, String url, Object body) {
        try {
            credentials.refreshIfExpired();
            var builder = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue())
                    .header("Accept", "application/json");
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            }
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, googleError(response.statusCode(), response.body()));
            }
            return response.body();
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not connect to Google Sheets.");
        }
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String googleError(int status, String body) {
        if (body != null && (body.contains("SERVICE_DISABLED") || body.contains("has not been used in project"))) {
            return "The Google Sheets API is not enabled for the service-account project.";
        }
        if (status == 403) {
            return "The service account cannot access this spreadsheet. Share it with the credentials client_email as Editor.";
        }
        if (status == 429) return "Google Sheets is temporarily rate-limiting requests.";
        return "Google Sheets rejected the request (status " + status + ").";
    }
}
