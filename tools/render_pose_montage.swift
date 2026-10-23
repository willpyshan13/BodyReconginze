import Foundation
import AVFoundation
import CoreGraphics
import ImageIO

struct Segment: Decodable { let action: String; let row: Int; let column: Int; let frames: Int }
struct Plan: Decodable { let fps: Int; let width: Int; let height: Int; let segments: [Segment] }
let args = CommandLine.arguments
 guard args.count == 4 else { fatalError("Usage: render_pose_montage sheet.png timeline.json output.mp4") }
let plan = try JSONDecoder().decode(Plan.self, from: Data(contentsOf: URL(fileURLWithPath: args[2])))
let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: args[1]) as CFURL, nil)!
let sheet = CGImageSourceCreateImageAtIndex(source, 0, nil)!
let columns = [(0, 336), (344, 680), (689, 1024)]
let rows = [(0, 300), (308, 621), (629, 941), (949, 1188), (1196, 1536)]
let poses = rows.map { y in columns.map { x in sheet.cropping(to: CGRect(x: x.0, y: y.0, width: x.1-x.0, height: y.1-y.0))! } }
let url = URL(fileURLWithPath: args[3])
if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264,
    AVVideoWidthKey: plan.width, AVVideoHeightKey: plan.height,
    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 2_500_000, AVVideoMaxKeyFrameIntervalKey: plan.fps]
])
input.expectsMediaDataInRealTime = false
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
    kCVPixelBufferWidthKey as String: plan.width, kCVPixelBufferHeightKey as String: plan.height,
    kCVPixelBufferCGImageCompatibilityKey as String: true,
    kCVPixelBufferCGBitmapContextCompatibilityKey as String: true
])
writer.add(input)
guard writer.startWriting() else { throw writer.error! }
writer.startSession(atSourceTime: .zero)
var frameIndex = 0
for segment in plan.segments {
    let image = poses[segment.row][segment.column]
    for _ in 0..<segment.frames {
        while !input.isReadyForMoreMediaData {
            if writer.status == .failed { throw writer.error! }
            Thread.sleep(forTimeInterval: 0.002)
        }
        var optional: CVPixelBuffer?
        guard CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &optional) == kCVReturnSuccess,
              let buffer = optional else { fatalError("Pixel buffer allocation failed") }
        CVPixelBufferLockBaseAddress(buffer, [])
        let context = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: plan.width, height: plan.height,
            bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue)!
        context.setFillColor(CGColor(red: 0.04, green: 0.05, blue: 0.07, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: plan.width, height: plan.height))
        let scale = min(Double(plan.width) / Double(image.width), Double(plan.height) / Double(image.height))
        let w = Double(image.width) * scale, h = Double(image.height) * scale
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: (Double(plan.width)-w)/2, y: (Double(plan.height)-h)/2, width: w, height: h))
        CVPixelBufferUnlockBaseAddress(buffer, [])
        guard adaptor.append(buffer, withPresentationTime: CMTime(value: Int64(frameIndex), timescale: Int32(plan.fps))) else {
            throw writer.error ?? NSError(domain: "Montage", code: 1)
        }
        frameIndex += 1
    }
}
writer.endSession(atSourceTime: CMTime(value: Int64(frameIndex), timescale: Int32(plan.fps)))
input.markAsFinished()
let done = DispatchSemaphore(value: 0)
writer.finishWriting { done.signal() }
done.wait()
guard writer.status == .completed else { throw writer.error! }
print("Wrote \(frameIndex) frames to \(url.path)")
