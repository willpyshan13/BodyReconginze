#ifndef BODY_RECONGNIZE_H
#define BODY_RECONGNIZE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define BR_LANDMARK_COUNT 33
#define BR_API_VERSION 1

/* MediaPipe Pose landmark indices. Coordinates are normalized image coordinates;
 * x grows right and y grows down. Mirror camera input consistently before use. */
typedef enum {
  BR_LEFT_SHOULDER = 11, BR_RIGHT_SHOULDER = 12,
  BR_LEFT_ELBOW = 13, BR_RIGHT_ELBOW = 14,
  BR_LEFT_WRIST = 15, BR_RIGHT_WRIST = 16,
  BR_LEFT_HIP = 23, BR_RIGHT_HIP = 24,
  BR_LEFT_KNEE = 25, BR_RIGHT_KNEE = 26,
  BR_LEFT_ANKLE = 27, BR_RIGHT_ANKLE = 28
} BRLandmarkIndex;

typedef struct {
  float x;
  float y;
  float confidence; /* [0, 1], combining detection and visibility confidence */
} BRLandmark;

typedef struct {
  BRLandmark landmarks[BR_LANDMARK_COUNT];
  int64_t timestamp_ms; /* monotonic capture timestamp */
  uint32_t image_width; /* pixel dimensions used to correct 2D angles */
  uint32_t image_height;
} BRFrame;

typedef enum {
  BR_SQUAT = 1,
  BR_ARM_RAISE = 2,
  BR_BICEP_CURL = 3,
  BR_PUSH_UP = 4,
  BR_LATERAL_RAISE = 5
} BRExercise;
typedef enum { BR_UNKNOWN = 0, BR_UP = 1, BR_DOWN = 2 } BRPhase;
typedef enum {
  BR_OK = 0, BR_INVALID_ARGUMENT = 1, BR_INSUFFICIENT_LANDMARKS = 2,
  BR_NON_MONOTONIC_TIME = 3
} BRStatus;

typedef struct {
  BRExercise exercise;
  BRPhase phase;
  uint32_t repetitions;
  float progress; /* [0, 1]: current movement depth/height */
  float live_score; /* [0, 100]: provisional score for the active repetition */
  float last_rep_score; /* [0, 100], 0 until a repetition finishes */
  float left_angle_deg; /* exercise-specific knee, elbow, or shoulder angle */
  float right_angle_deg;
} BRResult;

typedef struct BRSession BRSession;

/* One session is used from one thread. Create separate sessions for separate people. */
BRSession *br_create(BRExercise exercise);
void br_destroy(BRSession *session);
void br_reset(BRSession *session);
BRStatus br_process(BRSession *session, const BRFrame *frame, BRResult *result);
int br_api_version(void);

#ifdef __cplusplus
}
#endif
#endif
