package app.monthlyspend.drive;

import app.monthlyspend.api.ApiException;
import app.monthlyspend.config.AppProperties;
import app.monthlyspend.sheet.SheetsClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GoogleDriveOAuthService {
    private static final String SETTINGS_SHEET = "Private Settings";
    private static final String TOKEN_KEY = "google.drive.oauth.refresh-token";
    private final HttpClient http;
    private final ObjectMapper json;
    private final SheetsClient sheets;
    private final AppProperties properties;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private volatile AccessToken cachedToken;

    public GoogleDriveOAuthService(HttpClient http, ObjectMapper json, SheetsClient sheets, AppProperties properties) {
        this.http = http; this.json = json; this.sheets = sheets; this.properties = properties;
        var local = loadLocalClient(json);
        this.clientId = text(properties.googleDriveOauthClientId()) ? properties.googleDriveOauthClientId() : local.getOrDefault("client_id", "");
        this.clientSecret = text(properties.googleDriveOauthClientSecret()) ? properties.googleDriveOauthClientSecret() : local.getOrDefault("client_secret", "");
        this.redirectUri = text(properties.googleDriveOauthRedirectUri()) ? properties.googleDriveOauthRedirectUri()
                : local.getOrDefault("redirect_uri", "http://localhost:8080/oauth/google-drive/callback");
    }

    public boolean configured() { return text(clientId) && text(clientSecret); }
    public boolean connected() { return configured() && loadSetting(TOKEN_KEY) != null; }

    public String authorizationUrl() {
        requireConfigured();
        var state = stateToken();
        return "https://accounts.google.com/o/oauth2/v2/auth?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code&scope=" + enc("https://www.googleapis.com/auth/drive")
                + "&access_type=offline&prompt=consent&state=" + enc(state);
    }

    public void complete(String code, String state) {
        requireConfigured();
        verifyState(state);
        var response = form("https://oauth2.googleapis.com/token", Map.of(
                "code", code, "client_id", clientId, "client_secret", clientSecret,
                "redirect_uri", redirectUri, "grant_type", "authorization_code"));
        var refresh = value(response, "refresh_token");
        if (!text(refresh)) throw new ApiException(HttpStatus.BAD_GATEWAY, "Google did not return a refresh token. Revoke the app connection and try again.");
        saveSetting(TOKEN_KEY, encrypt(refresh));
        cachedToken = null;
    }

    public String upload(String folderId, String name, String mimeType, byte[] bytes, Map<String, String> appProperties) {
        if (!text(folderId)) throw new ApiException(HttpStatus.BAD_REQUEST, "The Google Drive receipt folder is not configured.");
        try {
            var boundary = "receipt-" + UUID.randomUUID();
            var metadata = json.writeValueAsString(Map.of("name", name, "parents", List.of(folderId), "appProperties", appProperties));
            var prefix = ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + metadata
                    + "\r\n--" + boundary + "\r\nContent-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            var suffix = ("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8);
            var body = new byte[prefix.length + bytes.length + suffix.length];
            System.arraycopy(prefix, 0, body, 0, prefix.length); System.arraycopy(bytes, 0, body, prefix.length, bytes.length);
            System.arraycopy(suffix, 0, body, prefix.length + bytes.length, suffix.length);
            var request = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"))
                    .header("Authorization", "Bearer " + accessToken()).header("Content-Type", "multipart/related; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw driveError(response.statusCode());
            return value(json.readValue(response.body(), new TypeReference<>() {}), "id");
        } catch (ApiException exception) { throw exception;
        } catch (Exception exception) { throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not upload the receipt to Google Drive."); }
    }

    public byte[] download(String fileId) {
        try {
            var request = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files/" + enc(fileId) + "?alt=media"))
                    .header("Authorization", "Bearer " + accessToken()).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) throw driveError(response.statusCode());
            return response.body();
        } catch (ApiException exception) { throw exception;
        } catch (Exception exception) { throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not load a receipt from Google Drive."); }
    }

    private synchronized String accessToken() {
        if (cachedToken != null && cachedToken.expiresAt().isAfter(Instant.now().plusSeconds(60))) return cachedToken.value();
        var stored = loadSetting(TOKEN_KEY);
        if (stored == null) throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "Connect your Google Drive before uploading receipts.");
        var response = form("https://oauth2.googleapis.com/token", Map.of("client_id", clientId,
                "client_secret", clientSecret, "refresh_token", decrypt(stored), "grant_type", "refresh_token"));
        cachedToken = new AccessToken(value(response, "access_token"), Instant.now().plusSeconds(Long.parseLong(value(response, "expires_in"))));
        return cachedToken.value();
    }

    private Map<String, Object> form(String url, Map<String, String> values) {
        try {
            var body = values.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).reduce((a,b) -> a + "&" + b).orElse("");
            var response = http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new ApiException(HttpStatus.BAD_GATEWAY, "Google OAuth rejected the connection.");
            return json.readValue(response.body(), new TypeReference<>() {});
        } catch (ApiException exception) { throw exception;
        } catch (Exception exception) { throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not connect to Google OAuth."); }
    }

    private String loadSetting(String key) {
        if (!sheets.sheetIds().containsKey(SETTINGS_SHEET)) return null;
        for (var row : sheets.readValues("'Private Settings'!A2:C")) if (row.size() >= 2 && key.equals(row.get(0).toString())) return row.get(1).toString();
        return null;
    }

    private synchronized void saveSetting(String key, String value) {
        if (!sheets.sheetIds().containsKey(SETTINGS_SHEET)) { sheets.addSheet(SETTINGS_SHEET); sheets.appendRow("'Private Settings'!A:C", List.of("Key", "Value", "Updated At")); }
        var rows = sheets.readValues("'Private Settings'!A2:C");
        for (int i = 0; i < rows.size(); i++) if (!rows.get(i).isEmpty() && key.equals(rows.get(i).get(0).toString())) {
            sheets.batchUpdateValues(List.of(Map.of("range", "'Private Settings'!B" + (i + 2) + ":C" + (i + 2), "majorDimension", "ROWS",
                    "values", List.of(List.of(value, Instant.now().toString()))))); return;
        }
        sheets.appendRow("'Private Settings'!A:C", List.of(key, value, Instant.now().toString()));
    }

    private String stateToken() { var expiry = Long.toString(Instant.now().plusSeconds(600).getEpochSecond()); return expiry + "." + sign(expiry); }
    private void verifyState(String state) {
        if (state == null || !state.matches("\\d+\\.[A-Za-z0-9_-]+")) throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid Google OAuth state.");
        var parts = state.split("\\.", 2);
        if (Long.parseLong(parts[0]) < Instant.now().getEpochSecond() || !MessageDigest.isEqual(sign(parts[0]).getBytes(), parts[1].getBytes()))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "The Google OAuth connection link expired.");
    }
    private String sign(String value) { try { var mac = javax.crypto.Mac.getInstance("HmacSHA256"); mac.init(new javax.crypto.spec.SecretKeySpec(properties.accessToken().getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String encrypt(String value) { try { var iv = new byte[12]; new SecureRandom().nextBytes(iv); var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv)); return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String decrypt(String value) { try { var parts = value.split(":", 2); var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0]))); return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8); } catch (Exception e) { throw new ApiException(HttpStatus.UNAUTHORIZED, "The saved Google Drive connection cannot be unlocked with this app key."); } }
    private SecretKeySpec key() { try { return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(properties.accessToken().getBytes(StandardCharsets.UTF_8)), "AES"); } catch (Exception e) { throw new IllegalStateException(e); } }
    private void requireConfigured() { if (!configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Google Drive OAuth client credentials are not configured."); }
    private static Map<String, String> loadLocalClient(ObjectMapper json) {
        try {
            var path = Path.of("client.json");
            if (!Files.isRegularFile(path)) return Map.of();
            Map<String, Object> root = json.readValue(Files.readString(path), new TypeReference<>() {});
            var web = root.get("web");
            if (!(web instanceof Map<?, ?> values)) return Map.of();
            var result = new java.util.HashMap<String, String>();
            for (var key : List.of("client_id", "client_secret")) if (values.get(key) != null) result.put(key, values.get(key).toString());
            var redirects = values.get("redirect_uris");
            if (redirects instanceof List<?> list) list.stream().map(Object::toString)
                    .filter(uri -> uri.startsWith("http://localhost:8080/")).findFirst().ifPresent(uri -> result.put("redirect_uri", uri));
            return result;
        } catch (Exception ignored) { return Map.of(); }
    }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static String value(Map<String, Object> map, String key) { var value = map.get(key); return value == null ? "" : value.toString(); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static ApiException driveError(int status) { return new ApiException(HttpStatus.BAD_GATEWAY, status == 401 ? "The Google Drive connection expired. Connect it again." : "Google Drive rejected the receipt request (status " + status + ")."); }
    private record AccessToken(String value, Instant expiresAt) {}
}
