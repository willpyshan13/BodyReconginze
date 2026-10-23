import BodyRecongnizeCore

public enum Exercise: UInt32 {
    case squat = 1
    case armRaise = 2
    case bicepCurl = 3
    case pushUp = 4
    case lateralRaise = 5
}
public enum Phase: UInt32 { case unknown = 0, up = 1, down = 2 }

public struct BodyResult {
    public let phase: Phase
    public let repetitions: UInt32
    public let progress: Float
    public let liveScore: Float
    public let lastRepScore: Float
    public let leftAngleDeg: Float
    public let rightAngleDeg: Float
}

public enum BodyError: Error {
    case invalidArgument, insufficientLandmarks, nonMonotonicTime
}

/// Each frame contains 33 x/y/confidence triplets in MediaPipe Pose order.
public final class BodySession {
    private var handle: OpaquePointer?

    public init(exercise: Exercise) {
        handle = br_create(BRExercise(rawValue: exercise.rawValue))
    }

    deinit { br_destroy(handle) }

    public func reset() { br_reset(handle) }

    public func process(landmarks: [Float], imageWidth: UInt32,
                        imageHeight: UInt32, timestampMs: Int64) throws -> BodyResult {
        guard landmarks.count == 99, imageWidth > 0, imageHeight > 0 else {
            throw BodyError.invalidArgument
        }
        var frame = BRFrame()
        frame.timestamp_ms = timestampMs
        frame.image_width = imageWidth
        frame.image_height = imageHeight
        withUnsafeMutableBytes(of: &frame.landmarks) { buffer in
            let points = buffer.bindMemory(to: BRLandmark.self)
            for index in 0..<33 {
                points[index] = BRLandmark(x: landmarks[index * 3],
                                           y: landmarks[index * 3 + 1],
                                           confidence: landmarks[index * 3 + 2])
            }
        }
        var result = BRResult()
        switch br_process(handle, &frame, &result) {
        case BR_OK:
            return BodyResult(phase: Phase(rawValue: result.phase.rawValue) ?? .unknown,
                              repetitions: result.repetitions,
                              progress: result.progress,
                              liveScore: result.live_score,
                              lastRepScore: result.last_rep_score,
                              leftAngleDeg: result.left_angle_deg,
                              rightAngleDeg: result.right_angle_deg)
        case BR_INSUFFICIENT_LANDMARKS: throw BodyError.insufficientLandmarks
        case BR_NON_MONOTONIC_TIME: throw BodyError.nonMonotonicTime
        default: throw BodyError.invalidArgument
        }
    }
}
