package app.monthlyspend.api;

import app.monthlyspend.drive.GoogleDriveOAuthService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GoogleDriveOAuthController {
    private final GoogleDriveOAuthService drive;
    public GoogleDriveOAuthController(GoogleDriveOAuthService drive) { this.drive = drive; }

    @GetMapping(value = "/oauth/google-drive/callback", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> callback(@RequestParam String code, @RequestParam String state) {
        drive.complete(code, state);
        return ResponseEntity.ok("<!doctype html><meta name=viewport content='width=device-width'><title>Drive connected</title>"
                + "<body style='font-family:sans-serif;padding:40px;text-align:center'><h2>Google Drive connected</h2>"
                + "<p>You can close this window and return to Monthly Spend.</p><script>setTimeout(()=>window.close(),1200)</script></body>");
    }
}
