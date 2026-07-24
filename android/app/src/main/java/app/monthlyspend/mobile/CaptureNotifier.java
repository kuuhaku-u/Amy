package app.monthlyspend.mobile;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

final class CaptureNotifier {
    private static final String CHANNEL = "expense-drafts";

    static void notifyDraft(Context context, String type) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        var manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Expense drafts",
                NotificationManager.IMPORTANCE_DEFAULT));
        var intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        var pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        var notification = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_currency_rupee)
                .setContentTitle("CREDIT".equals(type) ? "Credit detected" : "Possible expense detected")
                .setContentText("Open Monthly Spend to review it before saving.")
                .setContentIntent(pending).setAutoCancel(true).build();
        manager.notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), notification);
    }
}
