package app.monthlyspend.sheet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class MonthlySpendModels {
    private MonthlySpendModels() {}

    public record UpdateRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,80}") String submissionId,
            @NotNull LocalDate date,
            @Size(max = 100) String sheetName,
            @NotNull Map<String, BigDecimal> changes,
            Map<String, @Size(max = 500) String> comments
    ) {}

    public record SheetInfo(String name) {}
    public record CreateSheetRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 100) String sourceSheet
    ) {}

    public record SpendResponse(
            LocalDate date,
            boolean exists,
            Map<String, BigDecimal> values,
            Map<String, String> comments,
            BigDecimal total,
            BigDecimal weekTotal,
            BigDecimal monthlySpend
    ) {}

    public record DailySpend(LocalDate date, BigDecimal total) {}
    public record CategorySpend(String key, String label, BigDecimal total) {}
    public record WeekComparison(
            LocalDate start,
            BigDecimal total,
            BigDecimal previousTotal,
            BigDecimal changePercent
    ) {}
    public record AnalyticsResponse(
            String month,
            BigDecimal monthlyTotal,
            BigDecimal previousMonthTotal,
            BigDecimal monthChangePercent,
            BigDecimal averageRecordedDay,
            String highestCategory,
            List<DailySpend> daily,
            List<CategorySpend> categories,
            WeekComparison week,
            List<String> insights
    ) {}

    public enum TransactionType { DEBIT, CREDIT }

    public record TransactionRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{16,128}") String eventId,
            @NotNull LocalDate date,
            @Size(max = 100) String sheetName,
            @NotNull OffsetDateTime occurredAt,
            @NotNull TransactionType type,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Size(max = 40) String category,
            @Size(max = 160) String merchant,
            @NotBlank @Size(max = 160) String source,
            @NotBlank @Pattern(regexp = "NOTIFICATION|IMAGE") String captureMethod,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,80}") String deviceId
    ) {}

    public record TransactionResponse(
            String status,
            boolean summaryUpdated,
            SpendResponse spend
    ) {}
}
