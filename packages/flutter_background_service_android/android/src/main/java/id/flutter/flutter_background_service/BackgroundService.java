package id.flutter.flutter_background_service;

import static android.os.Build.VERSION.SDK_INT;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class BackgroundService extends Service implements MethodChannel.MethodCallHandler {
    private static final String TAG = "BackgroundService";
    private static final String LOCK_NAME = BackgroundService.class.getName()
            + ".Lock";
    public static volatile WakeLock lockStatic = null; // notice static
    AtomicBoolean isRunning = new AtomicBoolean(false);
    private FlutterEngine backgroundEngine;
    private MethodChannel methodChannel;
    private Config config;
    private DartExecutor.DartEntrypoint dartEntrypoint;
    private boolean isManuallyStopped = false;
    private String notificationTitle;
    private String notificationContent;
    private String notificationChannelId;
    private int notificationId;
    private String configForegroundTypes;
    private String[] foregroundTypes;
    private Handler mainHandler;

    synchronized public static PowerManager.WakeLock getLock(Context context) {
        if (lockStatic == null) {
            PowerManager mgr = (PowerManager) context
                    .getSystemService(Context.POWER_SERVICE);
            lockStatic = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    LOCK_NAME);
            lockStatic.setReferenceCounted(true);
        }

        return lockStatic;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    private void logPermissionDiagnostics() {
        Context ctx = getApplicationContext();
        if (ctx == null) return;

        Log.w(TAG, "══════════ PERMISSION DIAGNOSTICS ══════════");
        checkAndLogPermission("android.permission.ACCESS_FINE_LOCATION");
        checkAndLogPermission("android.permission.ACCESS_COARSE_LOCATION");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checkAndLogPermission("android.permission.ACCESS_BACKGROUND_LOCATION");
        }
        if (Build.VERSION.SDK_INT >= 34) {
            checkAndLogPermission("android.permission.FOREGROUND_SERVICE_LOCATION");
            checkAndLogPermission("android.permission.FOREGROUND_SERVICE_DATA_SYNC");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            checkAndLogPermission("android.permission.POST_NOTIFICATIONS");
        }
        Log.w(TAG, "════════════════════════════════════════════");
    }

    private void checkAndLogPermission(String permission) {
        int result = ContextCompat.checkSelfPermission(this, permission);
        boolean granted = (result == PackageManager.PERMISSION_GRANTED);
        Log.w(TAG, "  " + permission + " -> " + (granted ? "✅ GRANTED" : "❌ DENIED"));
    }

    private void writeErrorToSharedPrefs(String error) {
        try {
            SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE);
            prefs.edit()
                .putString("flutter.bg_service_error", error)
                .putLong("flutter.bg_service_error_timestamp", System.currentTimeMillis())
                .putBoolean("flutter.has_bg_service_error", true)
                .apply();
            Log.d(TAG, "Wrote native error to FlutterSharedPreferences: " + error);
        } catch (Exception e) {
            Log.e(TAG, "Failed to write error to FlutterSharedPreferences: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "════════════════ BackgroundService onCreate() ════════════════");

        FlutterBackgroundServicePlugin.servicePipe.addListener(listener);

        config = new Config(this);
        mainHandler = new Handler(Looper.getMainLooper());

        String notificationChannelId = config.getNotificationChannelId();
        Log.d(TAG, "Config notificationChannelId: " + notificationChannelId);
        if (notificationChannelId == null) {
            this.notificationChannelId = "FOREGROUND_DEFAULT";
            createNotificationChannel();
        } else {
            this.notificationChannelId = notificationChannelId;
        }

        notificationTitle = config.getInitialNotificationTitle();
        notificationContent = config.getInitialNotificationContent();
        notificationId = config.getForegroundNotificationId();
        configForegroundTypes = config.getForegroundServiceTypes();

        Log.d(TAG, "Initial notification config: title='" + notificationTitle + "', content='" + notificationContent + "', id=" + notificationId + ", types=" + configForegroundTypes);
        Log.d(TAG, "Calling initial updateNotificationInfo()...");
        updateNotificationInfo();
        Log.d(TAG, "Calling onStartCommand(null, -1, -1)...");
        onStartCommand(null, -1, -1);
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "════════════════ BackgroundService onDestroy() ════════════════ isManuallyStopped=" + isManuallyStopped);
        if (!isManuallyStopped) {
            Log.d(TAG, "Service destroyed unexpectedly - enqueueing WatchdogReceiver");
            WatchdogReceiver.enqueue(this);
        } else {
            Log.d(TAG, "Service destroyed manually - setting manuallyStopped flag");
            config.setManuallyStopped(true);
        }
        stopForeground(true);
        isRunning.set(false);

        if (backgroundEngine != null) {
            Log.d(TAG, "Detaching and destroying FlutterEngine...");
            backgroundEngine.getServiceControlSurface().detachFromService();
            backgroundEngine.destroy();
            backgroundEngine = null;
        }

        FlutterBackgroundServicePlugin.servicePipe.removeListener(listener);
        methodChannel = null;
        dartEntrypoint = null;
        super.onDestroy();
        Log.i(TAG, "BackgroundService onDestroy complete");
    }

    private final Pipe.PipeListener listener = new Pipe.PipeListener() {
        @Override
        public void onReceived(JSONObject object) {
            receiveData(object);
        }
    };

    private void createNotificationChannel() {
        if (SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Background Service";
            String description = "Executing process in background";

            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(notificationChannelId, name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    protected void updateNotificationInfo() {
        Log.d(TAG, "updateNotificationInfo() - isForeground: " + config.isForeground() + ", title: '" + notificationTitle + "', content: '" + notificationContent + "'");
        if (config.isForeground()) {
            String packageName = getApplicationContext().getPackageName();
            Intent i = getPackageManager().getLaunchIntentForPackage(packageName);

            int flags = PendingIntent.FLAG_CANCEL_CURRENT;
            if (SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }

            PendingIntent pi = PendingIntent.getActivity(BackgroundService.this, 11, i, flags);
            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, notificationChannelId)
                    .setSmallIcon(R.drawable.ic_bg_service_small)
                    .setAutoCancel(true)
                    .setOngoing(true)
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationContent)
                    .setContentIntent(pi);

            try {
                foregroundTypes = null;
                if (configForegroundTypes != null && !configForegroundTypes.isEmpty()) {
                    foregroundTypes = configForegroundTypes.split(",");
                }
                Integer serviceType = ForegroundTypeMapper.getForegroundServiceType(foregroundTypes);
                Log.d(TAG, "Attempting ServiceCompat.startForeground(id=" + notificationId + ", serviceType=" + serviceType + ")...");
                ServiceCompat.startForeground(this, notificationId, mBuilder.build(), serviceType);
                Log.i(TAG, "✅ ServiceCompat.startForeground succeeded! Notification active: ['" + notificationTitle + "' : '" + notificationContent + "']");
            } catch (SecurityException e) {
                Log.e(TAG, "❌ CRITICAL: SecurityException in startForeground! " + e.getMessage(), e);
                logPermissionDiagnostics();
                writeErrorToSharedPrefs("SecurityException in startForeground: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "❌ CRITICAL: Unexpected Exception in startForeground! " + e.getMessage(), e);
                writeErrorToSharedPrefs("Exception in startForeground: " + e.getMessage());
            }
        } else {
            Log.d(TAG, "updateNotificationInfo() skipped: not configured as foreground service");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        config.setManuallyStopped(false);
        WatchdogReceiver.enqueue(this);
        runService();

        return START_NOT_STICKY;
    }

    @SuppressLint("WakelockTimeout")
    private void runService() {
        Log.i(TAG, "════════════════ runService() ════════════════");
        try {
            if (isRunning.get() || (backgroundEngine != null && !backgroundEngine.getDartExecutor().isExecutingDart())) {
                Log.v(TAG, "Service already running, using existing service");
                return;
            }

            Log.i(TAG, "Starting flutter engine for background service");
            getLock(getApplicationContext()).acquire();

            Log.d(TAG, "Updating notification info before starting FlutterEngine...");
            updateNotificationInfo();

            FlutterLoader flutterLoader = FlutterInjector.instance().flutterLoader();
            // initialize flutter if it's not initialized yet
            if (!flutterLoader.initialized()) {
                Log.d(TAG, "FlutterLoader not initialized - starting initialization...");
                flutterLoader.startInitialization(getApplicationContext());
            }

            Log.d(TAG, "Ensuring FlutterLoader initialization complete...");
            flutterLoader.ensureInitializationComplete(getApplicationContext(), null);

            isRunning.set(true);
            Log.d(TAG, "Creating new FlutterEngine...");
            backgroundEngine = new FlutterEngine(this);

            // remove FlutterBackgroundServicePlugin (because its only for UI)
            backgroundEngine.getPlugins().remove(FlutterBackgroundServicePlugin.class);

            Log.d(TAG, "Attaching service control surface (isForeground=" + config.isForeground() + ")...");
            backgroundEngine.getServiceControlSurface().attachToService(BackgroundService.this, null, config.isForeground());

            Log.d(TAG, "Registering background MethodChannel...");
            methodChannel = new MethodChannel(backgroundEngine.getDartExecutor().getBinaryMessenger(), "id.flutter/background_service_android_bg", JSONMethodCodec.INSTANCE);
            methodChannel.setMethodCallHandler(this);

            String bundlePath = flutterLoader.findAppBundlePath();
            Log.d(TAG, "App bundle path: " + bundlePath);
            dartEntrypoint = new DartExecutor.DartEntrypoint(bundlePath, "package:flutter_background_service_android/flutter_background_service_android.dart", "entrypoint");

            final List<String> args = new ArrayList<>();
            long backgroundHandle = config.getBackgroundHandle();
            Log.d(TAG, "Background handle from config: " + backgroundHandle);
            if (backgroundHandle == 0) {
                Log.e(TAG, "❌ WARNING: backgroundHandle is 0! Dart entrypoint cannot execute onStart!");
                writeErrorToSharedPrefs("backgroundHandle is 0 - Dart onStart callback cannot be found");
            }
            args.add(String.valueOf(backgroundHandle));

            Log.i(TAG, "Executing Dart entrypoint with handle " + backgroundHandle + "...");
            backgroundEngine.getDartExecutor().executeDartEntrypoint(dartEntrypoint, args);
            Log.i(TAG, "✅ Dart entrypoint executed successfully");

        } catch (UnsatisfiedLinkError e) {
            notificationContent = "Error " + e.getMessage();
            updateNotificationInfo();
            Log.e(TAG, "❌ UnsatisfiedLinkError in runService: " + e.getMessage(), e);
            writeErrorToSharedPrefs("UnsatisfiedLinkError in runService: " + e.getMessage());
        } catch (Throwable t) {
            notificationContent = "Fatal Error: " + t.getMessage();
            updateNotificationInfo();
            Log.e(TAG, "❌ CRITICAL: Fatal error in runService: " + t.getMessage(), t);
            writeErrorToSharedPrefs("Fatal error in runService: " + t.getMessage());
        }
    }

    public void receiveData(JSONObject data) {
        if (methodChannel == null) return;
        try {
            final JSONObject arg = data;
            mainHandler.post(() -> {
                if (methodChannel == null) return;
                methodChannel.invokeMethod("onReceiveData", arg);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (isRunning.get()) {
            WatchdogReceiver.enqueue(getApplicationContext(), 1000);
        }
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        String method = call.method;
        Log.d(TAG, "onMethodCall received from Dart: " + method);

        try {
            if (method.equalsIgnoreCase("setNotificationInfo")) {
                JSONObject arg = (JSONObject) call.arguments;
                Log.d(TAG, "onMethodCall 'setNotificationInfo' payload: " + arg.toString());
                if (arg.has("title")) {
                    String oldTitle = notificationTitle;
                    String oldContent = notificationContent;
                    notificationTitle = arg.getString("title");
                    notificationContent = arg.getString("content");
                    Log.i(TAG, "Updating notification info: ['" + oldTitle + "' -> '" + notificationTitle + "'], ['" + oldContent + "' -> '" + notificationContent + "']");
                    updateNotificationInfo();
                    result.success(true);
                } else {
                    Log.w(TAG, "setNotificationInfo payload missing 'title' key");
                }
                return;
            }

            if (method.equalsIgnoreCase("setAutoStartOnBootMode")) {
                JSONObject arg = (JSONObject) call.arguments;
                boolean value = arg.getBoolean("value");
                config.setAutoStartOnBoot(value);
                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("setForegroundMode")) {
                JSONObject arg = (JSONObject) call.arguments;
                boolean value = arg.getBoolean("value");
                config.setIsForeground(value);
                if (value) {
                    updateNotificationInfo();
                    backgroundEngine.getServiceControlSurface().onMoveToForeground();
                } else {
                    stopForeground(true);
                    backgroundEngine.getServiceControlSurface().onMoveToBackground();
                }

                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("isForegroundMode")) {
                boolean value = config.isForeground();
                result.success(value);
                return;
            }

            if (method.equalsIgnoreCase("stopService")) {
                isManuallyStopped = true;
                WatchdogReceiver.remove(this);
                stopSelf();
                result.success(true);
                return;
            }

            if (method.equalsIgnoreCase("sendData")) {
                try {
                    if (FlutterBackgroundServicePlugin.mainPipe.hasListener()){
                        FlutterBackgroundServicePlugin.mainPipe.invoke((JSONObject) call.arguments);
                    }

                    result.success(true);
                } catch (Exception e) {
                    result.error("send-data-failure", e.getMessage(), e);
                }
                return;
            }

            if(method.equalsIgnoreCase("openApp")){
                try{
                    String packageName=  getPackageName();
                    Intent launchIntent= getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);

                        startActivity(launchIntent);
                        result.success(true);

                    }
                }catch (Exception e){
                    result.error("open app failure", e.getMessage(),e);

                }
                return;

            }
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage());
            e.printStackTrace();
        }

        result.notImplemented();
    }
}
