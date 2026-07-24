package app.monthlyspend.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.result.ActivityResult;
import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.lang.ref.WeakReference;
import java.util.UUID;

@CapacitorPlugin(name = "ExpenseCapture", permissions = {
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
})
public class ExpenseCapturePlugin extends Plugin {
    private static WeakReference<ExpenseCapturePlugin> active = new WeakReference<>(null);
    private CaptureDatabase database;
    private SecureStore secureStore;

    @Override public void load() {
        database = new CaptureDatabase(getContext());
        secureStore = new SecureStore(getContext());
        active = new WeakReference<>(this);
    }

    @PluginMethod public void listDrafts(PluginCall call) {
        var result = new JSObject(); result.put("drafts", database.drafts()); call.resolve(result);
    }

    @PluginMethod public void dismissDraft(PluginCall call) {
        var id = call.getString("id");
        if (id == null) { call.reject("Draft id is required."); return; }
        database.dismiss(id); call.resolve();
    }

    @PluginMethod public void completeDraft(PluginCall call) {
        var id = call.getString("id");
        if (id == null) { call.reject("Draft id is required."); return; }
        database.complete(id, call.getString("merchant", ""), call.getString("category", "")); call.resolve();
    }

    @PluginMethod public void notificationAccess(PluginCall call) {
        var component = new ComponentName(getContext(), PaymentNotificationListener.class);
        var result = new JSObject();
        result.put("granted", NotificationManagerCompat.getEnabledListenerPackages(getContext())
                .contains(component.getPackageName()));
        call.resolve(result);
    }

    @PluginMethod public void openNotificationAccess(PluginCall call) {
        startActivityForResult(call, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), "settingsResult");
    }

    @PluginMethod public void ensureNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33) { call.resolve(); return; }
        requestPermissionForAlias("notifications", call, "notificationPermissionResult");
    }

    @PermissionCallback private void notificationPermissionResult(PluginCall call) { call.resolve(); }

    @ActivityCallback private void settingsResult(PluginCall call, ActivityResult result) {
        if (call != null) call.resolve();
    }

    @PluginMethod public void scanImage(PluginCall call) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 33) intent = new Intent("android.provider.action.PICK_IMAGES").setType("image/*");
        else intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(call, intent, "imageResult");
    }

    @ActivityCallback private void imageResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null
                || result.getData().getData() == null) { call.reject("Image selection cancelled."); return; }
        processImage(getContext(), result.getData().getData(), call);
    }

    @PluginMethod public void saveAccessToken(PluginCall call) {
        try { secureStore.saveToken(call.getString("token", "")); call.resolve(); }
        catch (Exception exception) { call.reject("Could not secure the access token.", exception); }
    }

    @PluginMethod public void getAccessToken(PluginCall call) {
        try { var result = new JSObject(); result.put("token", secureStore.token()); call.resolve(result); }
        catch (Exception exception) { call.reject("Could not read the access token.", exception); }
    }

    @PluginMethod public void deviceId(PluginCall call) {
        var result = new JSObject(); result.put("deviceId", secureStore.deviceId()); call.resolve(result);
    }

    public static void processImage(Context context, Uri uri) { processImage(context, uri, null); }

    private static void processImage(Context context, Uri uri, PluginCall call) {
        try {
            var image = InputImage.fromFilePath(context, uri);
            var recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            recognizer.process(image).addOnSuccessListener(text -> {
                var candidate = TransactionParser.parse(text.getText(), "Shared image", "IMAGE",
                        TransactionParser.hash(text.getText()));
                var created = candidate != null && new CaptureDatabase(context).insert(candidate);
                if (created) { CaptureNotifier.notifyDraft(context, candidate.type()); announceDraft(); }
                if (call != null) { var result = new JSObject(); result.put("created", created); call.resolve(result); }
                recognizer.close();
            }).addOnFailureListener(error -> {
                if (call != null) call.reject("The image could not be read.", error);
                recognizer.close();
            });
        } catch (Exception exception) {
            if (call != null) call.reject("The selected image could not be opened.", exception);
        }
    }

    static void announceDraft() {
        var plugin = active.get();
        if (plugin != null) plugin.notifyListeners("draftAvailable", new JSObject(), true);
    }
}
