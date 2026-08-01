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
import java.time.OffsetDateTime;
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
import static app.monthlyspend.sheet.MonthlySpendModels.AnalyticsResponse;
import static app.monthlyspend.sheet.MonthlySpendModels.CategorySpend;
import static app.monthlyspend.sheet.MonthlySpendModels.DailySpend;
import static app.monthlyspend.sheet.MonthlySpendModels.WeekComparison;
import static app.monthlyspend.sheet.MonthlySpendModels.TransactionRequest;
import static app.monthlyspend.sheet.MonthlySpendModels.TransactionResponse;
import static app.monthlyspend.sheet.MonthlySpendModels.CreateSheetRequest;
import static app.monthlyspend.sheet.MonthlySpendModels.SheetInfo;

@Service
public class MonthlySpendService {
    private static final String TRANSACTION_SHEET = "Transaction Log";
    private static final List<String> TRANSACTION_HEADERS = List.of(
            "Event ID", "Occurred At", "Type", "Amount", "Category", "Merchant", "Source",
            "Capture Method", "Device ID", "Status", "Recorded At"
    );
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

    public SpendResponse get(LocalDate date) { return get(date, null); }

    public SpendResponse get(LocalDate date, String requestedSheet) {
        var sheetName = resolveSheet(requestedSheet);
        var rows = loadRows(sheetName);
        ensureUniqueDates(rows);
        return rows.stream().filter(row -> date.equals(row.date)).findFirst()
                .map(row -> response(row, true))
                .orElseGet(() -> emptyResponse(date));
    }

    public AnalyticsResponse analytics(LocalDate anchor, String requestedSheet) {
        var rows = loadRows(resolveSheet(requestedSheet));
        ensureUniqueDates(rows);
        var month = YearMonth.from(anchor);
        var previousMonth = month.minusMonths(1);
        var monthRows = rows.stream().filter(row -> YearMonth.from(row.date).equals(month))
                .sorted(Comparator.comparing(row -> row.date)).toList();

        var daily = monthRows.stream()
                .map(row -> new DailySpend(row.date, allExpenseTotal(row)))
                .toList();
        var monthlyTotal = daily.stream().map(DailySpend::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        var previousMonthTotal = rows.stream().filter(row -> YearMonth.from(row.date).equals(previousMonth))
                .map(this::allExpenseTotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        var categories = new ArrayList<CategorySpend>();
        for (var field : ExpenseField.values()) {
            var total = monthRows.stream().map(row -> zero(row.values.get(field)))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            categories.add(new CategorySpend(field.key(), field.header(), total));
        }
        categories.sort(Comparator.comparing(CategorySpend::total).reversed());

        var weekStart = mondayOf(anchor);
        var previousWeekStart = weekStart.minusWeeks(1);
        var weekTotal = totalBetween(rows, weekStart, weekStart.plusDays(6));
        var previousWeekTotal = totalBetween(rows, previousWeekStart, previousWeekStart.plusDays(6));
        var recordedDays = daily.stream().filter(item -> item.total().signum() > 0).count();
        var average = recordedDays == 0 ? BigDecimal.ZERO
                : monthlyTotal.divide(BigDecimal.valueOf(recordedDays), 2, RoundingMode.HALF_UP);
        var highest = categories.stream().filter(item -> item.total().signum() > 0).findFirst()
                .map(CategorySpend::label).orElse("No spending yet");
        var monthChange = percentChange(monthlyTotal, previousMonthTotal);
        var weekChange = percentChange(weekTotal, previousWeekTotal);

        var insights = new ArrayList<String>();
        if (monthlyTotal.signum() == 0) insights.add("No spending has been recorded for this month yet.");
        else {
            insights.add(highest + " is your highest spending category this month.");
            if (monthChange != null) insights.add("Monthly spending is " + direction(monthChange) + " than the previous month.");
            if (weekChange != null) insights.add("This week is " + direction(weekChange) + " compared with last week.");
            if (recordedDays > 0) insights.add("Your average across recorded days is ₹" + average.toPlainString() + ".");
        }

        return new AnalyticsResponse(month.toString(), monthlyTotal, previousMonthTotal, monthChange,
                average, highest, daily, categories,
                new WeekComparison(weekStart, weekTotal, previousWeekTotal, weekChange), insights);
    }

    public SpendResponse update(UpdateRequest request) {
            validateChanges(request.changes(), request.comments());
        updateLock.lock();
        try {
            var cached = recentSubmissions.get(request.submissionId());
            if (cached != null) return cached;

            var sheetName = resolveSheet(request.sheetName());
            var rows = loadRows(sheetName);
            ensureUniqueDates(rows);
            var row = rows.stream().filter(item -> request.date().equals(item.date)).findFirst().orElse(null);
            if (row == null) {
                sheets.appendRow(quotedSheet(sheetName) + "!A:M", List.of(dateFormula(request.date())));
                rows = loadRows(sheetName);
                ensureUniqueDates(rows);
                row = rows.stream().filter(item -> request.date().equals(item.date)).findFirst()
                        .orElseThrow(() -> new ApiException(HttpStatus.BAD_GATEWAY,
                                "The new date row could not be read back from Google Sheets."));
            }

            for (var change : request.changes().entrySet()) {
                row.values.put(ExpenseField.byKey().get(change.getKey()), normalize(change.getValue()));
            }
            calculate(rows);
            if (request.comments() != null) {
                for (var comment : request.comments().entrySet()) {
                    row.comments.put(ExpenseField.byKey().get(comment.getKey()),
                            comment.getValue() == null ? "" : comment.getValue().trim());
                }
            }
            writeRows(sheetName, rows, row, request.changes(), request.comments());
            var response = response(row, true);
            recentSubmissions.put(request.submissionId(), response);
            return response;
        } finally {
            updateLock.unlock();
        }
    }

    public TransactionResponse recordTransaction(TransactionRequest request) {
        updateLock.lock();
        try {
            var transactionSheetId = ensureTransactionSheet();
            var logRows = sheets.readValues(quote(TRANSACTION_SHEET) + "!A1:K");
            if (logRows.stream().skip(1).anyMatch(row -> !row.isEmpty() && request.eventId().equals(row.get(0).toString()))) {
                return new TransactionResponse("DUPLICATE", false, get(request.date()));
            }

            ExpenseField field = null;
            SheetRow edited = null;
            var sheetName = resolveSheet(request.sheetName());
            var rows = loadRows(sheetName);
            ensureUniqueDates(rows);
            if (request.type() == MonthlySpendModels.TransactionType.DEBIT) {
                field = ExpenseField.byKey().get(request.category());
                if (field == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                        "A valid category is required for a debit transaction.");
                edited = rows.stream().filter(row -> request.date().equals(row.date)).findFirst().orElse(null);
                if (edited == null) {
                    sheets.appendRow(quotedSheet(sheetName) + "!A:M", List.of(dateFormula(request.date())));
                    rows = loadRows(sheetName);
                    ensureUniqueDates(rows);
                    edited = rows.stream().filter(row -> request.date().equals(row.date)).findFirst()
                            .orElseThrow(() -> new ApiException(HttpStatus.BAD_GATEWAY,
                                    "The transaction date row could not be created."));
                }
                edited.values.put(field, zero(edited.values.get(field)).add(request.amount()));
                calculate(rows);
            }

            var sheetIds = sheets.sheetIds();
            var summarySheetId = sheetIds.get(sheetName);
            if (summarySheetId == null) throw new ApiException(HttpStatus.CONFLICT, "The summary worksheet is missing.");
            var batch = new ArrayList<Map<String, Object>>();
            batch.add(appendTransactionRequest(transactionSheetId, request,
                    request.type() == MonthlySpendModels.TransactionType.DEBIT ? "APPLIED" : "LOGGED"));
            if (edited != null) addSummaryGridUpdates(batch, summarySheetId, rows, edited, field);
            sheets.batchUpdateSpreadsheet(batch);
            return new TransactionResponse("CREATED", edited != null,
                    edited == null ? get(request.date(), sheetName) : response(edited, true));
        } finally {
            updateLock.unlock();
        }
    }

    private int ensureTransactionSheet() {
        var ids = sheets.sheetIds();
        if (!ids.containsKey(TRANSACTION_SHEET)) {
            sheets.addSheet(TRANSACTION_SHEET);
            ids = sheets.sheetIds();
            sheets.batchUpdateValues(List.of(Map.of(
                    "range", quote(TRANSACTION_SHEET) + "!A1:K1",
                    "majorDimension", "ROWS",
                    "values", List.of(TRANSACTION_HEADERS)
            )));
        }
        var values = sheets.readValues(quote(TRANSACTION_SHEET) + "!A1:K1");
        if (values.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "The transaction log header is missing.");
        for (int index = 0; index < TRANSACTION_HEADERS.size(); index++) {
            var actual = index < values.get(0).size() ? values.get(0).get(index).toString().trim() : "";
            if (!TRANSACTION_HEADERS.get(index).equals(actual)) throw new ApiException(HttpStatus.CONFLICT,
                    "Expected transaction header '" + TRANSACTION_HEADERS.get(index) + "'.");
        }
        return ids.get(TRANSACTION_SHEET);
    }

    private Map<String, Object> appendTransactionRequest(int sheetId, TransactionRequest request, String status) {
        var values = List.of(
                stringCell(request.eventId()), stringCell(request.occurredAt().toString()), stringCell(request.type().name()),
                numberCell(request.amount()), stringCell(request.category()), stringCell(request.merchant()),
                stringCell(request.source()), stringCell(request.captureMethod()), stringCell(request.deviceId()),
                stringCell(status), stringCell(OffsetDateTime.now().toString())
        );
        return Map.of("appendCells", Map.of(
                "sheetId", sheetId,
                "rows", List.of(Map.of("values", values)),
                "fields", "userEnteredValue"
        ));
    }

    private void addSummaryGridUpdates(List<Map<String, Object>> batch, int sheetId, List<SheetRow> rows,
                                       SheetRow edited, ExpenseField field) {
        addGridCell(batch, sheetId, edited.rowNumber, field.columnIndex(), edited.values.get(field));
        for (var row : rows) {
            addGridCell(batch, sheetId, row.rowNumber, 8, row.total);
            addGridCell(batch, sheetId, row.rowNumber, 9, row.weekTotal);
            addGridCell(batch, sheetId, row.rowNumber, 12, row.monthlySpend);
        }
    }

    private void addGridCell(List<Map<String, Object>> batch, int sheetId, int oneBasedRow,
                             int zeroBasedColumn, BigDecimal value) {
        batch.add(Map.of("updateCells", Map.of(
                "range", Map.of("sheetId", sheetId, "startRowIndex", oneBasedRow - 1,
                        "endRowIndex", oneBasedRow, "startColumnIndex", zeroBasedColumn,
                        "endColumnIndex", zeroBasedColumn + 1),
                "rows", List.of(Map.of("values", List.of(numberCell(value)))),
                "fields", "userEnteredValue"
        )));
    }

    private static Map<String, Object> stringCell(String value) {
        return Map.of("userEnteredValue", Map.of("stringValue", value == null ? "" : value));
    }

    private static Map<String, Object> numberCell(BigDecimal value) {
        return Map.of("userEnteredValue", Map.of("numberValue", zero(value)));
    }

    public List<SheetInfo> sheets() {
        return sheets.sheetIds().keySet().stream()
                .filter(this::hasExpectedHeaders)
                .map(SheetInfo::new).toList();
    }

    public List<MonthlySpendModels.HistoryEntry> history(LocalDate from, LocalDate to, String requestedSheet) {
        if (to.isBefore(from)) throw new ApiException(HttpStatus.BAD_REQUEST, "The end date must not be before the start date.");
        return loadRows(resolveSheet(requestedSheet)).stream()
                .filter(row -> !row.date.isBefore(from) && !row.date.isAfter(to))
                .sorted(Comparator.comparing((SheetRow row) -> row.date).reversed())
                .map(row -> {
                    var values = new LinkedHashMap<String, BigDecimal>();
                    var comments = new LinkedHashMap<String, String>();
                    for (var field : ExpenseField.values()) {
                        values.put(field.key(), row.values.get(field));
                        comments.put(field.key(), row.comments.getOrDefault(field, ""));
                    }
                    return new MonthlySpendModels.HistoryEntry(row.date, values, comments, allExpenseTotal(row));
                }).toList();
    }

    public MonthlySpendModels.CashflowResponse cashflow() {
        var name = sheets.sheetIds().keySet().stream()
                .filter(item -> item.trim().equalsIgnoreCase("Cashflow") || item.trim().equalsIgnoreCase("Cash Flow"))
                .findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "A Cashflow worksheet was not found."));
        var rows = sheets.readValues(quotedSheet(name) + "!A:Z").stream()
                .filter(row -> row.stream().anyMatch(value -> value != null && !value.toString().isBlank()))
                .toList();
        return new MonthlySpendModels.CashflowResponse(name, rows);
    }

    public SheetInfo createSheet(CreateSheetRequest request) {
        updateLock.lock();
        try {
            var ids = sheets.sheetIds();
            if (ids.containsKey(request.name())) throw new ApiException(HttpStatus.CONFLICT, "A worksheet with that name already exists.");
            if (!ids.containsKey(request.sourceSheet()) || !hasExpectedHeaders(request.sourceSheet()))
                throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a valid expense worksheet to copy.");
            sheets.duplicateSheet(ids.get(request.sourceSheet()), request.name());
            sheets.clearValues(quotedSheet(request.name()) + "!A" + FIRST_DATA_ROW + ":M");
            return new SheetInfo(request.name());
        } finally { updateLock.unlock(); }
    }

    private boolean hasExpectedHeaders(String sheetName) {
        var rows = sheets.readValues(quotedSheet(sheetName) + "!A" + HEADER_ROW + ":M" + HEADER_ROW);
        if (rows.isEmpty()) return false;
        var actual = rows.get(0);
        for (int index = 0; index < EXPECTED_HEADERS.size(); index++) {
            if (index >= actual.size() || !EXPECTED_HEADERS.get(index).equals(actual.get(index).toString().trim())) return false;
        }
        return true;
    }

    private List<SheetRow> loadRows(String sheetName) {
        var raw = sheets.readValues(quotedSheet(sheetName) + "!A" + HEADER_ROW + ":M");
        var notes = sheets.readNotes(quotedSheet(sheetName) + "!A" + HEADER_ROW + ":M");
        if (raw.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "The worksheet header row is missing.");
        validateHeaders(raw.get(0));
        var rows = new ArrayList<SheetRow>();
        for (int index = 1; index < raw.size(); index++) {
            var values = raw.get(index);
            if (values.isEmpty() || values.get(0) == null || values.get(0).toString().isBlank()) continue;
            var date = parseDate(values.get(0));
            var row = new SheetRow(FIRST_DATA_ROW + index - 1, date);
            for (var field : ExpenseField.values()) row.values.put(field, numberAt(values, field.columnIndex()));
            var noteRow = index < notes.size() ? notes.get(index) : List.<String>of();
            for (var field : ExpenseField.values()) row.comments.put(field,
                    field.columnIndex() < noteRow.size() ? noteRow.get(field.columnIndex()) : "");
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

    private void validateChanges(Map<String, BigDecimal> changes, Map<String, String> comments) {
        if (changes.isEmpty() && (comments == null || comments.isEmpty())) throw new ApiException(HttpStatus.BAD_REQUEST, "Change at least one amount or comment before saving.");
        for (var entry : changes.entrySet()) {
            if (!ExpenseField.byKey().containsKey(entry.getKey())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown expense field: " + entry.getKey());
            }
            if (entry.getValue() != null && (entry.getValue().signum() < 0 || entry.getValue().scale() > 2)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Amounts must be non-negative with at most two decimal places.");
            }
        }
        if (comments != null && comments.keySet().stream().anyMatch(key -> !ExpenseField.byKey().containsKey(key)))
            throw new ApiException(HttpStatus.BAD_REQUEST, "A comment has an unknown expense field.");
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

    private BigDecimal allExpenseTotal(SheetRow row) {
        return row.values.values().stream().map(MonthlySpendService::zero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal totalBetween(List<SheetRow> rows, LocalDate start, LocalDate end) {
        return rows.stream().filter(row -> !row.date.isBefore(start) && !row.date.isAfter(end))
                .map(this::allExpenseTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal percentChange(BigDecimal current, BigDecimal previous) {
        if (previous.signum() == 0) return null;
        return current.subtract(previous).multiply(BigDecimal.valueOf(100))
                .divide(previous, 1, RoundingMode.HALF_UP);
    }

    private static String direction(BigDecimal change) {
        return change.signum() >= 0 ? change.abs().toPlainString() + "% higher" : change.abs().toPlainString() + "% lower";
    }

    private void writeRows(String sheetName, List<SheetRow> rows, SheetRow edited, Map<String, BigDecimal> changes,
                           Map<String, String> comments) {
        var updates = new ArrayList<Map<String, Object>>();
        for (var key : changes.keySet()) {
            var field = ExpenseField.byKey().get(key);
            addUpdate(updates, sheetName, field.header(), edited.rowNumber, edited.values.get(field));
        }
        for (var row : rows) {
            addUpdate(updates, sheetName, "Total", row.rowNumber, row.total);
            addUpdate(updates, sheetName, "week Total", row.rowNumber, row.weekTotal);
            addUpdate(updates, sheetName, "Monthly Spend", row.rowNumber, row.monthlySpend);
        }
        sheets.batchUpdateValues(updates);
        if (comments != null && !comments.isEmpty()) {
            var sheetId = sheets.sheetIds().get(sheetName);
            var noteUpdates = new ArrayList<Map<String, Object>>();
            comments.forEach((key, value) -> {
                var field = ExpenseField.byKey().get(key);
                noteUpdates.add(Map.of("updateCells", Map.of(
                        "range", Map.of("sheetId", sheetId, "startRowIndex", edited.rowNumber - 1,
                                "endRowIndex", edited.rowNumber, "startColumnIndex", field.columnIndex(),
                                "endColumnIndex", field.columnIndex() + 1),
                        "rows", List.of(Map.of("values", List.of(Map.of("note", value == null ? "" : value.trim())))),
                        "fields", "note")));
            });
            sheets.batchUpdateSpreadsheet(noteUpdates);
        }
        int lastRow = rows.stream().mapToInt(item -> item.rowNumber).max().orElse(FIRST_DATA_ROW);
        sheets.formatDateColumn(sheets.sheetIds().get(sheetName), FIRST_DATA_ROW - 1, lastRow);
    }

    private void addUpdate(List<Map<String, Object>> updates, String sheetName, String header, int rowNumber, BigDecimal value) {
        int column = EXPECTED_HEADERS.indexOf(header) + 1;
        updates.add(Map.of(
                "range", quotedSheet(sheetName) + "!" + columnName(column) + rowNumber,
                "majorDimension", "ROWS",
                "values", List.of(List.of(value == null ? "" : value))
        ));
    }

    private SpendResponse response(SheetRow row, boolean exists) {
        var values = new LinkedHashMap<String, BigDecimal>();
        var comments = new LinkedHashMap<String, String>();
        for (var field : ExpenseField.values()) values.put(field.key(), row.values.get(field));
        for (var field : ExpenseField.values()) comments.put(field.key(), row.comments.getOrDefault(field, ""));
        return new SpendResponse(row.date, exists, values, comments, row.total, row.weekTotal, row.monthlySpend);
    }

    private SpendResponse emptyResponse(LocalDate date) {
        var values = new LinkedHashMap<String, BigDecimal>();
        for (var field : ExpenseField.values()) values.put(field.key(), null);
        return new SpendResponse(date, false, values, Map.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
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
    private String resolveSheet(String requested) {
        var name = requested == null || requested.isBlank() ? properties.sheetName() : requested.trim();
        if (!sheets.sheetIds().containsKey(name)) throw new ApiException(HttpStatus.BAD_REQUEST, "The selected worksheet does not exist.");
        return name;
    }
    private static String quotedSheet(String name) { return "'" + name.replace("'", "''") + "'"; }
    private static String quote(String name) { return "'" + name.replace("'", "''") + "'"; }
    private static String columnName(int oneBased) { return String.valueOf((char) ('A' + oneBased - 1)); }

    private static final class SheetRow {
        private final int rowNumber;
        private final LocalDate date;
        private final Map<ExpenseField, BigDecimal> values = new LinkedHashMap<>();
        private final Map<ExpenseField, String> comments = new LinkedHashMap<>();
        private BigDecimal total = BigDecimal.ZERO;
        private BigDecimal weekTotal = BigDecimal.ZERO;
        private BigDecimal monthlySpend = BigDecimal.ZERO;

        private SheetRow(int rowNumber, LocalDate date) {
            this.rowNumber = rowNumber;
            this.date = date;
        }
    }
}
