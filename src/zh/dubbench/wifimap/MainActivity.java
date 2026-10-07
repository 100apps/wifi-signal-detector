package zh.dubbench.wifimap;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
  private WebView web;
  private TextView status;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean started = false;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setBackgroundColor(Color.rgb(9, 18, 29));
    status = new TextView(this);
    status.setTextColor(Color.WHITE);
    status.setTextSize(13);
    status.setPadding(16, 12, 16, 12);
    status.setText("准备定位权限…");
    status.setTextIsSelectable(true);
    status.setMovementMethod(LinkMovementMethod.getInstance());
    layout.addView(status);
    LinearLayout buttons = new LinearLayout(this);
    Button start = new Button(this); start.setText("启动采集");
    start.setOnClickListener(v -> ensurePermission());
    buttons.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
    Button stop = new Button(this); stop.setText("停止采集");
    stop.setOnClickListener(v -> {
      stopService(new Intent(this, CollectorService.class));
      status.setText("采集已停止。点击“启动采集”重新开始。");
      started = false;
    });
    buttons.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
    layout.addView(buttons);
    web = new WebView(this);
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    web.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        if (url.startsWith("http://127.0.0.1:8765/")) return false;
        startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
        return true;
      }
    });
    web.addJavascriptInterface(new ExportBridge(), "AndroidBridge");
    layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(layout);
    ensurePermission();
  }

  private void ensurePermission() {
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }, 1);
      return;
    }
    start();
  }

  @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) start();
    else status.setText("需要授权“精确位置”才能读取 GPS 和 Wi-Fi 信号强度。");
  }

  private void start() {
    if (started) return;
    started = true;
    Intent intent = new Intent(this, CollectorService.class);
    startForegroundService(intent);
    String token = CollectorService.token(this);
    String ip = ipAddress();
    status.setText("本机服务：http://127.0.0.1:8765/?key=" + token + "\n同一 Wi-Fi 访问：http://" + ip + ":8765/?key=" + token);
    handler.postDelayed(() -> web.loadUrl("http://127.0.0.1:8765/?key=" + token), 750);
  }

  private String ipAddress() {
    WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    int ip = manager.getConnectionInfo().getIpAddress();
    if (ip == 0) return "手机局域网 IP";
    return (ip & 255) + "." + ((ip >> 8) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 24) & 255);
  }

  @Override public void onBackPressed() {
    if (web.canGoBack()) web.goBack();
    else super.onBackPressed();
  }
  @Override protected void onDestroy() {
    web.destroy();
    super.onDestroy();
  }

  private class ExportBridge {
    @JavascriptInterface public void savePng(String dataUrl) {
      if (dataUrl == null || !dataUrl.startsWith("data:image/png;base64,") || dataUrl.length() > 12000000) return;
      try {
        byte[] png = Base64.decode(dataUrl.substring(22), Base64.DEFAULT);
        String name = "wifi-signal-detector-" + System.currentTimeMillis() + ".png";
        if (Build.VERSION.SDK_INT >= 29) {
          ContentValues values = new ContentValues();
          values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
          values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
          values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WifiSignalDetector");
          values.put(MediaStore.Images.Media.IS_PENDING, 1);
          android.net.Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
          if (uri == null) throw new IllegalStateException("无法创建图片");
          try (OutputStream out = getContentResolver().openOutputStream(uri)) { out.write(png); }
          values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
          getContentResolver().update(uri, values, null, null);
        } else {
          File file = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), name);
          try (FileOutputStream out = new FileOutputStream(file)) { out.write(png); }
        }
        runOnUiThread(() -> Toast.makeText(MainActivity.this, "已保存到图片/WifiSignalDetector", Toast.LENGTH_LONG).show());
      } catch (Exception e) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, "图片保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
      }
    }
  }
}
