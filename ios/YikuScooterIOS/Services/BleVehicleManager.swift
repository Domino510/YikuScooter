import Foundation
import CoreBluetooth

public class BleVehicleManager: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    public static let shared = BleVehicleManager()

    @Published public var isConnected = false
    @Published public var rssi: Int = -100
    @Published public var telemetry = ScooterProtocol.TelemetryData()

    private var centralManager: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var writeChar: CBCharacteristic?
    private var seqCode = 1
    private var heartbeatTimer: Timer?

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil, options: [
            CBCentralManagerOptionRestoreIdentifierKey: "com.onecool.scooter.ble"
        ])
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
        centralManager.scanForPeripherals(withServices: [ScooterProtocol.serviceUUID], options: [
            CBCentralManagerScanOptionAllowDuplicatesKey: true
        ])
    }

    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        self.rssi = RSSI.intValue
        if peripheral.name == ScooterProtocol.deviceName || peripheral.identifier.uuidString == ScooterProtocol.deviceMAC {
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
        startScan()
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
            }
        }
    }

    public func sendCommand(cmd: UInt8, arg1: UInt8 = 0, arg2: UInt8 = 0) {
        guard let p = peripheral, let w = writeChar else { return }
        let packet = ScooterProtocol.buildPacket(seq: seqCode, cmd: cmd, arg1: arg1, arg2: arg2)
        seqCode += 1
        p.writeValue(packet, for: w, type: .withResponse)
    }

    private func startHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.sendCommand(cmd: ScooterProtocol.cmdHeartbeat, arg1: 0xAA, arg2: 0x5A)
            self?.peripheral?.readRSSI()
        }
    }

    private func stopHeartbeat() {
        heartbeatTimer?.invalidate()
        heartbeatTimer = nil
    }

    public func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
        DispatchQueue.main.async {
            self.rssi = RSSI.intValue
        }
    }
}
