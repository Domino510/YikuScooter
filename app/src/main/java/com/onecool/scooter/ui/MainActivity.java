package com.onecool.scooter.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import java.util.Locale;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.onecool.scooter.R;
import com.onecool.scooter.protocol.ScooterProtocol;
import com.onecool.scooter.service.BleVehicleService;

/**
 * 壹酷 封神DZ 极客智能座舱主控台
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 100;

    private BleVehicleService mBleService;
    private boolean mIsBound = false;
    private boolean mIsReceiverRegistered = false;

    // GPS 卫星高精度测速与里程积分引擎
    private LocationManager mLocationManager;
    private Location mLastGpsLocation = null;
    private int mGpsSpeedKmh = 0;
    private float mGpsTripKm = 0.0f;
    private float mTotalMileageKm = 0.3f;
    private android.content.SharedPreferences mPrefs;
    private boolean mIsGpsTracking = false;

    private final LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            handleGpsLocationUpdate(location);
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(@NonNull String provider) {}
        @Override public void onProviderDisabled(@NonNull String provider) {}
    };

    // 顶部连接与状态
    private TextView tvStatusDot, tvConnStatus, tvDeviceSub;
    private View layoutConnBadge;

    // 核心行车仪表数据
    private TextView tvSpeed, tvGear, tvVoltage, tvBattery, tvOnceTrip, tvTotalTrip, tvErrorStatus, badgeLimitStatus;

    // 核心控车按钮与深空暗核磁贴
    private View btnPowerOn, btnPowerOff, btnLockToggle, btnSpeedLimitToggle, btnSeekVehicle;
    private ImageView ivTileLock, ivTileSpeed, ivTileSeek;
    private TextView tvTileLockTitle, tvTileLockSub;
    private TextView tvTileSpeedTitle, tvTileSpeedSub;
    private TextView tvTileSeekTitle, tvTileSeekSub;
    private View btnPowerCore;
    private TextView tvPowerCoreTitle, tvPowerCoreSub;

    // 智能近场与安防
    private SwitchCompat swProximityUnlock, swAutoLock, swOverspeedTip;
    private SeekBar seekDistanceLevel, seekSensitivityLevel;
    private TextView tvDistanceVal, tvSensitivityVal;

    // 控制器 ECU 调教
    private SwitchCompat swEcuTcs, swEcuStartMode, swEcuHdc, swEcuAssist, swEcuPGear, swEcuEbs;
    private SeekBar seekEbsCurrent;
    private TextView tvEbsCurrentVal;
    private Button btnApplyEcu;

    // 电池校准
    private TextView btnVolt48v, btnVolt60v, btnVolt72v;

    private int mEcuConfig = 6450;
    private int mEbsCurrent = 128;

    private final BleVehicleService.VehicleListener mVehicleListener = new BleVehicleService.VehicleListener() {
        @Override
        public void onConnectionChanged(boolean connected, int rssi) {
            runOnUiThread(() -> updateConnectionUI(connected, rssi));
        }

        @Override
        public void onTelemetryUpdated(ScooterProtocol.TelemetryData telemetry) {
            runOnUiThread(() -> updateTelemetryUI(telemetry));
        }
    };

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BleVehicleService.LocalBinder binder = (BleVehicleService.LocalBinder) service;
            mBleService = binder.getService();
            mIsBound = true;
            mBleService.addListener(mVehicleListener);
            updateConnectionUI(mBleService.isConnected(), mBleService.getLastRssi());
            ScooterProtocol.TelemetryData t = mBleService.getLatestTelemetry();
            if (t != null && t.valid) {
                updateTelemetryUI(t);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (mBleService != null) {
                mBleService.removeListener(mVehicleListener);
            }
            mBleService = null;
            mIsBound = false;
            updateConnectionUI(false, -100);
        }
    };

    private final BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (BleVehicleService.ACTION_STATUS_UPDATED.equals(action)) {
                if (mBleService != null) {
                    updateTelemetryUI(mBleService.getLatestTelemetry());
                }
            } else if (BleVehicleService.ACTION_BLE_STATE_CHANGED.equals(action)) {
                boolean connected = intent.getBooleanExtra("connected", false);
                int rssi = intent.getIntExtra("rssi", -100);
                updateConnectionUI(connected, rssi);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mPrefs = getSharedPreferences("scooter_prefs", Context.MODE_PRIVATE);
            mTotalMileageKm = mPrefs.getFloat("key_total_mileage_km", 0.3f);
            checkOrientationLayout();
            initViews();
            registerStatusReceiver();

            if (hasRequiredPermissions()) {
                startVehicleService();
            } else {
                requestRequiredPermissions();
            }
        } catch (Exception e) {
            Log.e(TAG, "onCreate error: ", e);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            checkOrientationLayout();
            initViews();
            if (mBleService != null) {
                updateConnectionUI(mBleService.isConnected(), mBleService.getLastRssi());
                ScooterProtocol.TelemetryData t = mBleService.getLatestTelemetry();
                if (t != null && t.valid) {
                    updateTelemetryUI(t);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onConfigurationChanged error: ", e);
        }
    }

    private void checkOrientationLayout() {
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            setContentView(R.layout.activity_main_hud);
        } else {
            setContentView(R.layout.activity_main_portrait);
        }
    }

    private void initViews() {
        // 顶部状态栏与点击重试胶囊
        tvStatusDot = findViewById(R.id.tv_status_dot);
        tvConnStatus = findViewById(R.id.tv_conn_status);
        tvDeviceSub = findViewById(R.id.tv_device_sub);
        layoutConnBadge = findViewById(R.id.layout_conn_badge);

        if (layoutConnBadge != null) {
            layoutConnBadge.setOnClickListener(v -> {
                if (mBleService != null) {
                    if (!mBleService.isConnected()) {
                        mBleService.connectToScooter();
                        Toast.makeText(this, "正在尝试连接车机...", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "车机已在线 (信号: " + mBleService.getLastRssi() + " dBm)", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    startVehicleService();
                }
            });
        }

        // 仪表盘
        tvSpeed = findViewById(R.id.tv_speed);
        tvGear = findViewById(R.id.tv_gear);
        tvVoltage = findViewById(R.id.tv_voltage);
        tvBattery = findViewById(R.id.tv_battery);
        tvOnceTrip = findViewById(R.id.tv_once_trip);
        tvTotalTrip = findViewById(R.id.tv_total_trip);
        if (tvTotalTrip != null) {
            tvTotalTrip.setOnClickListener(v -> showOdometerCalibrateDialog());
        }
        tvErrorStatus = findViewById(R.id.tv_error_status);
        badgeLimitStatus = findViewById(R.id.badge_limit_status);

        // 核心控车按钮与深空暗核磁贴
        btnPowerOn = findViewById(R.id.btn_power_on);
        btnPowerOff = findViewById(R.id.btn_power_off);
        btnLockToggle = findViewById(R.id.btn_lock_toggle);
        btnSpeedLimitToggle = findViewById(R.id.btn_speed_limit_toggle);
        btnSeekVehicle = findViewById(R.id.btn_seek_vehicle);

        ivTileLock = findViewById(R.id.iv_tile_lock);
        tvTileLockTitle = findViewById(R.id.tv_tile_lock_title);
        tvTileLockSub = findViewById(R.id.tv_tile_lock_sub);

        ivTileSpeed = findViewById(R.id.iv_tile_speed);
        tvTileSpeedTitle = findViewById(R.id.tv_tile_speed_title);
        tvTileSpeedSub = findViewById(R.id.tv_tile_speed_sub);

        ivTileSeek = findViewById(R.id.iv_tile_seek);
        tvTileSeekTitle = findViewById(R.id.tv_tile_seek_title);
        tvTileSeekSub = findViewById(R.id.tv_tile_seek_sub);

        btnPowerCore = findViewById(R.id.btn_power_core);
        tvPowerCoreTitle = findViewById(R.id.tv_power_core_title);
        tvPowerCoreSub = findViewById(R.id.tv_power_core_sub);

        if (btnPowerCore != null) {
            btnPowerCore.setOnClickListener(v -> {
                boolean isPowerOn = (mBleService != null && mBleService.getLatestTelemetry().isPowerOn);
                if (isPowerOn) {
                    sendCommand(ScooterProtocol.CMD_POWER_OFF, "关闭电门");
                } else {
                    sendCommand(ScooterProtocol.CMD_POWER_ON, "开启电门");
                }
            });
        }

        if (btnPowerOn != null) {
            btnPowerOn.setOnClickListener(v -> sendCommand(ScooterProtocol.CMD_POWER_ON, "开启电门"));
        }
        if (btnPowerOff != null) {
            btnPowerOff.setOnClickListener(v -> sendCommand(ScooterProtocol.CMD_POWER_OFF, "关闭电门"));
        }
        if (btnLockToggle != null) {
            btnLockToggle.setOnClickListener(v -> {
                boolean isLocked = (mBleService != null && mBleService.getLatestTelemetry().isLocked);
                int cmd = isLocked ? ScooterProtocol.CMD_UNLOCK : ScooterProtocol.CMD_LOCK;
                sendCommand(cmd, isLocked ? "撤防解锁" : "锁车设防");
            });
        }
        if (btnSpeedLimitToggle != null) {
            btnSpeedLimitToggle.setOnClickListener(v -> {
                boolean isLimit = (mBleService != null && mBleService.getLatestTelemetry().isSpeedLimit);
                int cmd = isLimit ? ScooterProtocol.CMD_SPEED_LIMIT_OFF : ScooterProtocol.CMD_SPEED_LIMIT_ON;
                sendCommand(cmd, isLimit ? "极速模式" : "恢复限速");
            });
        }
        if (btnSeekVehicle != null) {
            btnSeekVehicle.setOnClickListener(v -> sendCommand(ScooterProtocol.CMD_MUTE, "鸣笛寻车"));
        }

        // 智能近场与安防
        swProximityUnlock = findViewById(R.id.sw_proximity_unlock);
        swAutoLock = findViewById(R.id.sw_auto_lock);
        swOverspeedTip = findViewById(R.id.sw_overspeed_tip);
        seekDistanceLevel = findViewById(R.id.seek_distance_level);
        tvDistanceVal = findViewById(R.id.tv_distance_val);
        seekSensitivityLevel = findViewById(R.id.seek_sensitivity_level);
        tvSensitivityVal = findViewById(R.id.tv_sensitivity_val);

        if (swProximityUnlock != null) {
            if (mBleService != null) {
                swProximityUnlock.setChecked(mBleService.isProximityUnlockEnabled());
            }
            swProximityUnlock.setOnCheckedChangeListener((btn, isChecked) -> {
                if (mBleService != null) {
                    mBleService.setProximityUnlockEnabled(isChecked);
                }
                sendCommand(isChecked ? ScooterProtocol.CMD_GANYING_ON : ScooterProtocol.CMD_GANYING_OFF,
                        isChecked ? "开启感应解锁" : "关闭感应解锁");
            });
        }

        if (swAutoLock != null) {
            swAutoLock.setOnCheckedChangeListener((btn, isChecked) -> {
                sendCommand(isChecked ? ScooterProtocol.CMD_AUTO_LOCK_ON : ScooterProtocol.CMD_AUTO_LOCK_OFF,
                        isChecked ? "开启离车自动设防" : "关闭离车自动设防");
            });
        }

        if (swOverspeedTip != null) {
            swOverspeedTip.setOnCheckedChangeListener((btn, isChecked) -> {
                sendCommand(isChecked ? ScooterProtocol.CMD_CHAOSU_ON : ScooterProtocol.CMD_CHAOSU_OFF,
                        isChecked ? "开启超速警示音" : "关闭超速警示音");
            });
        }

        if (seekDistanceLevel != null) {
            seekDistanceLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int level = progress + 1; // 1~7
                    if (tvDistanceVal != null) tvDistanceVal.setText(level + " 档");
                    if (fromUser) {
                        sendCommand(ScooterProtocol.CMD_DISTANCE_BASE + level, "调节感应距离至 " + level + " 档");
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (seekSensitivityLevel != null) {
            seekSensitivityLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int level = progress + 1; // 1~4
                    if (tvSensitivityVal != null) tvSensitivityVal.setText(level + " 级");
                    if (fromUser) {
                        sendCommand(ScooterProtocol.CMD_SENSITIVITY_BASE + level, "调节报警灵敏度至 " + level + " 级");
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        // ECU 控制器调参
        swEcuTcs = findViewById(R.id.sw_ecu_tcs);
        swEcuStartMode = findViewById(R.id.sw_ecu_start_mode);
        swEcuHdc = findViewById(R.id.sw_ecu_hdc);
        swEcuAssist = findViewById(R.id.sw_ecu_assist);
        swEcuPGear = findViewById(R.id.sw_ecu_p_gear);
        swEcuEbs = findViewById(R.id.sw_ecu_ebs);
        seekEbsCurrent = findViewById(R.id.seek_ebs_current);
        tvEbsCurrentVal = findViewById(R.id.tv_ebs_current_val);
        btnApplyEcu = findViewById(R.id.btn_apply_ecu);

        if (seekEbsCurrent != null) {
            seekEbsCurrent.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    mEbsCurrent = Math.max(1, progress);
                    if (tvEbsCurrentVal != null) tvEbsCurrentVal.setText(String.valueOf(mEbsCurrent));
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }

        if (btnApplyEcu != null) {
            btnApplyEcu.setOnClickListener(v -> applyEcuConfig());
        }

        // 电池电压校准
        btnVolt48v = findViewById(R.id.btn_volt_48v);
        btnVolt60v = findViewById(R.id.btn_volt_60v);
        btnVolt72v = findViewById(R.id.btn_volt_72v);

        if (btnVolt48v != null) {
            btnVolt48v.setOnClickListener(v -> {
                updateVoltageTabsUI(48);
                sendCommand(ScooterProtocol.CMD_VOLTAGE_48V, "校准标称电压 48V");
            });
        }
        if (btnVolt60v != null) {
            btnVolt60v.setOnClickListener(v -> {
                updateVoltageTabsUI(60);
                sendCommand(ScooterProtocol.CMD_VOLTAGE_60V, "校准标称电压 60V");
            });
        }
        if (btnVolt72v != null) {
            btnVolt72v.setOnClickListener(v -> {
                updateVoltageTabsUI(72);
                sendCommand(ScooterProtocol.CMD_VOLTAGE_72V, "校准标称电压 72V");
            });
        }
    }

    private void registerStatusReceiver() {
        if (mIsReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BleVehicleService.ACTION_STATUS_UPDATED);
            filter.addAction(BleVehicleService.ACTION_BLE_STATE_CHANGED);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(mStatusReceiver, filter);
            }
            mIsReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "registerReceiver error", e);
        }
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] permissions = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQ_CODE);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQ_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            if (hasRequiredPermissions()) {
                startVehicleService();
            } else {
                Toast.makeText(this, "需开启蓝牙权限以连接车辆中控", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startVehicleService() {
        try {
            Intent serviceIntent = new Intent(this, BleVehicleService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "startVehicleService error", e);
        }
    }

    private void sendCommand(int cmd, String actionName) {
        if (mBleService != null && mBleService.isConnected()) {
            boolean ok = mBleService.sendCommand(cmd);
            if (ok) {
                Toast.makeText(this, "指令已下发: " + actionName, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "指令下发未响应，重试中...", Toast.LENGTH_SHORT).show();
            }
        } else {
            // 未连接时自动触发重连唤醒
            if (mBleService != null) {
                mBleService.connectToScooter();
            }
            Toast.makeText(this, "车机正在自动唤醒重连中，请靠近车身...", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyEcuConfig() {
        if (mBleService == null || !mBleService.isConnected()) {
            Toast.makeText(this, "车机未建立连接，无法写入ECU", Toast.LENGTH_SHORT).show();
            if (mBleService != null) mBleService.connectToScooter();
            return;
        }

        mEcuConfig = 0;
        if (swEcuHdc != null && swEcuHdc.isChecked()) {
            mEcuConfig |= (1 << 0); // 陡坡缓降
            mEcuConfig |= (1 << 1); // 坡道驻车
        }
        if (swEcuTcs != null && swEcuTcs.isChecked()) {
            mEcuConfig |= (1 << 2); // TCS 防滑
        }
        if (swEcuAssist != null && swEcuAssist.isChecked()) {
            mEcuConfig |= (1 << 3); // 推行/倒车辅助
        }
        if (swEcuStartMode != null && swEcuStartMode.isChecked()) {
            mEcuConfig |= (1 << 4); // 硬启动
        }
        if (swEcuPGear != null && swEcuPGear.isChecked()) {
            mEcuConfig |= (1 << 5); // P档保护
        }
        if (swEcuEbs != null && swEcuEbs.isChecked()) {
            mEcuConfig |= (1 << 13); // 动能回收
        }

        // 依据官方小程序规范：CMD 255 同时下发低字节与高字节
        mBleService.sendCommand(ScooterProtocol.CMD_ECU_CONFIG, mEcuConfig & 0xFF, (mEcuConfig >> 8) & 0xFF);
        // CMD 254 发送动能回收力矩电流与补码
        mBleService.sendCommand(ScooterProtocol.CMD_ECU_CURRENT, mEbsCurrent, 255 - mEbsCurrent);

        Toast.makeText(this, "ECU 动力调教参数已同步！", Toast.LENGTH_SHORT).show();
    }

    private void updateConnectionUI(boolean connected, int rssi) {
        if (tvStatusDot != null) {
            tvStatusDot.setTextColor(connected ? 0xFF00E676 : 0xFFFF3D00);
        }
        if (tvConnStatus != null) {
            if (connected) {
                String rssiStr = (rssi > -100 && rssi < 0) ? (" (" + rssi + " dBm)") : "";
                tvConnStatus.setText("已连接" + rssiStr);
                tvConnStatus.setTextColor(0xFF00E676);
            } else {
                tvConnStatus.setText("未连接 (点击重试)");
                tvConnStatus.setTextColor(0xFF94A3B8);
            }
        }
    }

    private void updateTelemetryUI(ScooterProtocol.TelemetryData t) {
        if (t == null || !t.valid) return;

        // 速度与档位 (调用 GPS 融合与 P/D 联动逻辑)
        refreshSpeedAndGearUI();

        // 电压与电量
        if (tvVoltage != null) tvVoltage.setText(String.format("%.1f V", t.voltage));
        if (tvBattery != null) tvBattery.setText(t.batteryPercent + " %");

        // 里程 (硬件回传优先，否则保持 GPS 累加里程)
        if (tvOnceTrip != null) {
            float displayTrip = (t.onceMileageKm > 0) ? t.onceMileageKm : mGpsTripKm;
            tvOnceTrip.setText(String.format(Locale.getDefault(), "%.1f km", displayTrip));
        }
        if (tvTotalTrip != null) {
            float displayTotal = (t.totalMileageKm > 0) ? t.totalMileageKm : mTotalMileageKm;
            tvTotalTrip.setText(String.format(Locale.getDefault(), "%.1f km", displayTotal));
        }

        // 故障自检与限速状态
        if (tvErrorStatus != null) {
            if (t.errorCode == 0) {
                tvErrorStatus.setText("整车系统自检正常");
                tvErrorStatus.setTextColor(0xFF00E676);
            } else {
                tvErrorStatus.setText("系统告警: " + t.errorMsg);
                tvErrorStatus.setTextColor(0xFFFF3D00);
            }
        }

        if (badgeLimitStatus != null) {
            if (t.isSpeedLimit) {
                badgeLimitStatus.setText("25km/h 限速");
                badgeLimitStatus.setTextColor(0xFFFFB300);
            } else {
                badgeLimitStatus.setText("极速模式");
                badgeLimitStatus.setTextColor(0xFF00E5FF);
            }
        }

        // 方案 A 特斯拉风磁贴动态状态与微光指示同步
        if (tvTileLockTitle != null && tvTileLockSub != null && ivTileLock != null) {
            if (t.isLocked) {
                tvTileLockTitle.setText("撤防解锁");
                tvTileLockSub.setText("已设防 · 防盗中");
                tvTileLockSub.setTextColor(0xFF00E5FF);
                ivTileLock.setImageTintList(ContextCompat.getColorStateList(this, R.color.cyan));
                if (btnLockToggle != null) btnLockToggle.setBackgroundResource(R.drawable.bg_magnetic_tile_cyan);
            } else {
                tvTileLockTitle.setText("车辆设防");
                tvTileLockSub.setText("未设防 · 待上锁");
                tvTileLockSub.setTextColor(0xFFFFB300);
                ivTileLock.setImageTintList(ContextCompat.getColorStateList(this, R.color.amber));
                if (btnLockToggle != null) btnLockToggle.setBackgroundResource(R.drawable.bg_magnetic_tile_amber);
            }
        }

        if (tvTileSpeedTitle != null && tvTileSpeedSub != null && ivTileSpeed != null) {
            if (t.isSpeedLimit) {
                tvTileSpeedTitle.setText("解除限速");
                tvTileSpeedSub.setText("25km/h 限速");
                tvTileSpeedSub.setTextColor(0xFFFFB300);
                ivTileSpeed.setImageTintList(ContextCompat.getColorStateList(this, R.color.amber));
                if (btnSpeedLimitToggle != null) btnSpeedLimitToggle.setBackgroundResource(R.drawable.bg_magnetic_tile_amber);
            } else {
                tvTileSpeedTitle.setText("动力极速");
                tvTileSpeedSub.setText("极速全开 · 狂暴");
                tvTileSpeedSub.setTextColor(0xFF00E5FF);
                ivTileSpeed.setImageTintList(ContextCompat.getColorStateList(this, R.color.cyan));
                if (btnSpeedLimitToggle != null) btnSpeedLimitToggle.setBackgroundResource(R.drawable.bg_magnetic_tile_cyan);
            }
        }

        if (ivTileSeek != null) {
            ivTileSeek.setImageTintList(ContextCompat.getColorStateList(this, R.color.text_dim));
        }

        // 电池分段选择器同步
        updateVoltageTabsUI(t.sysVoltage);
    }


    private void startGpsTracking() {
        if (mIsGpsTracking) return;
        try {
            if (mLocationManager == null) {
                mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            }
            if (mLocationManager != null && hasRequiredPermissions()) {
                if (mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 300, 0f, mLocationListener);
                    mIsGpsTracking = true;
                    Log.i(TAG, "GPS 卫星实时测速已启动 (300ms 刷新)");
                } else if (mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 500, 0f, mLocationListener);
                    mIsGpsTracking = true;
                }
            }
        } catch (SecurityException se) {
            Log.w(TAG, "GPS 权限受限: " + se.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "启动 GPS 测速异常: " + e.getMessage());
        }
    }

    private void stopGpsTracking() {
        if (mLocationManager != null && mIsGpsTracking) {
            try {
                mLocationManager.removeUpdates(mLocationListener);
            } catch (Exception ignored) {}
            mIsGpsTracking = false;
        }
    }

    private void handleGpsLocationUpdate(Location location) {
        if (location == null) return;

        // 计算 GPS 卫星车速
        int speed = 0;
        if (location.hasSpeed()) {
            speed = Math.round(location.getSpeed() * 3.6f);
        } else if (mLastGpsLocation != null) {
            long timeDeltaMs = location.getTime() - mLastGpsLocation.getTime();
            if (timeDeltaMs > 300 && timeDeltaMs < 8000) {
                float distM = mLastGpsLocation.distanceTo(location);
                speed = Math.round((distM / (timeDeltaMs / 1000.0f)) * 3.6f);
            }
        }

        // 过滤静止漂移抖动 (时速低于 2km/h 视为静止)
        if (speed < 2) {
            speed = 0;
        }
        mGpsSpeedKmh = speed;

        // 里程积分累加
        if (mLastGpsLocation != null && speed > 0) {
            float deltaM = mLastGpsLocation.distanceTo(location);
            if (deltaM > 1.0f && deltaM < 120.0f) {
                float deltaKm = deltaM / 1000.0f;
                mGpsTripKm += deltaKm;
                mTotalMileageKm += deltaKm;
                if (mPrefs != null) {
                    mPrefs.edit().putFloat("key_total_mileage_km", mTotalMileageKm).apply();
                }
            }
        }
        mLastGpsLocation = location;

        runOnUiThread(this::refreshSpeedAndGearUI);
    }

    private void refreshSpeedAndGearUI() {
        // 车速优先级：硬件回传车速优先，无硬件测速线时由 GPS 卫星真实时速联动
        int displaySpeed = (mBleService != null && mBleService.getLatestTelemetry().speedKmh > 0)
                ? mBleService.getLatestTelemetry().speedKmh
                : mGpsSpeedKmh;

        if (tvSpeed != null) {
            tvSpeed.setText(String.valueOf(displaySpeed));
        }

        // 里程显示：硬件回传优先，否则使用高精 GPS 积分与持久化总里程
        if (tvOnceTrip != null) {
            float displayTrip = (mBleService != null && mBleService.getLatestTelemetry().onceMileageKm > 0)
                    ? mBleService.getLatestTelemetry().onceMileageKm
                    : mGpsTripKm;
            tvOnceTrip.setText(String.format(Locale.getDefault(), "%.1f km", displayTrip));
        }
        if (tvTotalTrip != null) {
            float displayTotal = (mBleService != null && mBleService.getLatestTelemetry().totalMileageKm > 0)
                    ? mBleService.getLatestTelemetry().totalMileageKm
                    : mTotalMileageKm;
            tvTotalTrip.setText(String.format(Locale.getDefault(), "%.1f km", displayTotal));
        }

        // P档与D档与车速智能联动逻辑：
        boolean isPowerOn = (mBleService != null && mBleService.getLatestTelemetry().isPowerOn);
        if (tvGear != null) {
            if (!isPowerOn) {
                tvGear.setText("P 档");
                tvGear.setTextColor(0xFFFFB300);
            } else {
                // 已上电时：静止 (速度<=1km/h) 处于驻车 P 档；开始行驶 (速度>1km/h) 自动切换为 D 档！
                if (displaySpeed <= 1) {
                    tvGear.setText("P 档");
                    tvGear.setTextColor(0xFFFFB300);
                } else if (displaySpeed > 25) {
                    tvGear.setText("D 极速");
                    tvGear.setTextColor(0xFF00E5FF);
                } else {
                    tvGear.setText("D 档");
                    tvGear.setTextColor(0xFF00E5FF);
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasRequiredPermissions()) {
            startGpsTracking();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopGpsTracking();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopGpsTracking();
        if (mIsReceiverRegistered) {
            try {
                unregisterReceiver(mStatusReceiver);
            } catch (Exception ignored) {}
            mIsReceiverRegistered = false;
        }
        if (mIsBound) {
            try {
                if (mBleService != null) {
                    mBleService.removeListener(mVehicleListener);
                }
                unbindService(mServiceConnection);
            } catch (Exception ignored) {}
            mIsBound = false;
        }
    }

    private void showOdometerCalibrateDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("校准总行驶里程 (km)");
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint(String.format(Locale.getDefault(), "当前: %.1f km", mTotalMileageKm));
        input.setText(String.format(Locale.getDefault(), "%.1f", mTotalMileageKm));
        input.selectAll();
        builder.setView(input);

        builder.setPositiveButton("保存", (dialog, which) -> {
            try {
                String valStr = input.getText().toString().trim();
                float val = Float.parseFloat(valStr);
                if (val >= 0) {
                    mTotalMileageKm = val;
                    if (mPrefs != null) {
                        mPrefs.edit().putFloat("key_total_mileage_km", mTotalMileageKm).apply();
                    }
                    if (tvTotalTrip != null) {
                        tvTotalTrip.setText(String.format(Locale.getDefault(), "%.1f km", mTotalMileageKm));
                    }
                    Toast.makeText(this, "总里程已校准为: " + String.format(Locale.getDefault(), "%.1f km", mTotalMileageKm), Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void updateVoltageTabsUI(int sysVolt) {
        if (btnVolt48v != null) {
            btnVolt48v.setBackgroundResource(sysVolt == 48 ? R.drawable.bg_segmented_item_selected : R.drawable.bg_segmented_item_normal);
            btnVolt48v.setTextColor(sysVolt == 48 ? 0xFF00E5FF : 0xFF94A3B8);
        }
        if (btnVolt60v != null) {
            btnVolt60v.setBackgroundResource(sysVolt == 60 ? R.drawable.bg_segmented_item_selected : R.drawable.bg_segmented_item_normal);
            btnVolt60v.setTextColor(sysVolt == 60 ? 0xFF00E5FF : 0xFF94A3B8);
        }
        if (btnVolt72v != null) {
            btnVolt72v.setBackgroundResource(sysVolt == 72 ? R.drawable.bg_segmented_item_selected : R.drawable.bg_segmented_item_normal);
            btnVolt72v.setTextColor(sysVolt == 72 ? 0xFF00E5FF : 0xFF94A3B8);
        }
    }
}