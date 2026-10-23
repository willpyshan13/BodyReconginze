// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "BodyRecongnize",
    platforms: [.iOS(.v15)],
    products: [.library(name: "BodyRecongnize", targets: ["BodyRecongnize"])],
    targets: [
        .target(name: "BodyRecongnizeCore", path: ".", sources: ["src/body_recongnize.cpp"], publicHeadersPath: "include", cxxSettings: [.unsafeFlags(["-std=c++17"]) ]),
        .target(name: "BodyRecongnize", dependencies: ["BodyRecongnizeCore"], path: "ios/Sources/BodyRecongnize")
    ],
    cxxLanguageStandard: .cxx17
)
