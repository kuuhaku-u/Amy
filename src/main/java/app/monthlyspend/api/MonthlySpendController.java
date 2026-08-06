package app.monthlyspend.api;

import app.monthlyspend.sheet.MonthlySpendModels.SpendResponse;
import app.monthlyspend.sheet.MonthlySpendModels.AnalyticsResponse;
import app.monthlyspend.sheet.MonthlySpendModels.UpdateRequest;
import app.monthlyspend.sheet.MonthlySpendModels.TransactionRequest;
import app.monthlyspend.sheet.MonthlySpendModels.TransactionResponse;
import app.monthlyspend.sheet.MonthlySpendModels;
import app.monthlyspend.sheet.MonthlySpendService;
import app.monthlyspend.drive.GoogleDriveOAuthService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/monthly-spend")
public class MonthlySpendController {
    private final MonthlySpendService service;
    private final GoogleDriveOAuthService drive;

    public MonthlySpendController(MonthlySpendService service, GoogleDriveOAuthService drive) {
        this.service = service;
        this.drive = drive;
    }

    @GetMapping
    SpendResponse get(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                      @RequestParam(required = false) String sheet) {
        return service.get(date, sheet);
    }

    @PutMapping
    SpendResponse update(@Valid @RequestBody UpdateRequest request) {
        return service.update(request);
    }

    @GetMapping("/analytics")
    AnalyticsResponse analytics(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                @RequestParam(required = false) String sheet) {
        return service.analytics(date, sheet);
    }

    @GetMapping("/sheets")
    List<MonthlySpendModels.SheetInfo> sheets() { return service.sheets(); }

    @PostMapping("/sheets")
    MonthlySpendModels.SheetInfo createSheet(@Valid @RequestBody MonthlySpendModels.CreateSheetRequest request) {
        return service.createSheet(request);
    }

    @GetMapping("/history")
    List<MonthlySpendModels.HistoryEntry> history(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String sheet) {
        return service.history(from, to, sheet);
    }

    @GetMapping("/cashflow")
    MonthlySpendModels.CashflowResponse cashflow() { return service.cashflow(); }

    @PostMapping(value = "/receipts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    MonthlySpendModels.ReceiptResponse uploadReceipt(
            @RequestPart("file") MultipartFile file,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("sheet") String sheet,
            @RequestParam(value = "kind", required = false) String kind) {
        return service.uploadReceipt(file, date, sheet, kind);
    }

    @GetMapping("/receipts")
    List<MonthlySpendModels.ReceiptImage> receipts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("sheet") String sheet) {
        return service.receipts(date, sheet);
    }

    @GetMapping("/drive/status")
    MonthlySpendModels.DriveConnection driveStatus() {
        return new MonthlySpendModels.DriveConnection(drive.configured(), drive.connected(),
                drive.configured() && !drive.connected() ? drive.authorizationUrl() : "");
    }

    @PostMapping("/food-log")
    MonthlySpendModels.FoodLogResponse foodLog(@Valid @RequestBody MonthlySpendModels.FoodLogRequest request) {
        return service.logFood(request);
    }

    @PostMapping("/private-income")
    MonthlySpendModels.PrivateIncomeResponse savePrivateIncome(@Valid @RequestBody MonthlySpendModels.PrivateIncomeRequest request) {
        return service.savePrivateIncome(request);
    }

    @PostMapping("/private-income/unlock")
    MonthlySpendModels.PrivateIncomeResponse unlockPrivateIncome(@Valid @RequestBody MonthlySpendModels.PrivateIncomeUnlockRequest request) {
        return service.unlockPrivateIncome(request);
    }

    @PostMapping("/transactions")
    TransactionResponse transaction(@Valid @RequestBody TransactionRequest request) {
        return service.recordTransaction(request);
    }
}
