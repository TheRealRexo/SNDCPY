// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SNDCPYClient",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "SNDCPYClient", targets: ["SNDCPYClient"]),
    ],
    targets: [
        .target(
            name: "SNDCPYClient",
            path: "Sources/com/sndcpy/client"
        ),
        
    ]
)
