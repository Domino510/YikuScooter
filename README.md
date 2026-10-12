# 壹酷 封神DZ 原生 Android 控车与智能仪表项目

本工程为基于反编译协议研发的 **原生 Android 独立应用程序**，彻底告别微信小程序与网页端 Web Bluetooth 的浏览器沙盒限制。

---

## 核心技术与特性

1. **底层真实配对与长连接（解决断连与“滴滴滴”报警）**：
   * 在建立 GATT 连接前调用 `device.createBond()` 触发系统级安全绑定；
   * 通过 `BluetoothLeScanner` 监听车机每秒广播的 13 字节 Manufacturer Data，**动态提取车机实时滚动的 $R$ 密钥**，杜绝硬编码静态密钥导致的鉴权失效；
   * 自动维持每秒 `[85, 170, 90]` 状态轮询保活心跳包。

2. **真·无感靠近解锁服务（Background Proximity Service）**：
   * 注册前台驻留服务（`BleVehicleService`），手机放口袋靠近车辆 1.5~2 米（RSSI $\ge -70$ dBm）自动发出撤防与通电指令；
   * 离开车辆 5 米外（RSSI $\le -88$ dBm 或信号断开）自动触发防盗锁车。

3. **桌面快捷微型小组件（App Widget）**：
   * 在手机桌面直接添加卡片组件，免开 App，一点即实现：
     * ⚡ **一键开机**
     * 🔌 **一键关机**
     * 🚀 **解除限速**
   * 组件上实时回显母线电压（如 59.4V）。

4. **双形态交互界面**：
   * **横屏（车把 HUD 模式）**：全屏常亮防息屏、96px 超大速度动效、P档/档位指示、精准母线电压与电量进度条；
   * **竖屏（控制中心）**：一键控车、灵敏度调节、电压校准及免密完全解锁的 **ECU 调参专区**（TCS、软硬启动、陡坡缓降、1~255级动能回收电流）。

---

## 项目工程结构

```
YikuScooterApp/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/onecool/scooter/
│           ├── protocol/
│           │   └── ScooterProtocol.java       # 全功能编解码引擎与遥测模型
│           ├── service/
│           │   └── BleVehicleService.java     # 前台长连接、动态R码监听、无感解锁
│           ├── widget/
│           │   └── ScooterAppWidget.java      # 桌面一键控车小组件
│           └── ui/
│               └── MainActivity.java          # HUD 仪表与控制台主界面
└── README.md
```
