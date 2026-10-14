package io.github.sorubedo.routeplanner;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private credentials encrypted with a non-exportable Android Keystore key. */
final class MapKeyStore {
    private static final String ALIAS = "route-planner-map-key";
    private static final String PREFS = "map-credentials";

    private static SecretKey encryptionKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    static String get(Context context) throws Exception {
        String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("key", "");
        if (encoded.isEmpty()) return "";
        String[] parts = encoded.split(":", 2);
        if (parts.length != 2) throw new java.io.IOException("地图 Key 存储已损坏，请重新填写");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    static void save(Context context, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey());
        String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(cipher.doFinal(key.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("key", encoded).commit())
            throw new java.io.IOException("无法保存地图 Key，请重试");
    }

    static void clear(Context context) throws Exception {
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit())
            throw new java.io.IOException("无法清除地图 Key，请重试");
    }
}
