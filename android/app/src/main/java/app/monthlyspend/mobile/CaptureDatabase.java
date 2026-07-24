package app.monthlyspend.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

final class CaptureDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "expense-capture.db";
    private static final int DB_VERSION = 1;

    CaptureDatabase(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE drafts (id TEXT PRIMARY KEY, event_id TEXT NOT NULL UNIQUE, type TEXT NOT NULL, " +
                "amount REAL, merchant TEXT NOT NULL, date TEXT NOT NULL, occurred_at TEXT NOT NULL, source TEXT NOT NULL, " +
                "capture_method TEXT NOT NULL, category TEXT, confidence REAL NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE merchant_rules (merchant_key TEXT PRIMARY KEY, category TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    boolean insert(TransactionParser.Candidate candidate) {
        var values = new ContentValues();
        values.put("id", candidate.id());
        values.put("event_id", candidate.eventId());
        values.put("type", candidate.type());
        if (candidate.amount() != null) values.put("amount", candidate.amount());
        else values.putNull("amount");
        values.put("merchant", candidate.merchant());
        values.put("date", candidate.date());
        values.put("occurred_at", candidate.occurredAt());
        values.put("source", candidate.source());
        values.put("capture_method", candidate.captureMethod());
        var suggested = categoryFor(candidate.merchant());
        if (suggested != null) values.put("category", suggested);
        else values.putNull("category");
        values.put("confidence", candidate.confidence());
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict("drafts", null, values,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    JSArray drafts() {
        var result = new JSArray();
        try (Cursor cursor = getReadableDatabase().query("drafts", null, null, null,
                null, null, "created_at DESC")) {
            while (cursor.moveToNext()) {
                var item = new JSObject();
                item.put("id", text(cursor, "id"));
                item.put("eventId", text(cursor, "event_id"));
                item.put("type", text(cursor, "type"));
                int amountIndex = cursor.getColumnIndexOrThrow("amount");
                item.put("amount", cursor.isNull(amountIndex) ? null : cursor.getDouble(amountIndex));
                item.put("merchant", text(cursor, "merchant"));
                item.put("date", text(cursor, "date"));
                item.put("occurredAt", text(cursor, "occurred_at"));
                item.put("source", text(cursor, "source"));
                item.put("captureMethod", text(cursor, "capture_method"));
                int categoryIndex = cursor.getColumnIndexOrThrow("category");
                item.put("category", cursor.isNull(categoryIndex) ? null : cursor.getString(categoryIndex));
                item.put("confidence", cursor.getDouble(cursor.getColumnIndexOrThrow("confidence")));
                result.put(item);
            }
        }
        return result;
    }

    void dismiss(String id) {
        getWritableDatabase().delete("drafts", "id = ?", new String[]{id});
    }

    void complete(String id, String merchant, String category) {
        var db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (merchant != null && !merchant.isBlank() && category != null && !category.isBlank()) {
                var values = new ContentValues();
                values.put("merchant_key", TransactionParser.merchantKey(merchant));
                values.put("category", category);
                db.insertWithOnConflict("merchant_rules", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.delete("drafts", "id = ?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private String categoryFor(String merchant) {
        if (merchant == null || merchant.isBlank()) return null;
        try (Cursor cursor = getReadableDatabase().query("merchant_rules", new String[]{"category"},
                "merchant_key = ?", new String[]{TransactionParser.merchantKey(merchant)}, null, null, null)) {
            if (cursor.moveToFirst()) return cursor.getString(0);
        }
        var normalized = merchant.toLowerCase();
        if (normalized.matches(".*(uber|ola|rapido|metro|irctc|fuel|petrol).*")) return "travel";
        if (normalized.matches(".*(swiggy|zomato|restaurant|cafe|food).*")) return "order";
        if (normalized.matches(".*(rent|landlord).*")) return "rent";
        return null;
    }

    private static String text(Cursor cursor, String name) {
        return cursor.getString(cursor.getColumnIndexOrThrow(name));
    }
}
