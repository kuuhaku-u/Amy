package app.monthlyspend.security;

import app.monthlyspend.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ApiSecurityFilter extends OncePerRequestFilter {
    private static final int MAX_WRITES_PER_MINUTE = 60;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, RateBucket> writeRates = new ConcurrentHashMap<>();

    public ApiSecurityFilter(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var authorization = request.getHeader("Authorization");
        var supplied = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : "";
        if (!safeEquals(properties.accessToken(), supplied)) {
            error(response, 401, "This device does not have a valid access link.");
            return;
        }

        if ("PUT".equals(request.getMethod()) && !allowWrite(clientAddress(request))) {
            response.setHeader("Retry-After", "60");
            error(response, 429, "Too many save attempts. Please wait one minute.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean allowWrite(String client) {
        long minute = Instant.now().getEpochSecond() / 60;
        var bucket = writeRates.compute(client, (key, existing) -> {
            if (existing == null || existing.minute != minute) return new RateBucket(minute, 1);
            return new RateBucket(minute, existing.count + 1);
        });
        if (writeRates.size() > 1_000) writeRates.entrySet().removeIf(entry -> entry.getValue().minute < minute - 2);
        return bucket.count <= MAX_WRITES_PER_MINUTE;
    }

    private static String clientAddress(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        return forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",", 2)[0].trim();
    }

    private static boolean safeEquals(String expected, String supplied) {
        if (expected == null || expected.isBlank()) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private void error(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of("error", message));
    }

    private record RateBucket(long minute, int count) {}
}

