package app.monthlyspend.mobile;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureStore {
    private static final String ALIAS = "monthly-spend-access";
    private static final String PREFS = "secure-capture";
    private final Context context;

    SecureStore(Context context) { this.context = context.getApplicationContext(); }

    void saveToken(String token) throws Exception {
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        var encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        var encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        prefs().edit().putString("token", encoded).apply();
    }

    String token() throws Exception {
        var encoded = prefs().getString("token", "");
        if (encoded.isBlank()) return "";
        var parts = encoded.split(":", 2);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    String deviceId() {
        var existing = prefs().getString("deviceId", "");
        if (!existing.isBlank()) return existing;
        var created = "android_" + UUID.randomUUID().toString().replace("-", "");
        prefs().edit().putString("deviceId", created).apply();
        return created;
    }

    private SecretKey key() throws Exception {
        var keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) return (SecretKey) keyStore.getKey(ALIAS, null);
        var generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
