import Foundation
import CoreBluetooth

public struct ScooterProtocol {
    public static let serviceUUID = CBUUID(string: "86912001-B5A3-F393-E0A9-E50E24DCCA9E")
    public static let writeUUID   = CBUUID(string: "86912002-B5A3-F393-E0A9-E50E24DCCA9E")
    public static let notifyUUID  = CBUUID(string: "86912003-B5A3-F393-E0A9-E50E24DCCA9E")
    public static let deviceName  = "smart_ebike"
    public static let deviceMAC   = "65:FF:00:0C:25:64"

    public static let cmdLock: UInt8           = 1
    public static let cmdUnlock: UInt8         = 2
    public static let cmdPowerOn: UInt8        = 3
    public static let cmdPowerOff: UInt8       = 4
    public static let cmdMute: UInt8           = 6
    public static let cmdSpeedLimitOff: UInt8  = 8
    public static let cmdSpeedLimitOn: UInt8   = 9

    public static let cmdGanyingOn: UInt8      = 32
    public static let cmdGanyingOff: UInt8     = 33
    public static let cmdChaosuOn: UInt8       = 34
    public static let cmdChaosuOff: UInt8      = 35
    public static let cmdAutoLockOn: UInt8     = 38
    public static let cmdAutoLockOff: UInt8    = 39
    public static let cmdHeartbeat: UInt8      = 85

    public static let cmdVolt48: UInt8         = 49
    public static let cmdVolt60: UInt8         = 50
    public static let cmdVolt72: UInt8         = 52

    public static let cmdEcuConfig: UInt8      = 255
    public static let cmdEcuCurrent: UInt8     = 254

    public static let defaultR: [UInt8] = [0x1D, 0xAC, 0x58, 0xCB]
    public static let defaultM: [UInt8] = [0xB3, 0xCE, 0xA1, 0x39]

    public static func calcC(seq: Int) -> UInt8 {
        var r = (16 + (seq & 0xFF)) & 0xFF
        r = (255 & (17 ^ r)) + 25 & 0xFF
        r = 255 & (36 ^ r)
        r = (r + ((seq >> 8) & 0xFF)) & 0xFF
        r = 255 & (107 ^ r)
        r = (r + 59) & 0xFF
        r = 255 & (58 ^ r)
        return UInt8(r & 127)
    }

    public static func buildPacket(seq: Int, rKey: [UInt8]? = nil, mKey: [UInt8]? = nil, cmd: UInt8, arg1: UInt8 = 0, arg2: UInt8 = 0) -> Data {
        let r = rKey ?? defaultR
        let m = mKey ?? defaultM
        let c = Int(calcC(seq: seq))
        var buf = [UInt8](repeating: 0, count: 15)

        buf[0] = 0x88
        buf[1] = UInt8(seq & 0xFF)
        buf[2] = UInt8((seq >> 8) & 0xFF)

        for i in 0..<4 {
            buf[3 + i] = UInt8((Int(r[i]) + c) & 0xFF)
            buf[7 + i] = UInt8((Int(m[i]) + c) & 0xFF)
        }

        buf[11] = UInt8((Int(cmd) + c) & 0xFF)
        buf[12] = UInt8((Int(arg1) + c) & 0xFF)
        buf[13] = UInt8((Int(arg2) + c) & 0xFF)

        var sum: Int = 0
        for i in 0..<14 { sum += Int(buf[i]) }
        buf[14] = UInt8(sum & 0xFF)

        return Data(buf)
    }

    public struct TelemetryData {
        public var valid = false
        public var state: Int = 0
        public var isPowerOn = false
        public var isLocked = false
        public var isPShow = false
        public var isSpeedLimit = false
        public var isAutoLock = false
        public var isOverspeedTips = false
        public var isProximityOn = false
        public var errorCode: Int = 0
        public var errorMsg = "系统正常"
        public var gear: Int = 1
        public var sensitivity: Int = 2
        public var distanceLevel: Int = 4
        public var totalMileageKm: Int = 0
        public var onceMileageKm: Float = 0.0
        public var speedKmh: Int = 0
        public var batteryPercent: Int = 0
        public var voltage: Float = 0.0
        public var sysVoltage: Int = 60

        public static func parse(data: Data) -> TelemetryData? {
            guard data.count >= 23, data[0] == 0x66 else { return nil }
            var sum: Int = 0
            for i in 0..<(data.count - 1) { sum += Int(data[i]) }
            guard UInt8(sum & 0xFF) == data[data.count - 1] else { return nil }

            var t = TelemetryData()
            t.valid = true
            t.state = Int(data[9])
            t.isPowerOn = (t.state == 3)
            t.isLocked = (t.state == 1 || t.state == 5)

            let flags = Int(data[10])
            t.isProximityOn = (flags & 1) == 1
            t.isPShow = (flags & 2) == 2
            t.isSpeedLimit = (flags & 4) == 4
            t.isAutoLock = (flags & 16) == 16
            t.isOverspeedTips = (flags & 32) == 32

            t.errorCode = Int(data[11])
            t.gear = Int(data[12] & 7)
            t.sensitivity = max(1, min(4, Int((data[12] >> 4) & 7)))

            t.totalMileageKm = (Int(data[13]) << 16) | (Int(data[14]) << 8) | Int(data[15])
            t.onceMileageKm = Float(data[16]) + 0.1 * Float(data[17])
            t.speedKmh = Int(data[18])
            t.batteryPercent = Int(data[19])
            t.voltage = Float(data[20]) + 0.1 * Float(data[21])

            if data.count >= 24 {
                let mask = Int(data[22])
                switch mask {
                case 1: t.sysVoltage = 36
                case 2: t.sysVoltage = 48
                case 4: t.sysVoltage = 60
                case 8: t.sysVoltage = 64
                case 16: t.sysVoltage = 72
                case 32: t.sysVoltage = 80
                case 64: t.sysVoltage = 84
                case 128: t.sysVoltage = 96
                default: t.sysVoltage = 60
                }
                t.distanceLevel = max(1, min(7, Int(data[23] & 0x0F)))
            }
            return t
        }
    }
}
