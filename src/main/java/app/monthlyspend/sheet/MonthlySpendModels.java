package app.monthlyspend.sheet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class MonthlySpendModels {
    private MonthlySpendModels() {}

    public record UpdateRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,80}") String submissionId,
            @NotNull LocalDate date,
            @NotNull Map<String, BigDecimal> changes
    ) {}

    public record SpendResponse(
            LocalDate date,
            boolean exists,
            Map<String, BigDecimal> values,
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
}
