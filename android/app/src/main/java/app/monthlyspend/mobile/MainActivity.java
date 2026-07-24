package app.monthlyspend.mobile;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ExpenseCapturePlugin.class);
        super.onCreate(savedInstanceState);
        handleSharedImage(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleSharedImage(intent);
    }

    private void handleSharedImage(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction()) || intent.getType() == null
                || !intent.getType().startsWith("image/")) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) ExpenseCapturePlugin.processImage(this, uri);
        intent.setAction(null);
    }
}
