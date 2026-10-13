package com.onecool.scooter.protocol;

/**
 * 壹酷 封神DZ / 易骑 RLA2 中控全功能底层协议编解码引擎
 */
public class ScooterProtocol {

    public static final String DEVICE_NAME = "smart_ebike";
    public static final String DEVICE_MAC = "65:FF:00:0C:25:64";

    public static final String SERVICE_UUID = "86912001-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String WRITE_CHAR_UUID = "86912002-B5A3-F393-E0A9-E50E24DCCA9E";
    public static final String NOTIFY_CHAR_UUID = "86912003-B5A3-F393-E0A9-E50E24DCCA9E";

    // 核心控车指令
    public static final int CMD_LOCK = 1;              // 设防上锁
    public static final int CMD_UNLOCK = 2;            // 撤防解锁
    public static final int CMD_POWER_ON = 3;          // 上电开机
    public static final int CMD_POWER_OFF = 4;         // 断电关机
    public static final int CMD_MUTE = 6;              // 寻车鸣笛 / 静音设防
    public static final int CMD_OPEN_CUSHION = 7;      // 开座垫锁
    public static final int CMD_SPEED_LIMIT_OFF = 8;   // 解除限速 (25km/h)
    public static final int CMD_SPEED_LIMIT_ON = 9;    // 恢复限速 (25km/h)

    // 智能近场与安防功能指令
    public static final int CMD_GANYING_ON = 32;       // 距离感应开
    public static final int CMD_GANYING_OFF = 33;      // 距离感应关
    public static final int CMD_CHAOSU_ON = 34;        // 超速鸣笛提示开
    public static final int CMD_CHAOSU_OFF = 35;       // 超速鸣笛提示关
    public static final int CMD_AUTO_LOCK_ON = 38;     // 自动设防开
    public static final int CMD_AUTO_LOCK_OFF = 39;    // 自动设防关
    public static final int CMD_HEARTBEAT = 85;        // 心跳轮询 (0x55)

    // 防盗灵敏度 1~4 级 (CMD 17~20)
    public static final int CMD_SENSITIVITY_BASE = 16; // 加上 1~4 级

    // 系统标称电压校准 (CMD 48~55)
    public static final int CMD_VOLTAGE_36V = 48;
    public static final int CMD_VOLTAGE_48V = 49;
    public static final int CMD_VOLTAGE_60V = 50;     // 封神DZ 加长锂电标称
    public static final int CMD_VOLTAGE_64V = 51;
    public static final int CMD_VOLTAGE_72V = 52;
    public static final int CMD_VOLTAGE_84V = 54;
    public static final int CMD_VOLTAGE_96V = 55;

    // 感应距离档位 1~7 级 (CMD 65~71)
    public static final int CMD_DISTANCE_BASE = 64;   // 加上 1~7 级

    // ECU 控制器调参指令 (免密工程通道，对应官方小程序 ecuConfig2 实现)
    public static final int CMD_ECU_CONFIG = 255;      // 发送功能配置 (arg1: 低8位, arg2: 高8位)
    public static final int CMD_ECU_CURRENT = 254;     // 发送能量回收制动力矩电流 (arg1: current 1~255, arg2: 255 - current)

    // 默认已知配对密钥（可被广播包动态更新）
    public static byte[] DEFAULT_R = new byte[]{(byte) 0x1D, (byte) 0xAC, (byte) 0x58, (byte) 0xCB};
    public static byte[] DEFAULT_M = new byte[]{(byte) 0xB3, (byte) 0xCE, (byte) 0xA1, (byte) 0x39};

    /**
     * 动态掩码计算算法
     */
    public static int calcC(int seq) {
        int r = (16 + (seq & 0xFF)) & 0xFF;
        r = (255 & (17 ^ r)) + 25 & 0xFF;
        r = 255 & (36 ^ r);
        r = (r + ((seq >> 8) & 0xFF)) & 0xFF;
        r = 255 & (107 ^ r);
        r = (r + 59) & 0xFF;
        r = 255 & (58 ^ r);
        return r & 127;
    }

    /**
     * 构建发送给车机的 15 字节控制帧
     */
    public static byte[] buildPacket(int seq, byte[] rKey, byte[] mKey, int cmd, int arg1, int arg2) {
        if (rKey == null || rKey.length < 4) rKey = DEFAULT_R;
        if (mKey == null || mKey.length < 4) mKey = DEFAULT_M;

        int c = calcC(seq);
        byte[] buf = new byte[15];
        buf[0] = (byte) 0x88;
        buf[1] = (byte) (seq & 0xFF);
        buf[2] = (byte) ((seq >> 8) & 0xFF);

        // 混淆 R 和 M
        for (int i = 0; i < 4; i++) {
            buf[3 + i] = (byte) ((rKey[i] + c) & 0xFF);
            buf[7 + i] = (byte) ((mKey[i] + c) & 0xFF);
        }

        buf[11] = (byte) ((cmd + c) & 0xFF);
        buf[12] = (byte) ((arg1 + c) & 0xFF);
        buf[13] = (byte) ((arg2 + c) & 0xFF);

        // 和校验
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            sum += (buf[i] & 0xFF);
        }
        buf[14] = (byte) (sum & 0xFF);

        return buf;
    }

    /**
     * 遥测数据模型
     */
    public static class TelemetryData {
        public boolean valid = false;
        public int state = 0;             // 1: 设防, 2: 解防, 3: 上电, 4: 报警, 5: 静音设防
        public boolean isPowerOn = false;
        public boolean isLocked = false;
        public boolean isPShow = false;
        public boolean isSpeedLimit = false;
        public boolean isAutoLock = false;
        public boolean isOverspeedTips = false;
        public boolean isProximitySensorOn = false;
        public int errorCode = 0;
        public String errorMsg = "系统正常";
        public int gear = 1;
        public int sensitivity = 2;       // 1~4
        public int distanceLevel = 4;      // 1~7
        public int totalMileageKm = 0;
        public float onceMileageKm = 0.0f;
        public int speedKmh = 0;
        public int batteryPercent = 0;
        public float voltage = 0.0f;
        public int sysVoltage = 60;
    }

    private static final String[] FAULT_NAMES = new String[]{
            "电机缺相", "刹车故障", "巡航状态", "欠压保护",
            "控制器故障", "转把故障", "霍尔故障", "电子刹车"
    };

    /**
     * 解析车机上报的 24 字节数据帧
     */
    public static TelemetryData parseTelemetry(byte[] data) {
        TelemetryData t = new TelemetryData();
        if (data == null || data.length < 23 || data[0] != 0x66) {
            return t;
        }

        int sum = 0;
        for (int i = 0; i < data.length - 1; i++) {
            sum += (data[i] & 0xFF);
        }
        if ((sum & 0xFF) != (data[data.length - 1] & 0xFF)) {
            return t; // 校验失败
        }

        t.valid = true;
        t.state = data[9] & 0xFF;
        t.isPowerOn = (t.state == 3);
        t.isLocked = (t.state == 1 || t.state == 5);

        int flags = data[10] & 0xFF;
        t.isProximitySensorOn = (flags & 1) == 1;
        t.isPShow = (flags & 2) == 2;
        t.isSpeedLimit = (flags & 4) == 4;
        t.isAutoLock = (flags & 16) == 16;
        t.isOverspeedTips = (flags & 32) == 32;

        t.errorCode = data[11] & 0xFF;
        if (t.errorCode == 0) {
            t.errorMsg = "系统正常";
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (((t.errorCode >> i) & 1) == 1) {
                    sb.append(FAULT_NAMES[i]).append(" ");
                }
            }
            t.errorMsg = sb.toString().trim();
        }

        t.gear = data[12] & 7;
        t.sensitivity = Math.max(1, Math.min(4, ((data[12] >> 4) & 7)));

        t.totalMileageKm = ((data[13] & 0xFF) << 16) | ((data[14] & 0xFF) << 8) | (data[15] & 0xFF);
        t.onceMileageKm = (data[16] & 0xFF) + 0.1f * (data[17] & 0xFF);
        t.speedKmh = data[18] & 0xFF;
        t.batteryPercent = data[19] & 0xFF;
        t.voltage = (data[20] & 0xFF) + 0.1f * (data[21] & 0xFF);

        if (data.length >= 24) {
            // 第22字节为位掩码: 1=36V, 2=48V, 4=60V, 8=64V, 16=72V, 32=80V, 64=84V, 128=96V
            int svMask = data[22] & 0xFF;
            switch (svMask) {
                case 1: t.sysVoltage = 36; break;
                case 2: t.sysVoltage = 48; break;
                case 4: t.sysVoltage = 60; break;
                case 8: t.sysVoltage = 64; break;
                case 16: t.sysVoltage = 72; break;
                case 32: t.sysVoltage = 80; break;
                case 64: t.sysVoltage = 84; break;
                case 128: t.sysVoltage = 96; break;
                default: t.sysVoltage = 60; break;
            }
            t.distanceLevel = Math.max(1, Math.min(7, (data[23] & 0x0F)));
        }

        return t;
    }
}
