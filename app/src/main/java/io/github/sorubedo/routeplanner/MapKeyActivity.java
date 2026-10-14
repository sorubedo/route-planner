package io.github.sorubedo.routeplanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.security.MessageDigest;
import java.util.Locale;

public final class MapKeyActivity extends Activity {
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout root, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextIsSelectable(true);
        view.setPadding(0, dp(8), 0, dp(8)); root.addView(view); return view;
    }
    private Button button(LinearLayout root, String label) {
        Button button = new Button(this); button.setText(label); root.addView(button); return button;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20)); scroll.addView(root); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        text(root, "腾讯地图设置", 24);
        text(root, "填写你自己的腾讯位置服务 Android SDK Key，用于地图与步行路线。请在腾讯位置服务控制台为该 Key 开启 Android 地图 SDK 权限，填写下方包名和当前安装包的签名 SHA-1，并确认步行路线服务可用。", 15);
        String registration = "包名：" + getPackageName() + "\n签名 SHA-1：" + certificateSha1();
        text(root, registration, 14);
        button(root, "复制授权信息").setOnClickListener(v -> {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("腾讯地图授权信息", registration));
            Toast.makeText(this, "包名与签名 SHA-1 已复制", Toast.LENGTH_SHORT).show();
        });
        EditText input = new EditText(this);
        input.setHint("输入腾讯地图 Android SDK Key"); input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setSaveEnabled(false); root.addView(input);
        TextView status = text(root, "", 14);
        try { input.setText(MapKeyStore.get(this)); }
        catch (Exception error) { status.setText("无法读取已保存的 Key，请重新填写或清除。"); }
        Switch visible = new Switch(this); visible.setText("显示 Key"); root.addView(visible);
        visible.setOnCheckedChangeListener((v, checked) -> {
            input.setInputType(InputType.TYPE_CLASS_TEXT | (checked
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            input.setSelection(input.length());
        });
        text(root, "Key 只保存在本机，供腾讯地图服务使用，不包含在路线导出中。保存后地图会重新加载；清除后可继续导入数据与几何规划。", 14);
        button(root, "保存并返回").setOnClickListener(v -> {
            String key = input.getText().toString().trim();
            if (!key.matches("[A-Za-z0-9-]{10,128}")) {
                input.setError("请输入完整的腾讯地图 Key，不要包含空格"); return;
            }
            try { MapKeyStore.save(this, key); setResult(RESULT_OK); finish(); }
            catch (Exception error) { status.setText("保存失败，请重试。"); }
        });
        button(root, "清除已保存的 Key").setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("清除腾讯地图 Key？").setMessage("地图与步行道路规划将暂停，已保存的区域和路线点会保留。")
                .setNegativeButton("取消", null).setPositiveButton("清除", (d, w) -> {
                    try { MapKeyStore.clear(this); setResult(RESULT_OK); finish(); }
                    catch (Exception error) { status.setText("清除失败，请重试。"); }
                }).show());
        button(root, "返回").setOnClickListener(v -> finish());
    }
    @SuppressWarnings("deprecation")
    private String certificateSha1() {
        try {
            Signature[] certificates;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                certificates = info.signingInfo.getApkContentsSigners();
            } else certificates = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES).signatures;
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(certificates[0].toByteArray());
            StringBuilder result = new StringBuilder();
            for (byte value : digest) {
                if (result.length() > 0) result.append(':');
                result.append(String.format(Locale.ROOT, "%02X", value & 255));
            }
            return result.toString();
        } catch (Exception error) { return "无法读取"; }
    }
}
