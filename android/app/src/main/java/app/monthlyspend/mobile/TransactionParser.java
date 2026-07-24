package app.monthlyspend.mobile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

final class TransactionParser {
    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:₹|INR|Rs\\.?)[\\s:]*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)|([0-9][0-9,]*(?:\\.[0-9]{1,2})?)[\\s]*(?:INR)");
    private static final Pattern DEBIT = Pattern.compile("(?i)\\b(paid|debited|spent|sent|purchase|purchased|withdrawn|payment successful|transaction successful)\\b");
    private static final Pattern CREDIT = Pattern.compile("(?i)\\b(credited|received|refund(?:ed)?|cashback|deposited)\\b");
    private static final Pattern IMAGE_RECEIPT = Pattern.compile("(?i)\\b(total|receipt|invoice|payment)\\b");
    private static final Pattern OTP = Pattern.compile("(?i)\\b(otp|one[ -]time password|verification code)\\b");
    private static final Pattern MERCHANT = Pattern.compile("(?i)\\b(?:to|at|merchant)[:\\s]+([A-Za-z0-9][A-Za-z0-9 .&_'/-]{1,59})");

    static Candidate parse(String raw, String source, String method, String seed) {
        if (raw == null) return null;
        var text = raw.replace('\u00a0', ' ').replaceAll("[\\t ]+", " ").trim();
        if (text.isBlank() || OTP.matcher(text).find()) return null;
        var amountMatcher = AMOUNT.matcher(text);
        if (!amountMatcher.find()) return null;
        var amountText = amountMatcher.group(1) != null ? amountMatcher.group(1) : amountMatcher.group(2);
        Double amount;
        try { amount = Double.parseDouble(amountText.replace(",", "")); }
        catch (NumberFormatException exception) { return null; }
        if (amount <= 0) return null;

        String type;
        double confidence;
        if (CREDIT.matcher(text).find()) { type = "CREDIT"; confidence = .94; }
        else if (DEBIT.matcher(text).find()) { type = "DEBIT"; confidence = .94; }
        else if ("IMAGE".equals(method) && IMAGE_RECEIPT.matcher(text).find()) { type = "DEBIT"; confidence = .72; }
        else return null;

        var merchant = "";
        var merchantMatcher = MERCHANT.matcher(text);
        if (merchantMatcher.find()) merchant = cleanMerchant(merchantMatcher.group(1));
        var zone = ZoneId.systemDefault();
        var occurred = OffsetDateTime.now(zone);
        var eventId = hash(source + "|" + seed + "|" + type + "|" + amount);
        return new Candidate(UUID.randomUUID().toString(), eventId, type, amount, merchant,
                LocalDate.now(zone).toString(), occurred.toString(), source, method, confidence);
    }

    static String merchantKey(String merchant) {
        return merchant.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String cleanMerchant(String value) {
        var firstLine = value.split("[\\n\\r]", 2)[0];
        return firstLine.replaceAll("(?i)\\b(on|using|ref|upi|a/c|account|txn|transaction)\\b.*$", "").trim();
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    record Candidate(String id, String eventId, String type, Double amount, String merchant,
                     String date, String occurredAt, String source, String captureMethod, double confidence) {}
}
