import SwiftUI

struct ContentView: View {
    @StateObject private var ble = BleVehicleManager.shared
    @StateObject private var gps = LocationSpeedEngine.shared

    @State private var showOdometerAlert = false
    @State private var odoInputText = ""

    // AirLock
    @State private var proxUnlock = true
    @State private var autoLock = false
    @State private var overspeedTip = false
    @State private var distanceLevel: Double = 4
    @State private var sensitivityLevel: Double = 2

    // ECU
    @State private var ecuTcs = false
    @State private var ecuStartMode = false
    @State private var ecuHdc = false
    @State private var ecuAssist = false
    @State private var ecuPGear = true
    @State private var ecuEbs = true
    @State private var ebsCurrent: Double = 128

    // Battery Tab
    @State private var selectedVolt: Int = 60

    private var displaySpeed: Int {
        ble.telemetry.speedKmh > 0 ? ble.telemetry.speedKmh : gps.speedKmh
    }

    private var gearText: String {
        if !ble.telemetry.isPowerOn {
            return "P 档"
        }
        if displaySpeed <= 1 {
            return "P 档"
        } else if displaySpeed > 25 {
            return "D 极速"
        } else {
            return "D 档"
        }
    }

    private var gearColor: Color {
        gearText.contains("P") ? Color.orange : Color.cyan
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                // 顶部设备与连接状态卡片
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("壹酷 · 封神DZ")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                        Text("加长锂电版 · 60V 平安700智控旗舰")
                            .font(.system(size: 12))
                            .foregroundColor(Color(white: 0.55))
                    }
                    Spacer()
                    HStack(spacing: 6) {
                        Circle()
                            .fill(ble.isConnected ? Color.green : Color.red)
                            .frame(width: 8, height: 8)
                        Text(ble.isConnected ? "已在线" : "等待连接")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color(white: 0.12))
                    .cornerRadius(14)
                }
                .padding(16)
                .background(Color(red: 0.07, green: 0.09, blue: 0.14))
                .cornerRadius(16)

                // 赛道级极光仪表舱
                VStack(spacing: 16) {
                    HStack(alignment: .center) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("实时车速")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(Color(white: 0.5))
                            HStack(alignment: .bottom, spacing: 4) {
                                Text("\(displaySpeed)")
                                    .font(.system(size: 58, weight: .bold, design: .rounded))
                                    .foregroundColor(.white)
                                Text("KM/H")
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundColor(Color(white: 0.5))
                                    .padding(.bottom, 8)
                            }
                        }
                        Spacer()
                        VStack(spacing: 4) {
                            Text("动力档位")
                                .font(.system(size: 11))
                                .foregroundColor(Color(white: 0.5))
                            Text(gearText)
                                .font(.system(size: 24, weight: .bold))
                                .foregroundColor(gearColor)
                        }
                        .padding(.horizontal, 18)
                        .padding(.vertical, 12)
                        .background(Color(red: 0.10, green: 0.13, blue: 0.19))
                        .cornerRadius(12)
                    }

                    // 四象限数据卡片
                    HStack(spacing: 6) {
                        metricBox(title: "母线电压", val: String(format: "%.1f V", ble.telemetry.voltage > 0 ? ble.telemetry.voltage : 58.0), color: .cyan)
                        metricBox(title: "剩余电量", val: "\(ble.telemetry.batteryPercent > 0 ? ble.telemetry.batteryPercent : 81) %", color: .green)
                        metricBox(title: "单次里程", val: String(format: "%.1f km", gps.tripDistanceKm), color: .white)
                        metricBox(title: "整车总计", val: String(format: "%.1f km", gps.totalDistanceKm), color: .white)
                            .onTapGesture {
                                odoInputText = String(format: "%.1f", gps.totalDistanceKm)
                                showOdometerAlert = true
                            }
                    }

                    // 自检状态条
                    HStack {
                        Text(ble.telemetry.errorCode == 0 ? "整车系统自检正常" : "系统告警")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(.green)
                        Spacer()
                        Text(ble.telemetry.isSpeedLimit ? "25 km/h 限速" : "极速模式")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(.cyan)
                    }
                    .padding(10)
                    .background(Color(red: 0.10, green: 0.13, blue: 0.19))
                    .cornerRadius(10)
                }
                .padding(18)
                .background(Color(red: 0.07, green: 0.09, blue: 0.14))
                .cornerRadius(16)

                // 驾驶座舱主控枢纽 (START环 + 深空暗核磁贴)
                VStack(spacing: 16) {
                    HStack {
                        Text("驾驶主控枢纽")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(Color(white: 0.5))
                        Spacer()
                    }

                    // START 能量启停环
                    Button(action: {
                        let haptic = UIImpactFeedbackGenerator(style: .heavy)
                        haptic.impactOccurred()
                        if ble.telemetry.isPowerOn {
                            ble.sendCommand(cmd: ScooterProtocol.cmdPowerOff)
                        } else {
                            ble.sendCommand(cmd: ScooterProtocol.cmdPowerOn)
                        }
                    }) {
                        ZStack {
                            Circle()
                                .stroke(ble.telemetry.isPowerOn ? Color.cyan : Color.orange, lineWidth: 5)
                                .frame(width: 92, height: 92)
                            Circle()
                                .fill(Color(red: 0.09, green: 0.12, blue: 0.18))
                                .frame(width: 82, height: 82)
                            VStack(spacing: 2) {
                                Text(ble.telemetry.isPowerOn ? "STOP" : "START")
                                    .font(.system(size: 16, weight: .bold))
                                    .foregroundColor(.white)
                                Text(ble.telemetry.isPowerOn ? "点击断电" : "点击上电")
                                    .font(.system(size: 10))
                                    .foregroundColor(Color(white: 0.5))
                            }
                        }
                    }

                    // 方案 A：三颗深空暗核磁贴
                    HStack(spacing: 8) {
                        tileButton(
                            title: ble.telemetry.isLocked ? "撤防解锁" : "车辆设防",
                            sub: ble.telemetry.isLocked ? "已设防 · 防盗中" : "未设防 · 待上锁",
                            systemIcon: ble.telemetry.isLocked ? "lock.fill" : "lock.open.fill",
                            accentColor: ble.telemetry.isLocked ? .cyan : .orange
                        ) {
                            let cmd = ble.telemetry.isLocked ? ScooterProtocol.cmdUnlock : ScooterProtocol.cmdLock
                            ble.sendCommand(cmd: cmd)
                        }

                        tileButton(
                            title: "动力极速",
                            sub: ble.telemetry.isSpeedLimit ? "25km/h 限速" : "极速全开 · 狂暴",
                            systemIcon: "bolt.fill",
                            accentColor: ble.telemetry.isSpeedLimit ? .orange : .cyan
                        ) {
                            let cmd = ble.telemetry.isSpeedLimit ? ScooterProtocol.cmdSpeedLimitOff : ScooterProtocol.cmdSpeedLimitOn
                            ble.sendCommand(cmd: cmd)
                        }

                        tileButton(
                            title: "寻车鸣笛",
                            sub: "声光寻车",
                            systemIcon: "speaker.wave.3.fill",
                            accentColor: Color(white: 0.6)
                        ) {
                            ble.sendCommand(cmd: ScooterProtocol.cmdMute)
                        }
                    }
                }
                .padding(16)
                .background(Color(red: 0.07, green: 0.09, blue: 0.14))
                .cornerRadius(16)

                // 动力电池系统标称规格 (机甲分段胶囊)
                VStack(alignment: .leading, spacing: 6) {
                    Text("动力电池系统标称规格")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(Color(white: 0.5))
                    Text("当前车辆出厂标配为 60V 加长锂电系统")
                        .font(.system(size: 11))
                        .foregroundColor(Color(white: 0.4))

                    HStack(spacing: 4) {
                        segTab(volt: 48, label: "48V 锂电")
                        segTab(volt: 60, label: "60V 标配")
                        segTab(volt: 72, label: "72V 锂电")
                    }
                    .padding(3)
                    .background(Color(red: 0.05, green: 0.07, blue: 0.11))
                    .cornerRadius(10)
                }
                .padding(16)
                .background(Color(red: 0.07, green: 0.09, blue: 0.14))
                .cornerRadius(16)
            }
            .padding(16)
        }
        .background(Color(red: 0.04, green: 0.05, blue: 0.08).ignoresSafeArea())
        .alert("校准总行驶里程", isPresented: $showOdometerAlert) {
            TextField("输入总里程 (km)", text: $odoInputText)
                .keyboardType(.decimalPad)
            Button("保存") {
                if let val = Float(odoInputText), val >= 0 {
                    gps.calibrateTotal(to: val)
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("设置初始总里程基准数值")
        }
    }

    private func metricBox(title: String, val: String, color: Color) -> some View {
        VStack(spacing: 4) {
            Text(title)
                .font(.system(size: 10))
                .foregroundColor(Color(white: 0.5))
                .lineLimit(1)
            Text(val)
                .font(.system(size: 15, weight: .bold))
                .foregroundColor(color)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .background(Color(red: 0.10, green: 0.13, blue: 0.19))
        .cornerRadius(8)
    }

    private func tileButton(title: String, sub: String, systemIcon: String, accentColor: Color, action: @escaping () -> Void) -> some View {
        Button(action: {
            let h = UIImpactFeedbackGenerator(style: .medium)
            h.impactOccurred()
            action()
        }) {
            VStack(spacing: 6) {
                Image(systemName: systemIcon)
                    .font(.system(size: 20, weight: .bold))
                    .foregroundColor(accentColor)
                Text(title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.white)
                Text(sub)
                    .font(.system(size: 9))
                    .foregroundColor(accentColor)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(Color(red: 0.08, green: 0.11, blue: 0.16))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(accentColor.opacity(0.35), lineWidth: 1))
            .cornerRadius(12)
        }
    }

    private func segTab(volt: Int, label: String) -> some View {
        let isSel = (selectedVolt == volt)
        return Button(action: {
            selectedVolt = volt
            let cmd: UInt8 = (volt == 48) ? ScooterProtocol.cmdVolt48 : ((volt == 72) ? ScooterProtocol.cmdVolt72 : ScooterProtocol.cmdVolt60)
            ble.sendCommand(cmd: cmd)
        }) {
            Text(label)
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(isSel ? .cyan : Color(white: 0.6))
                .frame(maxWidth: .infinity)
                .frame(height: 38)
                .background(isSel ? Color(red: 0.09, green: 0.16, blue: 0.24) : Color.clear)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(isSel ? Color.cyan : Color.clear, lineWidth: 1.5))
                .cornerRadius(8)
        }
    }
}
