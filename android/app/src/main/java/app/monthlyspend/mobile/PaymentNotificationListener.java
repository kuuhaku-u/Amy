package app.monthlyspend.mobile;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class PaymentNotificationListener extends NotificationListenerService {
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn.getPackageName().equals(getPackageName())) return;
        Bundle extras = sbn.getNotification().extras;
        var text = new StringBuilder();
        add(text, extras.getCharSequence(Notification.EXTRA_TITLE));
        add(text, extras.getCharSequence(Notification.EXTRA_TEXT));
        add(text, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        add(text, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        var candidate = TransactionParser.parse(text.toString(), sbn.getPackageName(), "NOTIFICATION",
                sbn.getKey() + "|" + sbn.getPostTime());
        if (candidate == null) return;
        if (new CaptureDatabase(this).insert(candidate)) {
            CaptureNotifier.notifyDraft(this, candidate.type());
            ExpenseCapturePlugin.announceDraft();
        }
    }

    private static void add(StringBuilder target, CharSequence value) {
        if (value != null && !value.toString().isBlank()) target.append(value).append('\n');
    }
}
