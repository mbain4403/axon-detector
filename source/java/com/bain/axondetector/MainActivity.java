package com.bain.axondetector;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Axon Detector — passive Bluetooth LE awareness + preloaded plate-reader map.
 *
 * The native side does exactly one radio job: it listens to BLE advertisements
 * (receive-only; it never connects, pairs, or transmits) and matches them
 * against Axon's public Bluetooth signatures. Matches live in memory only.
 * Device addresses are used as in-memory map keys, are never displayed,
 * never written to disk, and never leave the phone. The UI is bundled web
 * assets in a WebView; the only network use is OpenStreetMap tiles.
 */
public class MainActivity extends Activity {

    // Axon / TASER public Bluetooth signatures.
    private static final String AXON_OUI = "00:25:DF";
    private static final int AXON_COMPANY_ID = 0x034D; // TASER International
    private static final UUID[] AXON_SERVICES = new UUID[]{
            UUID.fromString("0000fc81-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000fe6b-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000fe6c-0000-1000-8000-00805f9b34fb"),
    };
    private static final long HIT_EXPIRE_MS = 25_000;
    private static final int REQ_PERMS = 42;
    private static final int REQ_LOC = 43;

    private WebView web;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private Handler ui;
    private boolean wantScan = true;
    private boolean scanning = false;
    private boolean locStarted = false;
    private boolean permRequested = false;
    private String lastCrash = null;
    private static final String CRASH_FILE = "last_crash.txt";

    private static class Hit {
        String letter;
        String label;
        int rssi;
        long lastSeen;
        Set<String> via = new LinkedHashSet<>();
    }

    private final Map<String, Hit> hits = new HashMap<>();
    private int letterSeq = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashCapture();
        loadLastCrash();
        ui = new Handler(Looper.getMainLooper());

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) adapter = bm.getAdapter();
        if (adapter != null) scanner = adapter.getBluetoothLeScanner();

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Bridge");
        setContentView(web);
        web.loadUrl("file:///android_asset/www/index.html");

        ui.postDelayed(tick, 1200);
    }

    // ---------- crash capture (diagnostics only: stores this app's own
    // stack trace in its private folder so the UI can show it next launch;
    // no device data, nothing transmitted) ----------

    private void installCrashCapture() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String text = sw.toString();
                if (text.length() > 4000) text = text.substring(0, 4000);
                FileOutputStream fos = openFileOutput(CRASH_FILE, Context.MODE_PRIVATE);
                fos.write(text.getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private void loadLastCrash() {
        try {
            File f = new File(getFilesDir(), CRASH_FILE);
            if (f.exists()) {
                FileInputStream fis = openFileInput(CRASH_FILE);
                byte[] buf = new byte[(int) Math.min(f.length(), 4000)];
                int n = fis.read(buf);
                fis.close();
                if (n > 0) lastCrash = new String(buf, 0, n, "UTF-8");
            }
        } catch (Throwable ignored) {}
    }

    // ---------- permissions ----------

    private boolean hasScanPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private String scanPermission() {
        return Build.VERSION.SDK_INT >= 31
                ? Manifest.permission.BLUETOOTH_SCAN
                : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    /** True once a request has been denied in a way that makes Android stop
     *  showing the dialog at all — the only route left is system Settings. */
    private boolean isPermBlocked() {
        return permRequested && !hasScanPermission()
                && !shouldShowRequestPermissionRationale(scanPermission());
    }

    private void requestScanPermission() {
        // Bluetooth only. Location is a separate ask, made from the map tab,
        // so this prompt is never bundled with a location request.
        // NOTE: this method is deliberately NOT named requestAccess — the
        // Bridge inner class has a requestAccess() of its own, and an
        // unqualified call inside Bridge used to resolve to the bridge method
        // itself, recursing until StackOverflowError (the v1.0–v1.2 crash).
        if (!hasScanPermission()) {
            permRequested = true;
            requestPermissions(new String[]{scanPermission()}, REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            if (hasScanPermission() && wantScan) startScan();
            pushState();
        } else if (requestCode == REQ_LOC) {
            startLocationIfAllowed();
            pushState();
        }
    }

    // ---------- BLE scanning ----------

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) handleResult(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            pushState();
        }
    };

    private void handleResult(ScanResult result) {
        try {
        if (result == null || result.getDevice() == null) return;
        ScanRecord rec = result.getScanRecord();
        String address = result.getDevice().getAddress();
        if (address == null) return;

        Set<String> via = new LinkedHashSet<>();
        if (address.toUpperCase().startsWith(AXON_OUI)) via.add("MAC prefix");

        String name = null;
        if (rec != null) {
            name = rec.getDeviceName();
            SparseArray<byte[]> mfr = rec.getManufacturerSpecificData();
            if (mfr != null && mfr.get(AXON_COMPANY_ID) != null) via.add("Bluetooth ID");
            List<ParcelUuid> uuids = rec.getServiceUuids();
            if (uuids != null) {
                for (ParcelUuid pu : uuids) {
                    for (UUID ax : AXON_SERVICES) {
                        if (ax.equals(pu.getUuid())) { via.add("Axon service"); break; }
                    }
                }
            }
        }
        if (name != null) {
            String up = name.toUpperCase();
            if (up.contains("AXON") || up.contains("TASER")) via.add("Device name");
        }
        if (via.isEmpty()) return;

        boolean isNew;
        synchronized (hits) {
            Hit h = hits.get(address);
            isNew = (h == null);
            if (h == null) {
                h = new Hit();
                h.letter = String.valueOf((char) ('A' + (letterSeq++ % 26)));
                hits.put(address, h);
            }
            h.label = classify(name);
            h.rssi = result.getRssi();
            h.lastSeen = System.currentTimeMillis();
            h.via.addAll(via);
        }
        if (isNew) vibrate();
        pushState();
        } catch (Throwable ignored) {}
    }

    private String classify(String name) {
        if (name != null) {
            String up = name.toUpperCase();
            if (up.contains("BODY")) return "Body camera";
            if (up.contains("TASER")) return "Taser";
            if (up.contains("FLEET")) return "Fleet vehicle unit";
            if (up.contains("SIGNAL")) return "Signal unit";
        }
        return "Axon gear";
    }

    private void startScan() {
        if (scanning || adapter == null) return;
        if (scanner == null) scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { pushState(); return; }
        if (!adapter.isEnabled() || !hasScanPermission()) { pushState(); return; }
        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable e) {
            scanning = false;
        }
        pushState();
    }

    private void stopScan() {
        if (!scanning || scanner == null) { scanning = false; return; }
        try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        scanning = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pushState();
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(180);
            }
        } catch (Exception ignored) {}
    }

    // ---------- location (map only) ----------

    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(Location loc) { pushLocation(loc); }
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
        @Override public void onStatusChanged(String p, int s, Bundle b) {}
    };

    private void startLocationIfAllowed() {
        if (locStarted || !hasLocationPermission()) return;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            Location last = null;
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10, locListener, Looper.getMainLooper());
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                if (last == null) last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10, locListener, Looper.getMainLooper());
            }
            locStarted = true;
            if (last != null) pushLocation(last);
        } catch (Exception ignored) {}
    }

    private void pushLocation(final Location loc) {
        if (web == null) return;
        web.post(() -> web.evaluateJavascript(
                "window.__onLoc && window.__onLoc(" + loc.getLatitude() + "," + loc.getLongitude() + ")", null));
    }

    // ---------- state push to UI ----------

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            pushState();
            ui.postDelayed(this, 2000);
        }
    };

    private String band(int rssi) {
        if (rssi >= -55) return "Very close";
        if (rssi >= -70) return "Near";
        if (rssi >= -85) return "Mid-range";
        return "Far / weak";
    }

    private void pushState() {
        if (web == null) return;
        long now = System.currentTimeMillis();
        JSONArray arr = new JSONArray();
        synchronized (hits) {
            List<String> dead = new ArrayList<>();
            for (Map.Entry<String, Hit> e : hits.entrySet()) {
                Hit h = e.getValue();
                if (now - h.lastSeen > HIT_EXPIRE_MS) { dead.add(e.getKey()); continue; }
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", h.letter);
                    o.put("label", h.label);
                    o.put("rssi", h.rssi);
                    o.put("band", band(h.rssi));
                    StringBuilder sb = new StringBuilder();
                    for (String v : h.via) { if (sb.length() > 0) sb.append(" + "); sb.append(v); }
                    o.put("via", sb.toString());
                    o.put("ago", Math.max(0, (now - h.lastSeen) / 1000));
                    arr.put(o);
                } catch (Exception ignored) {}
            }
            for (String k : dead) hits.remove(k);
        }
        JSONObject state = new JSONObject();
        try {
            state.put("btSupported", adapter != null);
            state.put("btOn", adapter != null && adapter.isEnabled());
            state.put("hasPerm", hasScanPermission());
            state.put("permBlocked", isPermBlocked());
            state.put("lastCrash", lastCrash == null ? "" : lastCrash);
            state.put("scanning", scanning);
            state.put("hits", arr);
        } catch (Exception ignored) {}
        final String js = "window.__onScan && window.__onScan(" + state.toString() + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    // ---------- lifecycle ----------

    @Override
    protected void onResume() {
        super.onResume();
        if (scanner == null && adapter != null) scanner = adapter.getBluetoothLeScanner();
        if (wantScan && hasScanPermission()) startScan();
        startLocationIfAllowed();
        pushState();
    }

    @Override
    protected void onPause() {
        stopScan();
        super.onPause();
    }

    // ---------- JS bridge ----------

    public class Bridge {
        @JavascriptInterface
        public void requestAccess() {
            runOnUiThread(() -> { MainActivity.this.requestScanPermission(); pushState(); });
        }

        @JavascriptInterface
        public void setScanning(final boolean on) {
            runOnUiThread(() -> {
                wantScan = on;
                if (on) {
                    if (!hasScanPermission()) MainActivity.this.requestScanPermission();
                    startScan();
                } else {
                    stopScan();
                }
                pushState();
            });
        }

        @JavascriptInterface
        public void dismissCrash() {
            lastCrash = null;
            try { deleteFile(CRASH_FILE); } catch (Throwable ignored) {}
            runOnUiThread(() -> pushState());
        }

        @JavascriptInterface
        public void requestLocation() {
            runOnUiThread(() -> {
                if (!hasLocationPermission()) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
                } else {
                    startLocationIfAllowed();
                }
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void enableBluetooth() {
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void ready() {
            runOnUiThread(() -> {
                if (wantScan && hasScanPermission()) startScan();
                startLocationIfAllowed();
                pushState();
            });
        }
    }
}
