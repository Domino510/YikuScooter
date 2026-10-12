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
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.onecool.scooter.R;
import com.onecool.scooter.protocol.ScooterProtocol;
import com.onecool.scooter.service.BleVehicleService;

/**
 * 壹酷 封神DZ 主交互界面（支持横屏HUD赛道仪表 / 竖屏极客控制台）
 */
public class MainActivity extends AppCompatActivity {

    private BleVehicleService mBleService;
    private boolean mIsBound = false;

    // UI 组件
    private TextView tvSpeed, tvGear, tvVoltage, tvBattery, tvOnceTrip, tvTotalTrip, tvStatusDot, tvConnStatus;
    private View indLock, indPower, indSpeedLimit, indError;
    private Button btnPowerOn, btnPowerOff, btnLockToggle, btnSpeedLimitToggle;
    private Switch swProximity, swAutoLock, swOverspeed;
    private Switch swTcs, swStartMode, swHdc, swEbs;
    private SeekBar seekEbsCurrent;
    private TextView tvEbsCurrentVal;

    private int mEcuConfig = 6450;
    private int mEbsCurrent = 128;

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BleVehicleService.LocalBinder binder = (BleVehicleService.LocalBinder) service;
            mBleService = binder.getService();
            mIsBound = true;
            updateConnectionUI(mBleService.isConnected());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBleService = null;
            mIsBound = false;
            updateConnectionUI(false);
        }
    };

    private final BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BleVehicleService.ACTION_STATUS_UPDATED.equals(action)) {
                int speed = intent.getIntExtra("speed", 0);
                float voltage = intent.getFloatExtra("voltage", 0.0f);
                int battery = intent.getIntExtra("battery", 0);
                int gear = intent.getIntExtra("gear", 1);
                boolean powerOn = intent.getBooleanExtra("powerOn", false);
                boolean locked = intent.getBooleanExtra("locked", false);
                boolean speedLimit = intent.getBooleanExtra("speedLimit", false);
                int totalKm = intent.getIntExtra("totalMileage", 0);
                float onceKm = intent.getFloatExtra("onceMileage", 0.0f);

                updateTelemetryUI(speed, voltage, battery, gear, powerOn, locked, speedLimit, totalKm, onceKm);
            } else if (BleVehicleService.ACTION_BLE_STATE_CHANGED.equals(action)) {
                boolean connected = intent.getBooleanExtra("connected", false);
                updateConnectionUI(connected);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 保持屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 检查横竖屏加载对应布局
        checkOrientationLayout();

        initViews();
        checkAndRequestPermissions();

        // 启动并绑定后台长连接服务
        Intent serviceIntent = new Intent(this, BleVehicleService.class);
        startService(serviceIntent);
        bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        // 注册广播接收
        IntentFilter filter = new IntentFilter();
        filter.addAction(BleVehicleService.ACTION_STATUS_UPDATED);
        filter.addAction(BleVehicleService.ACTION_BLE_STATE_CHANGED);
        registerReceiver(mStatusReceiver, filter);
    }

    private void checkOrientationLayout() {
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            setContentView(R.layout.activity_main_hud); // 横屏专用 HUD 赛道表盘
        } else {
            setContentView(R.layout.activity_main_portrait); // 竖屏全功能控制台
        }
    }

    private void initViews() {
        tvSpeed = findViewById(R.id.tv_speed);
        tvGear = findViewById(R.id.tv_gear);
        tvVoltage = findViewById(R.id.tv_voltage);
        tvBattery = findViewById(R.id.tv_battery);
        tvOnceTrip = findViewById(R.id.tv_once_trip);
        tvTotalTrip = findViewById(R.id.tv_total_trip);
        tvConnStatus = findViewById(R.id.tv_conn_status);
        tvStatusDot = findViewById(R.id.tv_status_dot);

        indLock = findViewById(R.id.ind_lock);
        indPower = findViewById(R.id.ind_power);
        indSpeedLimit = findViewById(R.id.ind_speed_limit);
        indError = findViewById(R.id.ind_error);

        btnPowerOn = findViewById(R.id.btn_power_on);
        btnPowerOff = findViewById(R.id.btn_power_off);
        btnLockToggle = findViewById(R.id.btn_lock_toggle);
        btnSpeedLimitToggle = findViewById(R.id.btn_speed_limit_toggle);

        if (btnPowerOn != null) {
            btnPowerOn.setOnClickListener(v -> sendCommand(ScooterProtocol.CMD_POWER_ON));
        }
        if (btnPowerOff != null) {
            btnPowerOff.setOnClickListener(v -> sendCommand(ScooterProtocol.CMD_POWER_OFF));
        }
        if (btnLockToggle != null) {
            btnLockToggle.setOnClickListener(v -> {
                boolean isLocked = (mBleService != null && mBleService.getLatestTelemetry().isLocked);
                sendCommand(isLocked ? ScooterProtocol.CMD_UNLOCK : ScooterProtocol.CMD_LOCK);
            });
        }
        if (btnSpeedLimitToggle != null) {
            btnSpeedLimitToggle.setOnClickListener(v -> {
                boolean isLimit = (mBleService != null && mBleService.getLatestTelemetry().isSpeedLimit);
                sendCommand(isLimit ? ScooterProtocol.CMD_SPEED_LIMIT_OFF : ScooterProtocol.CMD_SPEED_LIMIT_ON);
            });
        }

        // ECU 调参绑定
        swTcs = findViewById(R.id.sw_ecu_tcs);
        swStartMode = findViewById(R.id.sw_ecu_start_mode);
        swHdc = findViewById(R.id.sw_ecu_hdc);
        swEbs = findViewById(R.id.sw_ecu_ebs);
        seekEbsCurrent = findViewById(R.id.seek_ebs_current);
        tvEbsCurrentVal = findViewById(R.id.tv_ebs_current_val);

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

        Button btnApplyEcu = findViewById(R.id.btn_apply_ecu);
        if (btnApplyEcu != null) {
            btnApplyEcu.setOnClickListener(v -> applyEcuConfig());
        }
    }

    private void sendCommand(int cmd) {
        if (mBleService != null && mBleService.isConnected()) {
            boolean success = mBleService.sendCommand(cmd);
            if (!success) {
                Toast.makeText(this, "指令发送失败，正在重试连接...", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "车机未连接，请靠近车辆", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyEcuConfig() {
        if (mBleService == null || !mBleService.isConnected()) {
            Toast.makeText(this, "车机未连接，无法下发ECU参数", Toast.LENGTH_SHORT).show();
            return;
        }

        // 重新计算掩码
        if (swTcs != null && swTcs.isChecked()) mEcuConfig |= (1 << 2); else mEcuConfig &= ~(1 << 2);
        if (swStartMode != null && swStartMode.isChecked()) mEcuConfig |= (1 << 4); else mEcuConfig &= ~(1 << 4);
        if (swHdc != null && swHdc.isChecked()) { mEcuConfig |= (1 << 0); mEcuConfig |= (1 << 1); }
        else { mEcuConfig &= ~(1 << 0); mEcuConfig &= ~(1 << 1); }
        if (swEbs != null && swEbs.isChecked()) mEcuConfig |= (1 << 13); else mEcuConfig &= ~(1 << 13);

        // 下发常规位与高级位
        mBleService.sendCommand(ScooterProtocol.CMD_ECU_CONFIG_LOW, mEcuConfig & 0xFF, 0);
        mBleService.sendCommand(ScooterProtocol.CMD_ECU_CONFIG_HIGH, (mEcuConfig >> 8) & 0xFF, 0);
        mBleService.sendCommand(ScooterProtocol.CMD_ECU_CURRENT, mEbsCurrent, 255 - mEbsCurrent);

        Toast.makeText(this, "ECU 高级参数已成功同步至控制器！", Toast.LENGTH_SHORT).show();
    }

    private void updateConnectionUI(boolean connected) {
        if (tvConnStatus != null) tvConnStatus.setText(connected ? "已连接" : "等待连接...");
        if (tvStatusDot != null) tvStatusDot.setTextColor(connected ? 0xFF10B981 : 0xFFEF4444);
    }

    private void updateTelemetryUI(int speed, float voltage, int battery, int gear,
                                   boolean powerOn, boolean locked, boolean speedLimit,
                                   int totalKm, float onceKm) {
        if (tvSpeed != null) tvSpeed.setText(String.valueOf(speed));
        if (tvVoltage != null) tvVoltage.setText(String.format("%.1f", voltage));
        if (tvBattery != null) tvBattery.setText(String.valueOf(battery));
        if (tvGear != null) tvGear.setText(gear == 0 ? "P" : String.valueOf(gear));
        if (tvOnceTrip != null) tvOnceTrip.setText(String.format("%.1f", onceKm));
        if (tvTotalTrip != null) tvTotalTrip.setText(String.valueOf(totalKm));

        if (indPower != null) indPower.setSelected(powerOn);
        if (indLock != null) indLock.setSelected(locked);
        if (indSpeedLimit != null) indSpeedLimit.setSelected(speedLimit);
    }

    private void checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            String[] permissions = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
            for (String p : permissions) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, permissions, 100);
                    break;
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mStatusReceiver);
        if (mIsBound) {
            unbindService(mServiceConnection);
            mIsBound = false;
        }
    }
}
