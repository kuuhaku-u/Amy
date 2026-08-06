package app.monthlyspend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String spreadsheetId,
        String sheetName,
        String accessToken,
        String googleCredentialsJson,
        String googleCredentialsFile,
        String corsAllowedOrigins,
        String receiptFolderId,
        String foodReceiptFolderId,
        String spendReceiptFolderId
) {}
