package zh.dubbench.wifimap;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CollectorService extends Service {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ArrayList<JSONObject> history = new ArrayList<>();
  private Location gps, network;
  private LocationManager locations;
  private WifiManager wifi;
  private PowerManager.WakeLock wakeLock;
  private WebServer server;
  private String error = "等待定位…";
  private final LocationListener gpsListener = location -> gps = location;
  private final LocationListener networkListener = location -> network = location;
  private final Runnable poll = new Runnable() {
    @Override public void run() {
      collect();
      handler.postDelayed(this, 500);
    }
  };

  static String token(Context context) {
    android.content.SharedPreferences prefs = context.getSharedPreferences("access", MODE_PRIVATE);
    String existing = prefs.getString("token", null);
    if (existing != null) return existing;
    byte[] bytes = new byte[24]; new SecureRandom().nextBytes(bytes);
    StringBuilder builder = new StringBuilder();
    for (byte b : bytes) builder.append(String.format("%02x", b & 255));
    String created = builder.toString();
    prefs.edit().putString("token", created).apply();
    return created;
  }

  @Override public void onCreate() {
    super.onCreate();
    NotificationChannel channel = new NotificationChannel("collector", "信号采集", NotificationManager.IMPORTANCE_LOW);
    ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    Intent open = new Intent(this, MainActivity.class);
    PendingIntent action = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification notification = new Notification.Builder(this, "collector")
      .setContentTitle("WiFi 信号探测器正在采集")
      .setContentText("实时记录手机位置与当前 Wi-Fi 信号")
      .setSmallIcon(android.R.drawable.ic_menu_mylocation)
      .setContentIntent(action).setOngoing(true).build();
    startForeground(1, notification);
    PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
    wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiMap:Collector");
    wakeLock.acquire();
    wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
    locations = (LocationManager) getSystemService(LOCATION_SERVICE);
    loadHistory();
    try { server = new WebServer(); server.start(); }
    catch (Exception e) { error = "网页服务启动失败：" + e.getMessage(); }
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      try { locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, gpsListener, Looper.getMainLooper()); }
      catch (Exception e) { error = "GPS 无法启动：" + e.getMessage(); }
      try { locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 0f, networkListener, Looper.getMainLooper()); }
      catch (Exception ignored) { }
      handler.post(poll);
    } else {
      error = "没有精确位置权限；仅采集 Wi-Fi 信号";
      handler.post(poll);
    }
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
  @Override public IBinder onBind(Intent intent) { return null; }

  private void collect() {
    Location chosen = bestLocation();
    WifiInfo info;
    try { info = wifi.getConnectionInfo(); }
    catch (Exception e) { error = "Wi-Fi 状态读取失败：" + e.getMessage(); return; }
    if (info == null || info.getRssi() <= -127 || info.getRssi() > 0) { error = "等待已连接的 Wi-Fi 信号…"; return; }
    try {
      JSONObject sample = new JSONObject();
      sample.put("time", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).format(new java.util.Date()));
      sample.put("lat", chosen == null ? JSONObject.NULL : chosen.getLatitude());
      sample.put("lon", chosen == null ? JSONObject.NULL : chosen.getLongitude());
      sample.put("accuracy", chosen == null ? JSONObject.NULL : chosen.hasAccuracy() ? chosen.getAccuracy() : 9999);
      sample.put("altitude", chosen != null && chosen.hasAltitude() ? chosen.getAltitude() : JSONObject.NULL);
      sample.put("verticalAccuracy", chosen != null && chosen.hasVerticalAccuracy() ? chosen.getVerticalAccuracyMeters() : JSONObject.NULL);
      sample.put("locationFixTime", chosen == null ? JSONObject.NULL : chosen.getTime());
      sample.put("locationAgeMs", chosen == null ? JSONObject.NULL : Math.max(0, System.currentTimeMillis() - chosen.getTime()));
      sample.put("rssi", info.getRssi());
      String ssid = info.getSSID();
      sample.put("ssid", ssid == null || "<unknown ssid>".equals(ssid) ? "当前 Wi-Fi" : ssid.replace("\"", ""));
      sample.put("source", chosen == null ? "等待定位" : chosen == gps ? "GPS" : "网络定位");
      synchronized (history) {
        history.add(sample);
        if (history.size() > 3000) history.remove(0);
      }
      persist(sample);
      if (server != null) server.broadcast("{\"type\":\"sample\",\"sample\":" + sample + "}");
      error = chosen == null ? "信号采集中，等待定位" : "采集中";
    } catch (Exception e) { error = "采样失败：" + e.getMessage(); }
  }

  private Location bestLocation() {
    long now = System.currentTimeMillis();
    Location best = null;
    double bestScore = Double.POSITIVE_INFINITY;
    for (Location candidate : new Location[]{gps, network}) {
      if (candidate == null) continue;
      long age = now - candidate.getTime();
      if (age < 0 || age > 30000) continue;
      double score = (candidate.hasAccuracy() ? candidate.getAccuracy() : 9999) + age / 1000.0 * 1.5;
      if (score < bestScore) { best = candidate; bestScore = score; }
    }
    return best;
  }

  private File samplesFile() { return new File(getFilesDir(), "samples.jsonl"); }
  private void loadHistory() {
    File file = samplesFile();
    if (!file.exists()) return;
    try {
      java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
      String line;
      while ((line = reader.readLine()) != null) {
        try { history.add(new JSONObject(line)); if (history.size() > 3000) history.remove(0); }
        catch (Exception ignored) { }
      }
      reader.close();
    } catch (Exception ignored) { }
  }
  private void persist(JSONObject sample) {
    try (FileOutputStream out = new FileOutputStream(samplesFile(), true)) {
      out.write((sample.toString() + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) { error = "历史记录写入失败：" + e.getMessage(); }
    if (samplesFile().length() > 2000000) {
      try (FileOutputStream out = new FileOutputStream(samplesFile(), false)) {
        synchronized (history) { for (JSONObject row : history) out.write((row.toString() + "\n").getBytes(StandardCharsets.UTF_8)); }
      } catch (Exception ignored) { }
    }
  }
  private String historyJson() {
    JSONArray array = new JSONArray(); JSONObject latest = null;
    synchronized (history) { for (JSONObject row : history) array.put(row); if (!history.isEmpty()) latest = history.get(history.size() - 1); }
    return "{\"samples\":" + array + ",\"latest\":" + (latest == null ? "null" : latest) + "}";
  }

  @Override public void onDestroy() {
    handler.removeCallbacks(poll);
    if (locations != null) { locations.removeUpdates(gpsListener); locations.removeUpdates(networkListener); }
    if (server != null) server.close();
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    super.onDestroy();
  }

  private class WebServer {
    private final ServerSocket listener;
    private final CopyOnWriteArrayList<Socket> sockets = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Socket> websocketClients = new CopyOnWriteArrayList<>();
    private final ExecutorService broadcaster = Executors.newSingleThreadExecutor();
    private volatile boolean active = true;
    WebServer() throws Exception { listener = new ServerSocket(8765, 40, InetAddress.getByName("0.0.0.0")); }
    void start() {
      Thread accept = new Thread(() -> {
        while (active) {
          try {
            Socket socket = listener.accept(); sockets.add(socket);
            new Thread(() -> handle(socket), "wifi-http-client").start();
          } catch (Exception e) { if (active) error = "网页连接失败：" + e.getMessage(); }
        }
      }, "wifi-http-server");
      accept.start();
    }
    private void handle(Socket socket) {
      try {
        socket.setSoTimeout(30000);
        InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream header = new ByteArrayOutputStream(); int b;
        while (header.size() < 8192 && (b = in.read()) != -1) {
          header.write(b); byte[] bytes = header.toByteArray(); int n = bytes.length;
          if (n >= 4 && bytes[n - 4] == 13 && bytes[n - 3] == 10 && bytes[n - 2] == 13 && bytes[n - 1] == 10) break;
        }
        String h = header.toString("UTF-8");
        String[] lines = h.split("\r\n");
        if (lines.length == 0) return;
        String[] first = lines[0].split(" "); if (first.length < 2) return;
        String uri = first[1], path = uri.split("\\?")[0];
        boolean staticAsset = "/app.js".equals(path) || "/leaflet.js".equals(path) || "/leaflet.css".equals(path) || path.startsWith("/images/");
        if (!staticAsset && !uri.contains("key=" + token(CollectorService.this))) { respond(out, 403, "text/plain; charset=utf-8", "请使用 App 显示的链接".getBytes(StandardCharsets.UTF_8)); return; }
        String websocketKey = null;
        for (String line : lines) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:")) websocketKey = line.substring(line.indexOf(':') + 1).trim();
        if ("/ws".equals(path) && websocketKey != null) {
          String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((websocketKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
          out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
          websocketClients.add(socket); socket.setSoTimeout(0);
          String latest = "null";
          synchronized (history) { if (!history.isEmpty()) latest = history.get(history.size() - 1).toString(); }
          writeFrame(socket, "{\"type\":\"ready\",\"latest\":" + latest + "}");
          android.util.Log.i("WifiMap", "WebSocket accepted " + socket.getRemoteSocketAddress());
          while (active) { if (in.read() == -1) { android.util.Log.i("WifiMap", "WebSocket EOF"); break; } }
          return;
        }
        if ("/health".equals(path)) {
          respond(out, 200, "application/json; charset=utf-8", ("{\"status\":\"" + error.replace("\"", "") + "\"}").getBytes(StandardCharsets.UTF_8)); return;
        }
        if ("/history".equals(path)) { respond(out, 200, "application/json; charset=utf-8", historyJson().getBytes(StandardCharsets.UTF_8)); return; }
        String asset = null, type = "text/plain; charset=utf-8";
        if ("/".equals(path)) { asset = "index.html"; type = "text/html; charset=utf-8"; }
        else if ("/app.js".equals(path) || "/leaflet.js".equals(path)) { asset = path.substring(1); type = "text/javascript; charset=utf-8"; }
        else if ("/leaflet.css".equals(path)) { asset = "leaflet.css"; type = "text/css; charset=utf-8"; }
        else if (path.matches("/images/(marker-icon|marker-icon-2x|marker-shadow|layers|layers-2x)\\.png")) { asset = path.substring(1); type = "image/png"; }
        if (asset == null) { respond(out, 404, type, new byte[0]); return; }
        try (InputStream file = getAssets().open(asset)) {
          ByteArrayOutputStream buffer = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int count;
          while ((count = file.read(chunk)) != -1) buffer.write(chunk, 0, count);
          respond(out, 200, type, buffer.toByteArray());
        } catch (Exception e) { respond(out, 404, type, new byte[0]); }
      } catch (Exception e) { android.util.Log.e("WifiMap", "HTTP client failed", e); }
      finally { websocketClients.remove(socket); sockets.remove(socket); try { socket.close(); } catch (Exception ignored) { } }
    }
    private void respond(OutputStream out, int code, String type, byte[] body) throws Exception {
      String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : "Not Found";
      out.write(("HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      out.write(body); out.flush();
    }
    private void writeFrame(Socket socket, String message) throws Exception {
      byte[] payload = message.getBytes(StandardCharsets.UTF_8);
      synchronized (socket) {
        OutputStream out = socket.getOutputStream(); out.write(0x81);
        if (payload.length < 126) out.write(payload.length);
        else if (payload.length <= 65535) { out.write(126); out.write((payload.length >> 8) & 255); out.write(payload.length & 255); }
        else { out.write(127); for (int shift = 56; shift >= 0; shift -= 8) out.write((payload.length >> shift) & 255); }
        out.write(payload); out.flush();
      }
    }
    void broadcast(String message) {
      if (!active) return;
      broadcaster.execute(() -> {
        for (Socket socket : websocketClients) try { writeFrame(socket, message); } catch (Exception e) { android.util.Log.e("WifiMap", "WebSocket broadcast failed", e); websocketClients.remove(socket); try { socket.close(); } catch (Exception ignored) { } }
      });
    }
    void close() {
      active = false;
      broadcaster.shutdownNow();
      try { listener.close(); } catch (Exception ignored) { }
      for (Socket socket : sockets) try { socket.close(); } catch (Exception ignored) { }
    }
  }
}
