import Foundation
import CoreLocation

public class LocationSpeedEngine: NSObject, ObservableObject, CLLocationManagerDelegate {
    public static let shared = LocationSpeedEngine()

    @Published public var speedKmh: Int = 0
    @Published public var tripDistanceKm: Float = 0.0
    @Published public var totalDistanceKm: Float = 0.3

    private let locationManager = CLLocationManager()
    private var lastLocation: CLLocation?
    private let prefsKey = "key_total_mileage_km"

    override init() {
        super.init()
        let savedTotal = UserDefaults.standard.float(forKey: prefsKey)
        self.totalDistanceKm = (savedTotal > 0) ? savedTotal : 0.3

        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        locationManager.distanceFilter = 1.0
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.requestWhenInUseAuthorization()
        locationManager.startUpdatingLocation()
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last, loc.horizontalAccuracy > 0, loc.horizontalAccuracy < 30 else { return }

        let s = max(0, loc.speed) * 3.6
        DispatchQueue.main.async {
            self.speedKmh = Int(round(s))
        }

        if let last = lastLocation, loc.speed > 0 {
            let deltaM = loc.distance(from: last)
            if deltaM > 1.0 && deltaM < 120.0 {
                let deltaKm = Float(deltaM / 1000.0)
                DispatchQueue.main.async {
                    self.tripDistanceKm += deltaKm
                    self.totalDistanceKm += deltaKm
                    UserDefaults.standard.set(self.totalDistanceKm, forKey: self.prefsKey)
                }
            }
        }
        lastLocation = loc
    }

    public func calibrateTotal(to value: Float) {
        DispatchQueue.main.async {
            self.totalDistanceKm = value
            UserDefaults.standard.set(value, forKey: self.prefsKey)
        }
    }
}
