// swift-tools-version: 5.9
import PackageDescription

let package = Package(
  name: "BgGeoCore",
  platforms: [.iOS(.v15), .macOS(.v12)],
  products: [.library(name: "BgGeoCore", targets: ["BgGeoCore"])],
  targets: [
    .target(name: "BgGeoCore"),
    .testTarget(
      name: "BgGeoCoreTests",
      dependencies: ["BgGeoCore"]
    ),
  ]
)
