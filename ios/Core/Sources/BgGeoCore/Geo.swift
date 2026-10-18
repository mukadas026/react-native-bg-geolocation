import Foundation

private let earthRadiusMeters = 6371008.8

func distanceMeters(_ a: Fix, _ b: Fix) -> Double {
  let rad = Double.pi / 180
  let dLat = (b.lat - a.lat) * rad
  let dLon = (b.lon - a.lon) * rad
  let h = pow(sin(dLat / 2), 2) + cos(a.lat * rad) * cos(b.lat * rad) * pow(sin(dLon / 2), 2)
  return 2 * earthRadiusMeters * asin(sqrt(h))
}
