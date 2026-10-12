package com.onecool.scooter.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.onecool.scooter.protocol.ScooterProtocol;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 壹酷电车后台长连接与智能控车服务
 */
public class BleVehicleService extends Service {

    private static final String TAG = "BleVehicleService";
    private static final String CHANNEL_ID = "YikuScooterServiceChannel";

    public static final String ACTION_STATUS_UPDATED = "com.onecool.scooter.STATUS_UPDATED";
    public static final String ACTION_BLE_STATE_CHANGED = "com.onecool.scooter.BLE_STATE_CHANGED";

    public interface VehicleListener {
        void onConnectionChanged(boolean connected, int rssi);
        void onTelemetryUpdated(ScooterProtocol.TelemetryData telemetry);
    }

    private final IBinder mBinder = new LocalBinder();
    private final List<VehicleListener> mListeners = new CopyOnWriteArrayList<>();

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBleScanner;
    private BluetoothGatt mBluetoothGatt;

    private BluetoothGattCharacteristic mWriteChar;
    private BluetoothGattCharacteristic mNotifyChar;

    private int mSeqCode = 1;
    private byte[] mCurrentR = ScooterProtocol.DEFAULT_R.clone();
    private byte[] mCurrentM = ScooterProtocol.DEFAULT_M.clone();

    private boolean mIsConnected = false;
    private int mLastRssi = -100;
    private boolean mProximityUnlockEnabled = true;
    private long mLastProximityTriggerTime = 0;

    private ScooterProtocol.TelemetryData mLatestTelemetry = new ScooterProtocol.TelemetryData();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mHeartbeatRunnable;

    public class LocalBinder extends Binder {
        public BleVehicleService getService() {
            return BleVehicleService.this;
        }
    }

    public void addListener(VehicleListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
            listener.onConnectionChanged(mIsConnected, mLastRssi);
            if (mLatestTelemetry != null && mLatestTelemetry.valid) {
                listener.onTelemetryUpdated(mLatestTelemetry);
            }
        }
    }

    public void removeListener(VehicleListener listener) {
        mListeners.remove(listener);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        try {
            Notification notification = buildNotification("正在守候车辆连接...");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(101, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed: " + e.getMessage());
        }

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            mBluetoothAdapter = bm.getAdapter();
        }

        mHeartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (mIsConnected && mWriteChar != null) {
                    sendRaw(ScooterProtocol.CMD_HEARTBEAT, 170, 90);
                }
                mHandler.postDelayed(this, 1000);
            }
        };

        startScanForScooter();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("cmd")) {
            int cmd = intent.getIntExtra("cmd", 0);
            if (cmd > 0) {
                sendCommand(cmd);
            }
        }
        return START_STICKY;
    }

    public void startScanForScooter() {
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "缺少 BLUETOOTH_SCAN 权限");
                return;
            }
        }

        try {
            mBleScanner = mBluetoothAdapter.getBluetoothLeScanner();
            if (mBleScanner == null) return;

            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            ScanFilter filter = new ScanFilter.Builder()
                    .setDeviceName("smart_ebike")
                    .build();

            mBleScanner.startScan(Collections.singletonList(filter), settings, mScanCallback);
            Log.i(TAG, "启动后台广播嗅探 (smart_ebike)...");
        } catch (SecurityException se) {
            Log.e(TAG, "扫描权限受限: ", se);
        } catch (Exception e) {
            Log.e(TAG, "扫描启动异常: ", e);
        }
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            ScanRecord record = result.getScanRecord();
            mLastRssi = result.getRssi();

            if (record != null) {
                byte[] raw = record.getBytes();
                if (raw != null && raw.length >= 13) {
                    for (int i = 0; i < raw.length - 8; i++) {
                        if (raw[i] == (byte) 0x55) {
                            System.arraycopy(raw, i + 1, mCurrentR, 0, 4);
                            break;
                        }
                    }
                }
            }

            // 无感靠近解锁判定 (防抖 15 秒)
            long now = System.currentTimeMillis();
            if (mProximityUnlockEnabled && mLastRssi > -68 && now - mLastProximityTriggerTime > 15000) {
                if (mIsConnected && !mLatestTelemetry.isPowerOn) {
                    mLastProximityTriggerTime = now;
                    Log.i(TAG, "检测到靠近车辆 (RSSI: " + mLastRssi + " dBm)，自动无感解锁并上电！");
                    sendCommand(ScooterProtocol.CMD_UNLOCK);
                    mHandler.postDelayed(() -> sendCommand(ScooterProtocol.CMD_POWER_ON), 300);
                }
            }

            // 若尚未建立连接，立即发起直连
            if (!mIsConnected && mBluetoothGatt == null) {
                connectToDevice(device);
            } else if (mIsConnected) {
                dispatchConnectionState();
            }
        }
    };

    public void connectToDevice(BluetoothDevice device) {
        if (device == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        try {
            Log.i(TAG, "正在建立系统级 GATT 通道: " + device.getAddress());
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                device.createBond();
            }

            mBluetoothGatt = device.connectGatt(this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException se) {
            Log.e(TAG, "建立 GATT 连接权限不足: ", se);
        } catch (Exception e) {
            Log.e(TAG, "连接车机异常: ", e);
        }
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mIsConnected = true;
                Log.i(TAG, "GATT 物理连接成功，发现服务中...");
                updateNotification("车机已连接 (在线)");
                dispatchConnectionState();

                mHandler.postDelayed(() -> {
                    try {
                        gatt.discoverServices();
                    } catch (SecurityException ignored) {}
                }, 400);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mIsConnected = false;
                mBluetoothGatt = null;
                mWriteChar = null;
                mNotifyChar = null;
                mHandler.removeCallbacks(mHeartbeatRunnable);
                Log.w(TAG, "GATT 已断开，重新嗅探广播...");
                updateNotification("车机已离线，等待靠近...");
                dispatchConnectionState();
                startScanForScooter();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(UUID.fromString(ScooterProtocol.SERVICE_UUID));
                if (service != null) {
                    mWriteChar = service.getCharacteristic(UUID.fromString(ScooterProtocol.WRITE_CHAR_UUID));
                    mNotifyChar = service.getCharacteristic(UUID.fromString(ScooterProtocol.NOTIFY_CHAR_UUID));

                    // 开启车机 Notify 监听
                    if (mNotifyChar != null) {
                        try {
                            gatt.setCharacteristicNotification(mNotifyChar, true);
                            BluetoothGattDescriptor descriptor = mNotifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                            if (descriptor != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                } else {
                                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                    gatt.writeDescriptor(descriptor);
                                }
                            }
                        } catch (SecurityException se) {
                            Log.e(TAG, "Notify 监听权限异常: ", se);
                        }
                    }
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            Log.i(TAG, "Notify 描述符配置完成，状态: " + status);
            // 此时已握手完成，立即协商 MTU 并启动心跳轮询
            try {
                gatt.requestMtu(30);
            } catch (SecurityException ignored) {}

            mHandler.post(mHeartbeatRunnable);
            dispatchConnectionState();
        }

        // 兼容 Android 12 及以下
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            if (characteristic != null) {
                handleIncomingBytes(characteristic.getValue());
            }
        }

        // 兼容 Android 13+ (API 33+)
        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt, @NonNull BluetoothGattCharacteristic characteristic, @NonNull byte[] value) {
            super.onCharacteristicChanged(gatt, characteristic, value);
            handleIncomingBytes(value);
        }
    };

    private void handleIncomingBytes(byte[] val) {
        if (val != null && val.length >= 23) {
            ScooterProtocol.TelemetryData t = ScooterProtocol.parseTelemetry(val);
            if (t.valid) {
                mLatestTelemetry = t;
                for (VehicleListener l : mListeners) {
                    l.onTelemetryUpdated(mLatestTelemetry);
                }
                broadcastTelemetry();
            }
        }
    }

    /**
     * 发送功能指令
     */
    public boolean sendCommand(int cmd) {
        return sendCommand(cmd, 0, 0);
    }

    public boolean sendCommand(int cmd, int arg1, int arg2) {
        return sendRaw(cmd, arg1, arg2);
    }

    private boolean sendRaw(int cmd, int arg1, int arg2) {
        if (!mIsConnected || mBluetoothGatt == null || mWriteChar == null) {
            return false;
        }

        try {
            byte[] packet = ScooterProtocol.buildPacket(mSeqCode, mCurrentR, mCurrentM, cmd, arg1, arg2);
            mSeqCode = (mSeqCode + 1) & 0xFFFF;
            if (mSeqCode == 0) mSeqCode = 1;

            mWriteChar.setValue(packet);
            mWriteChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            return mBluetoothGatt.writeCharacteristic(mWriteChar);
        } catch (SecurityException se) {
            Log.e(TAG, "发送指令权限异常: ", se);
            return false;
        }
    }

    public ScooterProtocol.TelemetryData getLatestTelemetry() {
        return mLatestTelemetry;
    }

    public boolean isConnected() {
        return mIsConnected;
    }

    public int getLastRssi() {
        return mLastRssi;
    }

    public void setProximityUnlockEnabled(boolean enabled) {
        this.mProximityUnlockEnabled = enabled;
    }

    public boolean isProximityUnlockEnabled() {
        return mProximityUnlockEnabled;
    }

    private void dispatchConnectionState() {
        for (VehicleListener l : mListeners) {
            l.onConnectionChanged(mIsConnected, mLastRssi);
        }
        broadcastStateChange();
    }

    private void broadcastStateChange() {
        Intent intent = new Intent(ACTION_BLE_STATE_CHANGED);
        intent.putExtra("connected", mIsConnected);
        intent.putExtra("rssi", mLastRssi);
        intent.setPackage(getPackageName()); // Android 14 必需声明 package 否则静默丢弃
        sendBroadcast(intent);
    }

    private void broadcastTelemetry() {
        Intent intent = new Intent(ACTION_STATUS_UPDATED);
        intent.putExtra("speed", mLatestTelemetry.speedKmh);
        intent.putExtra("voltage", mLatestTelemetry.voltage);
        intent.putExtra("battery", mLatestTelemetry.batteryPercent);
        intent.putExtra("gear", mLatestTelemetry.gear);
        intent.putExtra("powerOn", mLatestTelemetry.isPowerOn);
        intent.putExtra("locked", mLatestTelemetry.isLocked);
        intent.putExtra("speedLimit", mLatestTelemetry.isSpeedLimit);
        intent.putExtra("totalMileage", mLatestTelemetry.totalMileageKm);
        intent.putExtra("onceMileage", mLatestTelemetry.onceMileageKm);
        intent.putExtra("errorMsg", mLatestTelemetry.errorMsg);
        intent.putExtra("errorCode", mLatestTelemetry.errorCode);
        intent.setPackage(getPackageName()); // Android 14 必需声明 package
        sendBroadcast(intent);
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("壹酷 封神DZ 智能管家")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(101, buildNotification(text));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "壹酷电车服务通道",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacks(mHeartbeatRunnable);
        try {
            if (mBluetoothGatt != null) {
                mBluetoothGatt.disconnect();
                mBluetoothGatt.close();
            }
        } catch (SecurityException ignored) {}
        try {
            if (mBleScanner != null && mScanCallback != null) {
                mBleScanner.stopScan(mScanCallback);
            }
        } catch (SecurityException ignored) {}
    }
}
