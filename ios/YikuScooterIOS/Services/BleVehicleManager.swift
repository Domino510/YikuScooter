import Foundation
import CoreBluetooth
import UserNotifications

public class BleVehicleManager: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    public static let shared = BleVehicleManager()

    @Published public var isConnected = false
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

    override init() {
        super.init()
        let savedLevel = UserDefaults.standard.integer(forKey: "key_distance_level")
        self.distanceLevel = (savedLevel >= 1 && savedLevel <= 7) ? savedLevel : 1
        if UserDefaults.standard.object(forKey: "key_proximity_unlock") != nil {
            self.proximityUnlockEnabled = UserDefaults.standard.bool(forKey: "key_proximity_unlock")
        }

        // 不传入 CBCentralManagerOptionRestoreIdentifierKey，避免未实现 willRestoreState 或个人免费签名权限裁剪导致的启动闪退
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
            startScan()
        } else {
            isConnected = false
        }
    }

    public func startScan() {
        guard centralManager.state == .poweredOn else { return }
        centralManager.scanForPeripherals(withServices: nil, options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: true
        ])
    }

    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        let name = peripheral.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? ""
        if name == ScooterProtocol.deviceName || peripheral.identifier.uuidString == ScooterProtocol.deviceMAC {
            let rssiVal = RSSI.intValue
            if rssiVal < 0 && rssiVal > -110 {
                self.rssi = rssiVal
            }
            centralManager.stopScan()
            self.peripheral = peripheral
            peripheral.delegate = self
            centralManager.connect(peripheral, options: nil)
        }
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        isConnected = true
        peripheral.discoverServices([ScooterProtocol.serviceUUID])
        startHeartbeat()
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        isConnected = false
        writeChar = nil
        stopHeartbeat()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.startScan()
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
        for c in chars {
            if c.uuid == ScooterProtocol.writeUUID {
                self.writeChar = c
                checkAndTriggerProximityUnlock()
            } else if c.uuid == ScooterProtocol.notifyUUID {
                peripheral.setNotifyValue(true, for: c)
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
        let packet = ScooterProtocol.buildPacket(seq: seqCode, cmd: cmd, arg1: arg1, arg2: arg2)
        seqCode = (seqCode + 1) & 0xFFFF
        if seqCode == 0 { seqCode = 1 }
        p.writeValue(packet, for: w, type: .withResponse)
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
