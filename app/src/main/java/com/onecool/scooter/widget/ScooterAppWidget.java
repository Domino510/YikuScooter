package com.onecool.scooter.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.onecool.scooter.R;
import com.onecool.scooter.protocol.ScooterProtocol;
import com.onecool.scooter.service.BleVehicleService;

/**
 * 壹酷 封神DZ 桌面快捷控车小组件 (一键开机/断电/解限速)
 */
public class ScooterAppWidget extends AppWidgetProvider {

    public static final String ACTION_WIDGET_CMD = "com.onecool.scooter.WIDGET_CMD";
    public static final String EXTRA_CMD = "extra_cmd";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId, false, "--.-V", 0);
        }
    }

    public static void updateAppWidget(Context context, AppWidgetManager appWidgetManager,
                                       int appWidgetId, boolean isConnected, String voltage, int speed) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_scooter_control);

        // 更新状态文字
        views.setTextViewText(R.id.tv_widget_status, isConnected ? "已连接" : "未连接");
        views.setTextViewText(R.id.tv_widget_voltage, voltage);

        // 绑定一键开机事件
        views.setOnClickPendingIntent(R.id.btn_widget_power_on,
                getCommandPendingIntent(context, ScooterProtocol.CMD_POWER_ON, 1));

        // 绑定一键关机事件
        views.setOnClickPendingIntent(R.id.btn_widget_power_off,
                getCommandPendingIntent(context, ScooterProtocol.CMD_POWER_OFF, 2));

        // 绑定解除限速事件
        views.setOnClickPendingIntent(R.id.btn_widget_speed_limit,
                getCommandPendingIntent(context, ScooterProtocol.CMD_SPEED_LIMIT_OFF, 3));

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    private static PendingIntent getCommandPendingIntent(Context context, int cmd, int requestCode) {
        Intent intent = new Intent(context, ScooterAppWidget.class);
        intent.setAction(ACTION_WIDGET_CMD);
        intent.putExtra(EXTRA_CMD, cmd);
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        if (ACTION_WIDGET_CMD.equals(intent.getAction())) {
            int cmd = intent.getIntExtra(EXTRA_CMD, 0);
            if (cmd > 0) {
                // 向后台服务发送控制指令
                Intent serviceIntent = new Intent(context, BleVehicleService.class);
                serviceIntent.putExtra("cmd", cmd);
                androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent);
            }
        } else if (BleVehicleService.ACTION_STATUS_UPDATED.equals(intent.getAction())) {
            // 收到车辆遥测数据，自动刷新桌面组件
            float voltage = intent.getFloatExtra("voltage", 0.0f);
            int speed = intent.getIntExtra("speed", 0);

            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            ComponentName thisWidget = new ComponentName(context, ScooterAppWidget.class);
            int[] allIds = manager.getAppWidgetIds(thisWidget);

            for (int id : allIds) {
                updateAppWidget(context, manager, id, true, String.format("%.1fV", voltage), speed);
            }
        }
    }
}
