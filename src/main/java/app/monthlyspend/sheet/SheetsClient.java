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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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

    public List<List<String>> readNotes(String range) {
        var response = send("GET", BASE_URL + properties.spreadsheetId() + "?ranges=" + encodePath(range)
                + "&includeGridData=true&fields=sheets.data.rowData.values.note", null);
        try {
            Map<String, Object> parsed = objectMapper.readValue(response, new TypeReference<>() {});
            var sheetList = objectMapper.convertValue(parsed.getOrDefault("sheets", List.of()),
                    new TypeReference<List<Map<String, Object>>>() {});
            if (sheetList.isEmpty()) return List.of();
            var data = objectMapper.convertValue(sheetList.get(0).getOrDefault("data", List.of()),
                    new TypeReference<List<Map<String, Object>>>() {});
            if (data.isEmpty()) return List.of();
            var rowData = objectMapper.convertValue(data.get(0).getOrDefault("rowData", List.of()),
                    new TypeReference<List<Map<String, Object>>>() {});
            var result = new java.util.ArrayList<List<String>>();
            for (var row : rowData) {
                var cells = objectMapper.convertValue(row.getOrDefault("values", List.of()),
                        new TypeReference<List<Map<String, Object>>>() {});
                result.add(cells.stream().map(cell -> cell.getOrDefault("note", "").toString()).toList());
            }
            return result;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Google Sheets returned unreadable cell notes.");
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

    public Map<String, Integer> sheetIds() {
        var response = send("GET", BASE_URL + properties.spreadsheetId()
                + "?fields=sheets.properties(sheetId,title)", null);
        try {
            Map<String, Object> parsed = objectMapper.readValue(response, new TypeReference<>() {});
            var result = new LinkedHashMap<String, Integer>();
            var sheetList = objectMapper.convertValue(parsed.getOrDefault("sheets", List.of()),
                    new TypeReference<List<Map<String, Object>>>() {});
            for (var sheet : sheetList) {
                var sheetProperties = objectMapper.convertValue(sheet.get("properties"),
                        new TypeReference<Map<String, Object>>() {});
                result.put(sheetProperties.get("title").toString(),
                        ((Number) sheetProperties.get("sheetId")).intValue());
            }
            return result;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Google Sheets returned unreadable worksheet metadata.");
        }
    }

    public void addSheet(String title) {
        batchUpdateSpreadsheet(List.of(Map.of("addSheet", Map.of("properties", Map.of("title", title)))));
    }

    public void duplicateSheet(int sourceSheetId, String title) {
        batchUpdateSpreadsheet(List.of(Map.of("duplicateSheet", Map.of(
                "sourceSheetId", sourceSheetId, "newSheetName", title))));
    }

    public void clearValues(String range) {
        send("POST", BASE_URL + properties.spreadsheetId() + "/values/" + encodePath(range) + ":clear", Map.of());
    }

    public String ensureDriveFolder(String folderName) {
        var query = "mimeType='application/vnd.google-apps.folder' and trashed=false and name='"
                + folderName.replace("'", "\\'") + "'";
        var response = send("GET", "https://www.googleapis.com/drive/v3/files?q=" + encodePath(query)
                + "&fields=files(id,name)&pageSize=1", null);
        try {
            Map<String, Object> parsed = objectMapper.readValue(response, new TypeReference<>() {});
            var files = objectMapper.convertValue(parsed.getOrDefault("files", List.of()),
                    new TypeReference<List<Map<String, Object>>>() {});
            if (!files.isEmpty()) return files.get(0).get("id").toString();
            var created = send("POST", "https://www.googleapis.com/drive/v3/files?fields=id", Map.of(
                    "name", folderName, "mimeType", "application/vnd.google-apps.folder"));
            Map<String, Object> folder = objectMapper.readValue(created, new TypeReference<>() {});
            return folder.get("id").toString();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Google Drive returned unreadable folder information.");
        }
    }

    public String uploadDriveFile(String folderId, String name, String contentType, byte[] bytes,
                                  Map<String, String> appProperties) {
        var boundary = "monthly-spend-" + UUID.randomUUID();
        try {
            var metadata = objectMapper.writeValueAsString(Map.of(
                    "name", name, "parents", List.of(folderId), "appProperties", appProperties));
            var prefix = ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                    + metadata + "\r\n--" + boundary + "\r\nContent-Type: " + contentType + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            var suffix = ("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8);
            var body = new byte[prefix.length + bytes.length + suffix.length];
            System.arraycopy(prefix, 0, body, 0, prefix.length);
            System.arraycopy(bytes, 0, body, prefix.length, bytes.length);
            System.arraycopy(suffix, 0, body, prefix.length + bytes.length, suffix.length);
            var response = sendBytes("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
                    "multipart/related; boundary=" + boundary, body);
            Map<String, Object> parsed = objectMapper.readValue(response, new TypeReference<>() {});
            return parsed.get("id").toString();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not prepare the receipt for Google Drive.");
        }
    }

    public void batchUpdateSpreadsheet(List<Map<String, Object>> requests) {
        send("POST", BASE_URL + properties.spreadsheetId() + ":batchUpdate", Map.of("requests", requests));
    }

    public void formatDateColumn(int sheetId, int startRowIndex, int endRowIndex) {
        var format = Map.of(
                "requests", List.of(Map.of("repeatCell", Map.of(
                        "range", Map.of(
                                "sheetId", sheetId,
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

    private String sendBytes(String method, String url, String contentType, byte[] body) {
        try {
            credentials.refreshIfExpired();
            var request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue())
                    .header("Accept", "application/json").header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new ApiException(HttpStatus.BAD_GATEWAY, googleError(response.statusCode(), response.body()));
            return response.body();
        } catch (ApiException exception) { throw exception;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not connect to Google Drive.");
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
