package app.monthlyspend.sheet;

import java.util.LinkedHashMap;
import java.util.Map;

public enum ExpenseField {
    TRAVEL("travel", "Travel", 1),
    BREAKFAST("breakfast", "Breakfast", 2),
    LUNCH("lunch", "Lunch", 3),
    EVE_SNACK("eveSnack", "Eve Snack", 4),
    DINNER("dinner", "Dinner", 5),
    ORDER("order", "Order", 6),
    OTHERS("others", "Others", 7),
    HOME("home", "Home", 10),
    RENT("rent", "Rent", 11);

    private final String key;
    private final String header;
    private final int columnIndex;

    ExpenseField(String key, String header, int columnIndex) {
        this.key = key;
        this.header = header;
        this.columnIndex = columnIndex;
    }

    public String key() { return key; }
    public String header() { return header; }
    public int columnIndex() { return columnIndex; }
    public int columnNumber() { return columnIndex + 1; }

    public static Map<String, ExpenseField> byKey() {
        var result = new LinkedHashMap<String, ExpenseField>();
        for (var field : values()) result.put(field.key, field);
        return result;
    }
}

