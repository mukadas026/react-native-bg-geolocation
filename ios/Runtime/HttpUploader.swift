import Foundation

final class HttpUploader {
  private let session: URLSession

  init() {
    let configuration = URLSessionConfiguration.default
    configuration.timeoutIntervalForRequest = 30
    configuration.waitsForConnectivity = false
    session = URLSession(configuration: configuration)
  }

  func post(
    url: String,
    headers: [String: String],
    fixes: [Fix],
    completion: @escaping (Int) -> Void
  ) {
    guard let endpoint = URL(string: url) else {
      completion(0)
      return
    }
    var request = URLRequest(url: endpoint)
    request.httpMethod = "POST"
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    headers.forEach { request.setValue($1, forHTTPHeaderField: $0) }
    request.httpBody = Self.payload(fixes)

    session.dataTask(with: request) { _, response, error in
      let status = error == nil ? (response as? HTTPURLResponse)?.statusCode ?? 0 : 0
      DispatchQueue.main.async { completion(status) }
    }.resume()
  }

  static func payload(_ fixes: [Fix]) -> Data? {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]

    let locations: [[String: Any]] = fixes.map { fix in
      var coords: [String: Any] = ["latitude": fix.lat, "longitude": fix.lon]
      if let v = fix.accuracy { coords["accuracy"] = v }
      if let v = fix.speed { coords["speed"] = v }
      if let v = fix.bearing { coords["heading"] = v }
      if let v = fix.altitude { coords["altitude"] = v }
      return [
        "uuid": fix.id,
        "timestamp": formatter.string(from: Date(timeIntervalSince1970: Double(fix.timestamp) / 1000)),
        "coords": coords,
      ]
    }
    return try? JSONSerialization.data(withJSONObject: ["location": locations])
  }
}
