require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "BgGeolocation"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => min_ios_version_supported }
  s.source       = { :git => "https://github.com/mukadas026/react-native-bg-geolocation.git", :tag => "#{s.version}" }

  s.source_files = "ios/*.{h,m,mm}", "ios/Runtime/**/*.swift", "ios/Core/Sources/**/*.swift"
  s.private_header_files = "ios/*.h"
  s.swift_version = "5.0"
  s.libraries = "sqlite3"
  s.frameworks = "CoreLocation", "Network"
  s.pod_target_xcconfig = { "DEFINES_MODULE" => "YES" }

  install_modules_dependencies(s)
end
