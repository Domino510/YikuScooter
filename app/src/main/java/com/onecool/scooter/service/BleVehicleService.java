package com.onecool.scooter.service;

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
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.onecool.scooter.protocol.ScooterProtocol;

import java.util.Collections;
import java.util.UUID;

/**
 * 壹酷电车后台长连接与无感解锁服务
 */
public class BleVehicleService extends Service {

    private static final String TAG = "BleVehicleService";
    private static final String CHANNEL_ID = "YikuScooterServiceChannel";

    public static final String ACTION_STATUS_UPDATED = "com.onecool.scooter.STATUS_UPDATED";
    public static final String ACTION_BLE_STATE_CHANGED = "com.onecool.scooter.BLE_STATE_CHANGED";

    private final IBinder mBinder = new LocalBinder();
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBleScanner;
    private BluetoothGatt mBluetoothGatt;

    private BluetoothGattCharacteristic mWriteChar;
    private BluetoothGattCharacteristic mNotifyChar;

    private int mSeqCode = 1;
    private byte[] mCurrentR = ScooterProtocol.DEFAULT_R.clone();
    private byte[] mCurrentM = ScooterProtocol.DEFAULT_M.clone();

    private boolean mIsConnected = false;
    private boolean mProximityUnlockEnabled = true;
    private ScooterProtocol.TelemetryData mLatestTelemetry = new ScooterProtocol.TelemetryData();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Runnable mHeartbeatRunnable;

    public class LocalBinder extends Binder {
        public BleVehicleService getService() {
            return BleVehicleService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(101, buildNotification("正在监听车机状态..."));

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

    private void startScanForScooter() {
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) return;
        mBleScanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (mBleScanner == null) return;

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        ScanFilter filter = new ScanFilter.Builder()
                .setDeviceName("smart_ebike")
                .build();

        mBleScanner.startScan(Collections.singletonList(filter), settings, mScanCallback);
        Log.i(TAG, "开始后台扫描车机广播...");
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            ScanRecord record = result.getScanRecord();

            if (record != null) {
                byte[] raw = record.getBytes();
                // 动态提取广播中的 R 和 M 密钥 (厂商自定义数据段)
                if (raw != null && raw.length >= 13) {
                    // 从广播帧中提取 4 字节动态滚动 R 码
                    for (int i = 0; i < raw.length - 8; i++) {
                        if (raw[i] == (byte) 0x55) { // 找到 0x55 标识位
                            System.arraycopy(raw, i + 1, mCurrentR, 0, 4);
                            Log.d(TAG, "动态更新车机广播 R 码: " + bytesToHex(mCurrentR));
                            break;
                        }
                    }
                }
            }

            // 无感靠近开锁逻辑 (RSSI 强度判定)
            int rssi = result.getRssi();
            if (mProximityUnlockEnabled && rssi > -70 && !mLatestTelemetry.isPowerOn && mIsConnected) {
                Log.i(TAG, "检测到靠近车身 (RSSI: " + rssi + " dBm)，执行无感开锁！");
                sendCommand(ScooterProtocol.CMD_UNLOCK);
                sendCommand(ScooterProtocol.CMD_POWER_ON);
            }

            // 若尚未连接，自动发起 GATT 直连
            if (!mIsConnected && mBluetoothGatt == null) {
                connectToDevice(device);
            }
        }
    };

    public void connectToDevice(BluetoothDevice device) {
        Log.i(TAG, "正在建立系统级 GATT 通道: " + device.getAddress());
        // 系统级配对绑定，防止车机报警“滴滴滴”并主动断开
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            device.createBond();
        }

        mBluetoothGatt = device.connectGatt(this, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mIsConnected = true;
                Log.i(TAG, "GATT 已连接，开始发现服务...");
                updateNotification("车机已连接 (在线)");
                mHandler.postDelayed(() -> gatt.discoverServices(), 600);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mIsConnected = false;
                mBluetoothGatt = null;
                mWriteChar = null;
                mNotifyChar = null;
                mHandler.removeCallbacks(mHeartbeatRunnable);
                Log.w(TAG, "GATT 连接已断开，重新启动广播嗅探...");
                updateNotification("车机已离线，正在监听广播...");
                broadcastStateChange();
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

                    // 开启状态监听 Notify
                    if (mNotifyChar != null) {
                        gatt.setCharacteristicNotification(mNotifyChar, true);
                        BluetoothGattDescriptor descriptor = mNotifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(descriptor);
                        }
                    }

                    // 协商 MTU 为 30
                    gatt.requestMtu(30);

                    // 启动每秒心跳拉取遥测
                    mHandler.post(mHeartbeatRunnable);
                    broadcastStateChange();
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            byte[] val = characteristic.getValue();
            if (val != null) {
                mLatestTelemetry = ScooterProtocol.parseTelemetry(val);
                if (mLatestTelemetry.valid) {
                    broadcastTelemetry();
                }
            }
        }
    };

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

        byte[] packet = ScooterProtocol.buildPacket(mSeqCode, mCurrentR, mCurrentM, cmd, arg1, arg2);
        mSeqCode = (mSeqCode + 1) & 0xFFFF;
        if (mSeqCode == 0) mSeqCode = 1;

        mWriteChar.setValue(packet);
        mWriteChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        return mBluetoothGatt.writeCharacteristic(mWriteChar);
    }

    public ScooterProtocol.TelemetryData getLatestTelemetry() {
        return mLatestTelemetry;
    }

    public boolean isConnected() {
        return mIsConnected;
    }

    public void setProximityUnlockEnabled(boolean enabled) {
        this.mProximityUnlockEnabled = enabled;
    }

    private void broadcastStateChange() {
        Intent intent = new Intent(ACTION_BLE_STATE_CHANGED);
        intent.putExtra("connected", mIsConnected);
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
        sendBroadcast(intent);
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("壹酷 封神DZ 智能伴侣")
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
                    "壹酷电车后台服务",
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
        if (mBluetoothGatt != null) {
            mBluetoothGatt.disconnect();
            mBluetoothGatt.close();
        }
        if (mBleScanner != null && mScanCallback != null) {
            mBleScanner.stopScan(mScanCallback);
        }
    }
}
