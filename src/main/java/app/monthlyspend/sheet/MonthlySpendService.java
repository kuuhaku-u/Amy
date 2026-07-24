package app.monthlyspend.sheet;

import app.monthlyspend.api.ApiException;
import app.monthlyspend.config.AppProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static app.monthlyspend.sheet.MonthlySpendModels.SpendResponse;
import static app.monthlyspend.sheet.MonthlySpendModels.UpdateRequest;

@Service
public class MonthlySpendService {
    private static final int HEADER_ROW = 3;
    private static final int FIRST_DATA_ROW = 4;
    private static final LocalDate SHEETS_EPOCH = LocalDate.of(1899, 12, 30);
    private static final List<String> EXPECTED_HEADERS = List.of(
            "Date", "Travel", "Breakfast", "Lunch", "Eve Snack", "Dinner", "Order", "Others",
            "Total", "week Total", "Home", "Rent", "Monthly Spend"
    );
    private static final List<ExpenseField> DAILY_TOTAL_FIELDS = List.of(
            ExpenseField.TRAVEL, ExpenseField.BREAKFAST, ExpenseField.LUNCH,
            ExpenseField.EVE_SNACK, ExpenseField.DINNER
    );

    private final SheetsClient sheets;
    private final AppProperties properties;
    private final ReentrantLock updateLock = new ReentrantLock(true);
    private final Map<String, SpendResponse> recentSubmissions = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, SpendResponse> eldest) {
            return size() > 500;
        }
    };

    public MonthlySpendService(SheetsClient sheets, AppProperties properties) {
        this.sheets = sheets;
        this.properties = properties;
    }

    public SpendResponse get(LocalDate date) {
        var rows = loadRows();
        ensureUniqueDates(rows);
        return rows.stream().filter(row -> date.equals(row.date)).findFirst()
                .map(row -> response(row, true))
                .orElseGet(() -> emptyResponse(date));
    }

    public SpendResponse update(UpdateRequest request) {
        validateChanges(request.changes());
        updateLock.lock();
        try {
            var cached = recentSubmissions.get(request.submissionId());
            if (cached != null) return cached;

            var rows = loadRows();
            ensureUniqueDates(rows);
            var row = rows.stream().filter(item -> request.date().equals(item.date)).findFirst().orElse(null);
            if (row == null) {
                sheets.appendRow(quotedSheet() + "!A:M", List.of(dateFormula(request.date())));
                rows = loadRows();
                ensureUniqueDates(rows);
                row = rows.stream().filter(item -> request.date().equals(item.date)).findFirst()
                        .orElseThrow(() -> new ApiException(HttpStatus.BAD_GATEWAY,
                                "The new date row could not be read back from Google Sheets."));
            }

            for (var change : request.changes().entrySet()) {
                row.values.put(ExpenseField.byKey().get(change.getKey()), normalize(change.getValue()));
            }
            calculate(rows);
            writeRows(rows, row, request.changes());
            var response = response(row, true);
            recentSubmissions.put(request.submissionId(), response);
            return response;
        } finally {
            updateLock.unlock();
        }
    }

    private List<SheetRow> loadRows() {
        var raw = sheets.readValues(quotedSheet() + "!A" + HEADER_ROW + ":M");
        if (raw.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "The worksheet header row is missing.");
        validateHeaders(raw.get(0));
        var rows = new ArrayList<SheetRow>();
        for (int index = 1; index < raw.size(); index++) {
            var values = raw.get(index);
            if (values.isEmpty() || values.get(0) == null || values.get(0).toString().isBlank()) continue;
            var date = parseDate(values.get(0));
            var row = new SheetRow(FIRST_DATA_ROW + index - 1, date);
            for (var field : ExpenseField.values()) row.values.put(field, numberAt(values, field.columnIndex()));
            row.total = numberAt(values, 8);
            row.weekTotal = numberAt(values, 9);
            row.monthlySpend = numberAt(values, 12);
            rows.add(row);
        }
        calculate(rows);
        return rows;
    }

    private void validateHeaders(List<Object> actual) {
        for (int index = 0; index < EXPECTED_HEADERS.size(); index++) {
            var value = index < actual.size() && actual.get(index) != null ? actual.get(index).toString().trim() : "";
            if (!EXPECTED_HEADERS.get(index).equals(value)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Expected header '" + EXPECTED_HEADERS.get(index) + "' in column " + columnName(index + 1) + ".");
            }
        }
    }

    private void validateChanges(Map<String, BigDecimal> changes) {
        if (changes.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Change at least one amount before saving.");
        for (var entry : changes.entrySet()) {
            if (!ExpenseField.byKey().containsKey(entry.getKey())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown expense field: " + entry.getKey());
            }
            if (entry.getValue() != null && (entry.getValue().signum() < 0 || entry.getValue().scale() > 2)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Amounts must be non-negative with at most two decimal places.");
            }
        }
    }

    private void ensureUniqueDates(List<SheetRow> rows) {
        var counts = new HashMap<LocalDate, Integer>();
        for (var row : rows) counts.merge(row.date, 1, Integer::sum);
        counts.forEach((date, count) -> {
            if (count > 1) throw new ApiException(HttpStatus.CONFLICT,
                    "The worksheet contains duplicate rows for " + date + ". Resolve them before syncing.");
        });
    }

    private void calculate(List<SheetRow> rows) {
        for (var row : rows) {
            row.total = DAILY_TOTAL_FIELDS.stream().map(field -> zero(row.values.get(field)))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        var weekly = new HashMap<LocalDate, BigDecimal>();
        for (var row : rows) weekly.merge(mondayOf(row.date), row.total, BigDecimal::add);
        for (var row : rows) row.weekTotal = weekly.get(mondayOf(row.date));

        var sorted = rows.stream().sorted(Comparator.comparing(item -> item.date)).toList();
        YearMonth currentMonth = null;
        var running = BigDecimal.ZERO;
        for (var row : sorted) {
            var month = YearMonth.from(row.date);
            if (!month.equals(currentMonth)) {
                currentMonth = month;
                running = BigDecimal.ZERO;
            }
            for (var value : row.values.values()) running = running.add(zero(value));
            row.monthlySpend = running;
        }
    }

    private void writeRows(List<SheetRow> rows, SheetRow edited, Map<String, BigDecimal> changes) {
        var updates = new ArrayList<Map<String, Object>>();
        for (var key : changes.keySet()) {
            var field = ExpenseField.byKey().get(key);
            addUpdate(updates, field.header(), edited.rowNumber, edited.values.get(field));
        }
        for (var row : rows) {
            addUpdate(updates, "Total", row.rowNumber, row.total);
            addUpdate(updates, "week Total", row.rowNumber, row.weekTotal);
            addUpdate(updates, "Monthly Spend", row.rowNumber, row.monthlySpend);
        }
        sheets.batchUpdateValues(updates);
        int lastRow = rows.stream().mapToInt(item -> item.rowNumber).max().orElse(FIRST_DATA_ROW);
        sheets.formatDateColumn(FIRST_DATA_ROW - 1, lastRow);
    }

    private void addUpdate(List<Map<String, Object>> updates, String header, int rowNumber, BigDecimal value) {
        int column = EXPECTED_HEADERS.indexOf(header) + 1;
        updates.add(Map.of(
                "range", quotedSheet() + "!" + columnName(column) + rowNumber,
                "majorDimension", "ROWS",
                "values", List.of(List.of(value == null ? "" : value))
        ));
    }

    private SpendResponse response(SheetRow row, boolean exists) {
        var values = new LinkedHashMap<String, BigDecimal>();
        for (var field : ExpenseField.values()) values.put(field.key(), row.values.get(field));
        return new SpendResponse(row.date, exists, values, row.total, row.weekTotal, row.monthlySpend);
    }

    private SpendResponse emptyResponse(LocalDate date) {
        var values = new LinkedHashMap<String, BigDecimal>();
        for (var field : ExpenseField.values()) values.put(field.key(), null);
        return new SpendResponse(date, false, values, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private LocalDate parseDate(Object value) {
        if (value instanceof Number number) return SHEETS_EPOCH.plusDays(Math.round(number.doubleValue()));
        try {
            return LocalDate.parse(value.toString());
        } catch (DateTimeParseException exception) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A Date cell is not a real Google Sheets date: " + value);
        }
    }

    private static BigDecimal numberAt(List<Object> values, int index) {
        if (index >= values.size() || values.get(index) == null || values.get(index).toString().isBlank()) return null;
        var text = values.get(index).toString().trim();
        if ("NA".equalsIgnoreCase(text) || "IDK".equalsIgnoreCase(text)) return null;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "An amount cell contains non-numeric data.");
        }
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value == null ? null : value.setScale(Math.max(0, value.scale()), RoundingMode.UNNECESSARY);
    }

    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static LocalDate mondayOf(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }
    private static String dateFormula(LocalDate date) {
        return "=DATE(" + date.getYear() + "," + date.getMonthValue() + "," + date.getDayOfMonth() + ")";
    }
    private String quotedSheet() { return "'" + properties.sheetName().replace("'", "''") + "'"; }
    private static String columnName(int oneBased) { return String.valueOf((char) ('A' + oneBased - 1)); }

    private static final class SheetRow {
        private final int rowNumber;
        private final LocalDate date;
        private final Map<ExpenseField, BigDecimal> values = new LinkedHashMap<>();
        private BigDecimal total = BigDecimal.ZERO;
        private BigDecimal weekTotal = BigDecimal.ZERO;
        private BigDecimal monthlySpend = BigDecimal.ZERO;

        private SheetRow(int rowNumber, LocalDate date) {
            this.rowNumber = rowNumber;
            this.date = date;
        }
    }
}
