import Foundation
import CoreBluetooth
import UserNotifications

public class BleVehicleManager: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    public static let shared = BleVehicleManager()

    @Published public var isConnected = false
    @Published public var statusText = "等待连接"
    @Published public var rssi: Int = -100
    @Published public var telemetry = ScooterProtocol.TelemetryData()
    @Published public var proximityUnlockEnabled: Bool = true
    @Published public var distanceLevel: Int = 1

    private var centralManager: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var writeChar: CBCharacteristic?
    private var seqCode = 1
    private var heartbeatTimer: Timer?
    private var lastProximityTriggerTime: TimeInterval = 0
    private var lastAlarmNotifyTime: TimeInterval = 0

    // 动态密钥 R 与 M（自动从 13 字节广播包同步，兼容已配对克隆与新配对模式）
    private var currentR: [UInt8] = ScooterProtocol.defaultR
    private var currentM: [UInt8] = ScooterProtocol.defaultM
    private var isVehicleInPairingMode: Bool = false
    private var consecutiveQuickFailures: Int = 0
    private var connectTimestamp: TimeInterval = 0

    override init() {
        super.init()
        let savedLevel = UserDefaults.standard.integer(forKey: "key_distance_level")
        self.distanceLevel = (savedLevel >= 1 && savedLevel <= 7) ? savedLevel : 1
        if UserDefaults.standard.object(forKey: "key_proximity_unlock") != nil {
            self.proximityUnlockEnabled = UserDefaults.standard.bool(forKey: "key_proximity_unlock")
        }

        centralManager = CBCentralManager(delegate: self, queue: .main)
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    public func setProximityUnlock(_ enabled: Bool) {
        self.proximityUnlockEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: "key_proximity_unlock")
    }

    public func setDistanceLevel(_ level: Int) {
        let clamped = max(1, min(7, level))
        self.distanceLevel = clamped
        UserDefaults.standard.set(clamped, forKey: "key_distance_level")
    }

    public static func rssiThreshold(for level: Int) -> Int {
        switch level {
        case 1: return -50 // 贴身 0.8m
        case 2: return -54 // 近距 1.5m
        case 3: return -58 // 2.5m
        case 4: return -62 // 适中 3.5m
        case 5: return -66 // 5.0m
        case 6: return -70 // 6.5m
        case 7: return -74 // 远距 8.5m
        default: return -54
        }
    }

    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            startScan(manual: false)
        } else {
            isConnected = false
            statusText = "蓝牙未开启"
        }
    }

    public func startScan(manual: Bool = true) {
        if manual {
            consecutiveQuickFailures = 0
        }
        guard centralManager.state == .poweredOn else { return }
        statusText = "正在搜索车机..."

        // 1. 优先检查系统已连接/已配对的同服务外设，避免重复广播触发配对弹窗
        let connectedPeripherals = centralManager.retrieveConnectedPeripherals(withServices: [ScooterProtocol.serviceUUID])
        if let existing = connectedPeripherals.first {
            self.peripheral = existing
            existing.delegate = self
            connectTimestamp = Date().timeIntervalSince1970
            centralManager.connect(existing, options: nil)
            return
        }

        centralManager.scanForPeripherals(withServices: nil, options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: false
        ])
    }

    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        let name = peripheral.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? ""

        // 解析 13 字节厂商广播包 (RLA2 + 状态字节 + 4字节R + 4字节M)
        var matchedByMfg = false
        if let mfgData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data, mfgData.count >= 13 {
            let bytes = [UInt8](mfgData)
            // 检查是否包含 "RLA2" (0x52, 0x4C, 0x41, 0x32) 或 "RL01"
            if bytes[0] == 0x52 && bytes[1] == 0x4C {
                matchedByMfg = true
                isVehicleInPairingMode = (bytes[4] == 0x55)
                currentR = Array(bytes[5..<9])
                let advM = Array(bytes[9..<13])
                // 若广播包中携带有有效的非零 M 密钥，直接同步克隆使用
                let isAdvMValid = advM.contains(where: { $0 != 0x00 && $0 != 0xFF })
                if !isVehicleInPairingMode && isAdvMValid {
                    currentM = advM
                }
            } else if mfgData.count >= 15 {
                // 兼容前2字节为 Company ID 的情况
                if bytes[2] == 0x52 && bytes[3] == 0x4C {
                    matchedByMfg = true
                    isVehicleInPairingMode = (bytes[6] == 0x55)
                    currentR = Array(bytes[7..<11])
                    let advM = Array(bytes[11..<15])
                    let isAdvMValid = advM.contains(where: { $0 != 0x00 && $0 != 0xFF })
                    if !isVehicleInPairingMode && isAdvMValid {
                        currentM = advM
                    }
                }
            }
        }

        if name == ScooterProtocol.deviceName || matchedByMfg {
            let rssiVal = RSSI.intValue
            if rssiVal < 0 && rssiVal > -110 {
                self.rssi = rssiVal
            }
            centralManager.stopScan()
            self.peripheral = peripheral
            peripheral.delegate = self
            statusText = "正在连接握手..."
            connectTimestamp = Date().timeIntervalSince1970
            centralManager.connect(peripheral, options: nil)
        }
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        statusText = "正在同步特征..."
        // 严格遵循官方小程序的 600ms 延迟后再发现服务，给 iOS 系统蓝牙 SMP 配对留出稳态时间
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
            guard let self = self, self.peripheral == peripheral else { return }
            peripheral.discoverServices([ScooterProtocol.serviceUUID])
        }
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        isConnected = false
        writeChar = nil
        stopHeartbeat()
        statusText = "连接被拒 (点击重试)"
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let connectionDuration = Date().timeIntervalSince1970 - connectTimestamp
        let wasReady = (writeChar != nil && isConnected)

        isConnected = false
        writeChar = nil
        stopHeartbeat()

        if !wasReady && connectionDuration < 5.0 {
            consecutiveQuickFailures += 1
        } else {
            consecutiveQuickFailures = 0
        }

        // 如果连续快速断开超过 2 次，说明车机处于非配对拒连状态或系统配对未通过，停止无限重连弹窗轰炸！
        if consecutiveQuickFailures >= 2 {
            statusText = "配对受阻 (点此重连)"
            return
        }

        statusText = "已断开，重连中..."
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in
            self?.startScan(manual: false)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for s in services where s.uuid == ScooterProtocol.serviceUUID {
            peripheral.discoverCharacteristics([ScooterProtocol.writeUUID, ScooterProtocol.notifyUUID], for: s)
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let chars = service.characteristics else { return }
        var foundNotify: CBCharacteristic?

        for c in chars {
            if c.uuid == ScooterProtocol.writeUUID {
                self.writeChar = c
            } else if c.uuid == ScooterProtocol.notifyUUID {
                foundNotify = c
            }
        }

        if self.writeChar != nil {
            self.isConnected = true
            self.consecutiveQuickFailures = 0
            self.statusText = "已在线"

            // 第一时间立即发送首帧认证心跳包，喂饱车机看门狗并完成 R/M 密钥握手！
            self.sendCommand(cmd: ScooterProtocol.cmdHeartbeat, arg1: 0xAA, arg2: 0x5A)
            self.startHeartbeat()
            self.checkAndTriggerProximityUnlock()
        }

        if let notifyChar = foundNotify {
            // 延迟 300ms 开启 Notify，避免与首包认证写入争抢通道
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                peripheral.setNotifyValue(true, for: notifyChar)
            }
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == ScooterProtocol.notifyUUID, let data = characteristic.value else { return }
        if let t = ScooterProtocol.TelemetryData.parse(data: data) {
            DispatchQueue.main.async {
                self.telemetry = t
                if t.distanceLevel >= 1 && t.distanceLevel <= 7 {
                    self.distanceLevel = t.distanceLevel
                }
                if t.state == 4 {
                    self.triggerSentryAlarm()
                }
            }
        }
    }

    public func sendCommand(cmd: UInt8, arg1: UInt8 = 0, arg2: UInt8 = 0) {
        guard let p = peripheral, let w = writeChar else { return }
        let packet = ScooterProtocol.buildPacket(
            seq: seqCode,
            rKey: currentR,
            mKey: currentM,
            cmd: cmd,
            arg1: arg1,
            arg2: arg2
        )
        seqCode = (seqCode + 1) & 0xFFFF
        if seqCode == 0 { seqCode = 1 }

        // 自适应特征值写入属性：优先检查是否支持 withoutResponse 或 withResponse
        if w.properties.contains(.writeWithoutResponse) {
            p.writeValue(packet, for: w, type: .withoutResponse)
        } else {
            p.writeValue(packet, for: w, type: .withResponse)
        }
    }

    private func startHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            self.sendCommand(cmd: ScooterProtocol.cmdHeartbeat, arg1: 0xAA, arg2: 0x5A)
            self.peripheral?.readRSSI()
        }
    }

    private func stopHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
    }

    public func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        let val = RSSI.intValue
        if error == nil && val < 0 && val > -110 {
            DispatchQueue.main.async {
                self.rssi = val
                self.checkAndTriggerProximityUnlock()
            }
        }
    }

    private func checkAndTriggerProximityUnlock() {
        guard proximityUnlockEnabled, isConnected, !telemetry.isPowerOn else { return }
        let threshold = Self.rssiThreshold(for: distanceLevel)
        let now = Date().timeIntervalSince1970
        if rssi >= threshold && rssi < 0 && (now - lastProximityTriggerTime > 15.0) {
            lastProximityTriggerTime = now
            sendCommand(cmd: ScooterProtocol.cmdUnlock)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
                self?.sendCommand(cmd: ScooterProtocol.cmdPowerOn)
            }
        }
    }

    private func triggerSentryAlarm() {
        let now = Date().timeIntervalSince1970
        guard now - lastAlarmNotifyTime > 12.0 else { return }
        lastAlarmNotifyTime = now

        let content = UNMutableNotificationContent()
        content.title = "车辆防盗警报触发！"
        content.body = "检测到您的壹酷封神DZ发生异常震动或被触碰，请留意车辆安全。"
        content.sound = .defaultCritical

        let req = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(req, withCompletionHandler: nil)
    }
}
