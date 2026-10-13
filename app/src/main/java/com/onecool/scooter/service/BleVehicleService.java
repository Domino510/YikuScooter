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
import java.util.Set;
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
    private boolean mIsScanning = false;
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

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
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

        connectToScooter();
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

    /**
     * 智能连接通道：优先检测已绑定配对设备/已知MAC直连，未发现则启动BLE广播扫描
     */
    public synchronized void connectToScooter() {
        if (mIsConnected && mWriteChar != null) return;
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) return;

        // 1. 优先从系统已配对设备中寻找车机，避免被动等待广播
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        try {
            Set<BluetoothDevice> bondedDevices = mBluetoothAdapter.getBondedDevices();
            if (bondedDevices != null) {
                for (BluetoothDevice dev : bondedDevices) {
                    if (ScooterProtocol.DEVICE_NAME.equalsIgnoreCase(dev.getName())
                            || ScooterProtocol.DEVICE_MAC.equalsIgnoreCase(dev.getAddress())) {
                        Log.i(TAG, "在系统已配对列表中发现车机: " + dev.getAddress() + "，直接建立连接");
                        connectToDevice(dev);
                        return;
                    }
                }
            }

            // 2. 尝试已知 MAC 直连
            try {
                BluetoothDevice directDev = mBluetoothAdapter.getRemoteDevice(ScooterProtocol.DEVICE_MAC);
                if (directDev != null && directDev.getBondState() == BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "发现已知已配对 MAC 车机，直接直连: " + ScooterProtocol.DEVICE_MAC);
                    connectToDevice(directDev);
                    return;
                }
            } catch (Exception ignored) {}
        } catch (SecurityException ignored) {}

        // 3. 启动后台低延迟广播嗅探
        startScanForScooter();
    }

    public synchronized void startScanForScooter() {
        if (mIsConnected || mIsScanning) return;
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
                    .setDeviceName(ScooterProtocol.DEVICE_NAME)
                    .build();

            mBleScanner.startScan(Collections.singletonList(filter), settings, mScanCallback);
            mIsScanning = true;
            Log.i(TAG, "启动后台广播嗅探 (smart_ebike)...");
        } catch (SecurityException se) {
            Log.e(TAG, "扫描权限受限: ", se);
        } catch (Exception e) {
            Log.e(TAG, "扫描启动异常: ", e);
        }
    }

    public synchronized void stopScanForScooter() {
        if (!mIsScanning || mBleScanner == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }
            mBleScanner.stopScan(mScanCallback);
            mIsScanning = false;
            Log.i(TAG, "已停止广播扫描，腾出射频通道");
        } catch (Exception ignored) {
            mIsScanning = false;
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

            // 若尚未建立连接，立即停止扫描并发起 GATT 连接
            if (!mIsConnected && mBluetoothGatt == null) {
                stopScanForScooter();
                connectToDevice(device);
            } else if (mIsConnected) {
                dispatchConnectionState();
            }
        }
    };

    public synchronized void connectToDevice(BluetoothDevice device) {
        if (device == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        stopScanForScooter(); // 立即停止扫描，避免与 connectGatt 争用射频导致 133 错误

        try {
            Log.i(TAG, "正在建立 GATT 通道: " + device.getAddress());
            if (mBluetoothGatt != null) {
                try {
                    mBluetoothGatt.close();
                } catch (Exception ignored) {}
                mBluetoothGatt = null;
            }

            // 直接发起 GATT 直连，避免强行同步 createBond() 导致 SMP 握手与连接冲突
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
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT 物理连接成功，发现服务中...");
                updateNotification("车机已物理连接，正在发现服务...");

                // 物理连接建立后暂不置 true，待发现服务并获得写入特征值后才视为真正就绪
                mHandler.postDelayed(() -> {
                    try {
                        gatt.discoverServices();
                    } catch (SecurityException ignored) {}
                }, 300);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT 通道已断开 (status=" + status + ")");
                mIsConnected = false;
                mWriteChar = null;
                mNotifyChar = null;
                mHandler.removeCallbacks(mHeartbeatRunnable);

                // 关键修复：显式调用 gatt.close() 释放底层 Handle，防止句柄耗尽崩溃
                try {
                    gatt.close();
                } catch (Exception ignored) {}
                mBluetoothGatt = null;

                updateNotification("车机已离线，守候靠近中...");
                dispatchConnectionState();

                // 延迟 1 秒后自动重启广播扫描
                mHandler.postDelayed(BleVehicleService.this::connectToScooter, 1000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(UUID.fromString(ScooterProtocol.SERVICE_UUID));
                if (service != null) {
                    mWriteChar = service.getCharacteristic(UUID.fromString(ScooterProtocol.WRITE_CHAR_UUID));
                    mNotifyChar = service.getCharacteristic(UUID.fromString(ScooterProtocol.NOTIFY_CHAR_UUID));

                    // 写入通道已就绪，立即确认连接！
                    if (mWriteChar != null) {
                        mIsConnected = true;
                        Log.i(TAG, "通信写入特征值就绪，车机已完全在线！");
                        updateNotification("车机已连接 (在线)");
                        dispatchConnectionState();

                        // 立即启动 1Hz 心跳轮询拉取遥测数据
                        mHandler.removeCallbacks(mHeartbeatRunnable);
                        mHandler.post(mHeartbeatRunnable);

                        // 若靠近触发判定满足，执行无感开锁与上电
                        if (mProximityUnlockEnabled && mLastRssi > -68 && !mLatestTelemetry.isPowerOn) {
                            sendCommand(ScooterProtocol.CMD_UNLOCK);
                            mHandler.postDelayed(() -> sendCommand(ScooterProtocol.CMD_POWER_ON), 300);
                        }
                    }

                    // 开启车机 Notify 监听
                    if (mNotifyChar != null) {
                        try {
                            gatt.setCharacteristicNotification(mNotifyChar, true);
                            BluetoothGattDescriptor descriptor = mNotifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                            if (descriptor != null) {
                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                gatt.writeDescriptor(descriptor);
                            }
                            gatt.requestMtu(30);
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
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            if (characteristic != null) {
                handleIncomingBytes(characteristic.getValue());
            }
        }

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

            // 自适应特征值写入属性
            int writeType = (mWriteChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            mWriteChar.setWriteType(writeType);

            boolean success = mBluetoothGatt.writeCharacteristic(mWriteChar);
            if (!success && writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                // 降级为 DEFAULT 写入
                mWriteChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                success = mBluetoothGatt.writeCharacteristic(mWriteChar);
            }
            return success;
        } catch (SecurityException se) {
            Log.e(TAG, "发送指令权限异常: ", se);
            return false;
        }
    }

    public ScooterProtocol.TelemetryData getLatestTelemetry() {
        return mLatestTelemetry;
    }

    public boolean isConnected() {
        return mIsConnected && mWriteChar != null;
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
        boolean connected = isConnected();
        for (VehicleListener l : mListeners) {
            l.onConnectionChanged(connected, mLastRssi);
        }
        broadcastStateChange();
    }

    private void broadcastStateChange() {
        Intent intent = new Intent(ACTION_BLE_STATE_CHANGED);
        intent.putExtra("connected", isConnected());
        intent.putExtra("rssi", mLastRssi);
        intent.setPackage(getPackageName());
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
        intent.setPackage(getPackageName());
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
}
