package app.monthlyspend.sheet;

import app.monthlyspend.api.ApiException;
import app.monthlyspend.config.AppProperties;
import app.monthlyspend.drive.GoogleDriveOAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

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
    private static final String RECEIPT_FOLDER = "Monthly Spend Receipts";
    private static final String RECEIPT_STORAGE_SHEET = "Receipt Storage";
    private static final int RECEIPT_CHUNK_SIZE = 40_000;
    private static final List<String> RECEIPT_HEADERS = List.of(
            "Receipt ID", "Date", "Source Sheet", "Kind", "File Name", "MIME Type", "Size Bytes",
            "Chunk Index", "Total Chunks", "SHA-256", "Created At", "Base64 Data");
    private static final String FOOD_LOG_SHEET = "Food Log";
    private static final List<String> FOOD_LOG_HEADERS = List.of("Date", "Meal", "Food", "Amount", "Notes", "Receipt File ID", "Recorded At");
    private static final String PRIVATE_SETTINGS_SHEET = "Private Settings";
    private static final List<String> PRIVATE_SETTINGS_HEADERS = List.of("Key", "Value", "Updated At");
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
    private final GoogleDriveOAuthService drive;
    private final ReentrantLock updateLock = new ReentrantLock(true);
    private final Map<String, SpendResponse> recentSubmissions = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, SpendResponse> eldest) {
            return size() > 500;
        }
    };

    public MonthlySpendService(SheetsClient sheets, AppProperties properties, GoogleDriveOAuthService drive) {
        this.sheets = sheets;
        this.properties = properties;
        this.drive = drive;
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

    public MonthlySpendModels.ReceiptResponse uploadReceipt(MultipartFile file, LocalDate date, String requestedSheet, String kind) {
        var sheetName = resolveSheet(requestedSheet);
        if (file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a receipt file to upload.");
        if (file.getSize() > 20L * 1024 * 1024) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Receipt images must be 20 MB or smaller.");
        var contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!List.of("image/jpeg", "image/png", "image/webp").contains(contentType))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Receipts must be JPEG, PNG, or WebP images.");
        try {
            var safeOriginal = file.getOriginalFilename() == null ? "receipt" : file.getOriginalFilename().replaceAll("[^A-Za-z0-9._-]", "_");
            var prepared = prepareReceiptImage(file.getBytes(), contentType);
            var name = date + "_" + safeOriginal.replaceFirst("\\.[^.]+$", "") + ("image/jpeg".equals(prepared.mimeType()) ? ".jpg" : ".webp");
            var bytes = prepared.bytes();
            contentType = prepared.mimeType();
            var receiptId = java.util.UUID.randomUUID().toString();
            var digest = sha256(bytes);
            var normalizedKind = "food".equalsIgnoreCase(kind) ? "food" : "spend";
            var folderId = "food".equals(normalizedKind) ? properties.foodReceiptFolderId() : properties.spendReceiptFolderId();
            var driveId = drive.upload(folderId, name, contentType, bytes, Map.of(
                    "receiptId", receiptId, "date", date.toString(), "sheet", sheetName, "kind", normalizedKind));
            updateLock.lock();
            try {
                if (!sheets.sheetIds().containsKey(RECEIPT_STORAGE_SHEET)) sheets.addSheet(RECEIPT_STORAGE_SHEET);
                sheets.batchUpdateValues(List.of(Map.of("range", quote(RECEIPT_STORAGE_SHEET) + "!A1:L1",
                        "majorDimension", "ROWS", "values", List.of(RECEIPT_HEADERS))));
                sheets.appendRow(quote(RECEIPT_STORAGE_SHEET) + "!A:L", List.of(
                        receiptId, date.toString(), "'" + sheetName, normalizedKind, name, contentType,
                        bytes.length, 0, 0, digest, OffsetDateTime.now().toString(), "drive:" + driveId));
            } finally { updateLock.unlock(); }
            return new MonthlySpendModels.ReceiptResponse(receiptId, name, "Google Drive");
        } catch (java.io.IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not read the uploaded receipt.");
        }
    }

    private ReceiptPayload prepareReceiptImage(byte[] original, String contentType) {
        try {
            var source = ImageIO.read(new ByteArrayInputStream(original));
            if (source == null) {
                if (original.length <= 750 * 1024 && "image/webp".equals(contentType)) return new ReceiptPayload(original, contentType);
                throw new ApiException(HttpStatus.BAD_REQUEST, "This image format could not be decoded. Use JPEG or PNG.");
            }
            var scale = Math.min(1d, 1280d / Math.max(source.getWidth(), source.getHeight()));
            var width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            var height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            var output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var graphics = output.createGraphics();
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null); graphics.dispose();
            var bytes = new ByteArrayOutputStream();
            var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try (var imageOutput = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(imageOutput); var parameters = writer.getDefaultWriteParam();
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); parameters.setCompressionQuality(.72f);
                writer.write(null, new IIOImage(output, null, null), parameters);
            } finally { writer.dispose(); }
            if (bytes.size() > 750 * 1024) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "The compressed image is still too large for Sheets storage.");
            return new ReceiptPayload(bytes.toByteArray(), "image/jpeg");
        } catch (ApiException exception) { throw exception;
        } catch (Exception exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "The receipt image could not be processed."); }
    }

    private record ReceiptPayload(byte[] bytes, String mimeType) {}

    public List<MonthlySpendModels.ReceiptImage> receipts(LocalDate date, String requestedSheet) {
        var sheetName = resolveSheet(requestedSheet);
        if (!sheets.sheetIds().containsKey(RECEIPT_STORAGE_SHEET)) return List.of();
        var rows = sheets.readValues(quote(RECEIPT_STORAGE_SHEET) + "!A2:L");
        var grouped = new LinkedHashMap<String, List<List<Object>>>();
        for (var row : rows) {
            if (row.size() < 12 || (date != null && !date.equals(parseDate(row.get(1)))) || !matchesReceiptSheet(sheetName, row.get(2))) continue;
            grouped.computeIfAbsent(row.get(0).toString(), ignored -> new ArrayList<>()).add(row);
        }
        var result = new ArrayList<MonthlySpendModels.ReceiptImage>();
        for (var entry : grouped.entrySet()) {
            var chunks = entry.getValue().stream().sorted(Comparator.comparingInt(row -> Integer.parseInt(row.get(7).toString()))).toList();
            var first = chunks.get(0);
            String encoded;
            var storage = first.get(11).toString();
            if (storage.startsWith("drive:")) encoded = Base64.getEncoder().encodeToString(drive.download(storage.substring(6)));
            else {
                var builder = new StringBuilder();
                chunks.forEach(row -> builder.append(row.get(11).toString()));
                encoded = builder.toString();
            }
            result.add(new MonthlySpendModels.ReceiptImage(entry.getKey(), parseDate(first.get(1)), first.get(3).toString(),
                    first.get(5).toString(), "data:" + first.get(5) + ";base64," + encoded));
        }
        return result;
    }

    private boolean matchesReceiptSheet(String sheetName, Object stored) {
        if (sheetName.equals(stored.toString())) return true;
        if (!(stored instanceof Number)) return false;
        try {
            var expected = YearMonth.parse(sheetName.trim(), DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH));
            return expected.equals(YearMonth.from(parseDate(stored)));
        } catch (DateTimeParseException exception) { return false; }
    }

    public MonthlySpendModels.FoodLogResponse logFood(MonthlySpendModels.FoodLogRequest request) {
        updateLock.lock();
        try {
            var ids = sheets.sheetIds();
            if (!ids.containsKey(FOOD_LOG_SHEET)) {
                sheets.addSheet(FOOD_LOG_SHEET);
            }
            sheets.batchUpdateValues(List.of(Map.of("range", quote(FOOD_LOG_SHEET) + "!A1:G1",
                    "majorDimension", "ROWS", "values", List.of(FOOD_LOG_HEADERS))));
            sheets.appendRow(quote(FOOD_LOG_SHEET) + "!A:G", List.of(
                    dateFormula(request.date()), request.meal(), request.food().trim(), request.amount(),
                    request.notes() == null ? "" : request.notes().trim(),
                    request.receiptFileId() == null ? "" : request.receiptFileId(), OffsetDateTime.now().toString()));
            return new MonthlySpendModels.FoodLogResponse("CREATED", request.date(), request.meal(), request.food().trim());
        } finally { updateLock.unlock(); }
    }

    public MonthlySpendModels.PrivateIncomeResponse savePrivateIncome(MonthlySpendModels.PrivateIncomeRequest request) {
        updateLock.lock();
        try {
            var settings = loadPrivateSettings();
            var prefix = "income." + request.month() + ".";
            var existingSalt = settings.get(prefix + "salt");
            if (existingSalt != null) verifyPassphrase(properties.accessToken(), existingSalt, settings.get(prefix + "verifier"));
            var salt = existingSalt == null ? randomBytes(16) : Base64.getDecoder().decode(existingSalt);
            var key = deriveKey(properties.accessToken(), salt);
            var iv = randomBytes(12);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            var encrypted = cipher.doFinal(request.income().toPlainString().getBytes(StandardCharsets.UTF_8));
            upsertPrivateSettings(Map.of(
                    prefix + "salt", Base64.getEncoder().encodeToString(salt),
                    prefix + "verifier", verifier(key),
                    prefix + "cipher", Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(encrypted)));
            return new MonthlySpendModels.PrivateIncomeResponse(request.income());
        } catch (ApiException exception) { throw exception;
        } catch (Exception exception) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not secure the income value.");
        } finally { updateLock.unlock(); }
    }

    public MonthlySpendModels.PrivateIncomeResponse unlockPrivateIncome(MonthlySpendModels.PrivateIncomeUnlockRequest request) {
        try {
            var settings = loadPrivateSettings();
            var prefix = "income." + request.month() + ".";
            var saltText = settings.get(prefix + "salt");
            var cipherText = settings.get(prefix + "cipher");
            if (saltText == null || cipherText == null) throw new ApiException(HttpStatus.NOT_FOUND, "No private income has been saved yet.");
            var key = verifyPassphrase(properties.accessToken(), saltText, settings.get(prefix + "verifier"));
            var parts = cipherText.split(":", 2);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            var value = new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
            return new MonthlySpendModels.PrivateIncomeResponse(new BigDecimal(value));
        } catch (ApiException exception) { throw exception;
        } catch (Exception exception) { throw new ApiException(HttpStatus.UNAUTHORIZED, "The income passphrase is incorrect."); }
    }

    private Map<String, String> loadPrivateSettings() {
        if (!sheets.sheetIds().containsKey(PRIVATE_SETTINGS_SHEET)) return new LinkedHashMap<>();
        var rows = sheets.readValues(quote(PRIVATE_SETTINGS_SHEET) + "!A2:C");
        var result = new LinkedHashMap<String, String>();
        for (var row : rows) if (row.size() >= 2) result.put(row.get(0).toString(), row.get(1).toString());
        return result;
    }

    private void upsertPrivateSettings(Map<String, String> changes) {
        var ids = sheets.sheetIds();
        if (!ids.containsKey(PRIVATE_SETTINGS_SHEET)) {
            sheets.addSheet(PRIVATE_SETTINGS_SHEET);
            sheets.batchUpdateValues(List.of(Map.of("range", quote(PRIVATE_SETTINGS_SHEET) + "!A1:C1",
                    "majorDimension", "ROWS", "values", List.of(PRIVATE_SETTINGS_HEADERS))));
        }
        var rows = sheets.readValues(quote(PRIVATE_SETTINGS_SHEET) + "!A2:C");
        var rowByKey = new HashMap<String, Integer>();
        for (int index = 0; index < rows.size(); index++) if (!rows.get(index).isEmpty()) rowByKey.put(rows.get(index).get(0).toString(), index + 2);
        var updates = new ArrayList<Map<String, Object>>();
        for (var entry : changes.entrySet()) {
            var row = rowByKey.get(entry.getKey());
            if (row == null) sheets.appendRow(quote(PRIVATE_SETTINGS_SHEET) + "!A:C", List.of(entry.getKey(), entry.getValue(), OffsetDateTime.now().toString()));
            else updates.add(Map.of("range", quote(PRIVATE_SETTINGS_SHEET) + "!B" + row + ":C" + row,
                    "majorDimension", "ROWS", "values", List.of(List.of(entry.getValue(), OffsetDateTime.now().toString()))));
        }
        if (!updates.isEmpty()) sheets.batchUpdateValues(updates);
    }

    private static SecretKeySpec verifyPassphrase(String passphrase, String saltText, String expected) {
        var key = deriveKey(passphrase, Base64.getDecoder().decode(saltText));
        if (expected == null || !MessageDigest.isEqual(verifier(key).getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8)))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "The income passphrase is incorrect.");
        return key;
    }
    private static SecretKeySpec deriveKey(String passphrase, byte[] salt) {
        try {
            var spec = new PBEKeySpec(passphrase.toCharArray(), salt, 210_000, 256);
            return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static String verifier(SecretKeySpec key) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(key.getEncoded())); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static String sha256(byte[] value) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 is not available.", exception); }
    }
    private static byte[] randomBytes(int size) { var bytes = new byte[size]; new SecureRandom().nextBytes(bytes); return bytes; }

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
