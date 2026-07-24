package app.monthlyspend.api;

import app.monthlyspend.sheet.MonthlySpendModels.SpendResponse;
import app.monthlyspend.sheet.MonthlySpendModels.UpdateRequest;
import app.monthlyspend.sheet.MonthlySpendService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/monthly-spend")
public class MonthlySpendController {
    private final MonthlySpendService service;

    public MonthlySpendController(MonthlySpendService service) {
        this.service = service;
    }

    @GetMapping
    SpendResponse get(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.get(date);
    }

    @PutMapping
    SpendResponse update(@Valid @RequestBody UpdateRequest request) {
        return service.update(request);
    }
}

