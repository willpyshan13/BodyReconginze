#include "body_recongnize.h"

#include <algorithm>
#include <cmath>
#include <new>

namespace {
constexpr float kMinConfidence = 0.5f;
constexpr float kPi = 3.14159265358979323846f;
constexpr int64_t kMinimumTransitionMs = 180;
constexpr int64_t kMaximumTrackingGapMs = 1000;

float clamp(float value, float low, float high) {
  return std::max(low, std::min(high, value));
}

float angle(const BRLandmark &a, const BRLandmark &pivot,
            const BRLandmark &b, float aspect) {
  const float ax = (a.x - pivot.x) * aspect, ay = a.y - pivot.y;
  const float bx = (b.x - pivot.x) * aspect, by = b.y - pivot.y;
  const float length = std::hypot(ax, ay) * std::hypot(bx, by);
  if (length < 1e-6f) return NAN;
  return std::acos(clamp((ax * bx + ay * by) / length, -1.f, 1.f)) *
         180.f / kPi;
}

bool valid(const BRFrame &frame, int index) {
  const auto &p = frame.landmarks[index];
  return std::isfinite(p.x) && std::isfinite(p.y) &&
         std::isfinite(p.confidence) && p.confidence >= kMinConfidence &&
         p.confidence <= 1.f && p.x >= 0.f && p.x <= 1.f &&
         p.y >= 0.f && p.y <= 1.f;
}

bool valid_arm(const BRFrame &frame, int shoulder, int elbow, int wrist) {
  return valid(frame, shoulder) && valid(frame, elbow) && valid(frame, wrist);
}

bool has_landmarks(const BRFrame &frame, BRExercise exercise) {
  const int squat[] = {23, 24, 25, 26, 27, 28};
  const int arm[] = {11, 12, 13, 14, 15, 16};
  const int lateral_raise[] = {11, 12, 13, 14, 23, 24};
  switch (exercise) {
  case BR_SQUAT:
    for (int index : squat) if (!valid(frame, index)) return false;
    return true;
  case BR_ARM_RAISE:
    for (int index : arm) if (!valid(frame, index)) return false;
    return valid(frame, 23) && valid(frame, 24);
  case BR_BICEP_CURL:
    for (int index : arm) if (!valid(frame, index)) return false;
    return true;
  case BR_PUSH_UP:
    return (valid_arm(frame, 11, 13, 15) && valid(frame, 23)) ||
           (valid_arm(frame, 12, 14, 16) && valid(frame, 24));
  case BR_LATERAL_RAISE:
    for (int index : lateral_raise) if (!valid(frame, index)) return false;
    return true;
  default:
    return false;
  }
}

float score(float progress, float symmetry) {
  return clamp(80.f * progress + 20.f * symmetry, 0.f, 100.f);
}
} // namespace

struct BRSession {
  explicit BRSession(BRExercise kind) : exercise(kind) {}
  BRExercise exercise;
  BRPhase phase = BR_UNKNOWN;
  uint32_t repetitions = 0;
  int64_t last_timestamp = -1;
  int64_t last_transition = -1;
  float peak_progress = 0.f;
  float worst_symmetry = 1.f;
  float last_rep_score = 0.f;
};

extern "C" {
BRSession *br_create(BRExercise exercise) {
  if (exercise < BR_SQUAT || exercise > BR_LATERAL_RAISE) return nullptr;
  return new (std::nothrow) BRSession(exercise);
}

void br_destroy(BRSession *session) { delete session; }

void br_reset(BRSession *session) {
  if (session) {
    BRExercise exercise = session->exercise;
    *session = BRSession(exercise);
  }
}

int br_api_version(void) { return BR_API_VERSION; }

BRStatus br_process(BRSession *session, const BRFrame *frame, BRResult *result) {
  if (!session || !frame || !result || frame->timestamp_ms < 0 ||
      frame->image_width == 0 || frame->image_height == 0)
    return BR_INVALID_ARGUMENT;
  if (session->last_timestamp >= frame->timestamp_ms)
    return BR_NON_MONOTONIC_TIME;
  if (!has_landmarks(*frame, session->exercise))
    return BR_INSUFFICIENT_LANDMARKS;

  if (session->last_timestamp >= 0 &&
      frame->timestamp_ms - session->last_timestamp > kMaximumTrackingGapMs) {
    session->phase = BR_UNKNOWN;
    session->last_transition = -1;
    session->peak_progress = 0.f;
    session->worst_symmetry = 1.f;
  }

  const auto &p = frame->landmarks;
  const float aspect = static_cast<float>(frame->image_width) /
                       static_cast<float>(frame->image_height);
  float left, right, progress, symmetry;
  BRPhase observed;
  float targetThreshold;
  BRPhase start;
  if (session->exercise == BR_SQUAT) {
    left = angle(p[23], p[25], p[27], aspect);
    right = angle(p[24], p[26], p[28], aspect);
    // A 90 degree knee bend reaches full depth. Side-view input works best.
    progress = clamp((165.f - (left + right) / 2.f) / 75.f, 0.f, 1.f);
    symmetry = 1.f - clamp(std::fabs(left - right) / 45.f, 0.f, 1.f);
    targetThreshold = 0.70f;
    start = BR_UP;
  } else if (session->exercise == BR_ARM_RAISE) {
    // Shoulder angle between upper arm and torso; 0 is arms down, 180 up.
    left = angle(p[13], p[11], p[23], aspect);
    right = angle(p[14], p[12], p[24], aspect);
    progress = clamp(((left + right) / 2.f - 30.f) / 130.f, 0.f, 1.f);
    symmetry = 1.f - clamp(std::fabs(left - right) / 60.f, 0.f, 1.f);
    targetThreshold = 0.80f;
    start = BR_DOWN;
  } else if (session->exercise == BR_BICEP_CURL) {
    // Elbow flexion: arms extended at the bottom, forearms curled upward.
    left = angle(p[11], p[13], p[15], aspect);
    right = angle(p[12], p[14], p[16], aspect);
    progress = clamp((165.f - (left + right) / 2.f) / 110.f, 0.f, 1.f);
    symmetry = 1.f - clamp(std::fabs(left - right) / 60.f, 0.f, 1.f);
    targetThreshold = 0.80f;
    start = BR_DOWN;
  } else if (session->exercise == BR_PUSH_UP) {
    // Elbow flexion from a straight-arm plank to the lowered position.
    const bool left_visible = valid_arm(*frame, 11, 13, 15) && valid(*frame, 23);
    const bool right_visible = valid_arm(*frame, 12, 14, 16) && valid(*frame, 24);
    left = left_visible ? angle(p[11], p[13], p[15], aspect) : NAN;
    right = right_visible ? angle(p[12], p[14], p[16], aspect) : NAN;
    if (!left_visible) left = right;
    if (!right_visible) right = left;
    if (!std::isfinite(left) || !std::isfinite(right))
      return BR_INSUFFICIENT_LANDMARKS;
    // Elbow flexion alone also occurs in standing curls. Require a visible,
    // mostly horizontal shoulder-to-hip axis before accepting a push-up cycle.
    auto horizontal_torso = [&](int shoulder, int hip) {
      const float dx = std::fabs(p[shoulder].x - p[hip].x) * aspect;
      const float dy = std::fabs(p[shoulder].y - p[hip].y);
      return dx > 1e-3f && dx > dy * 1.2f;
    };
    const bool plausible = (!left_visible || horizontal_torso(11, 23)) &&
                           (!right_visible || horizontal_torso(12, 24));
    if (!plausible) {
      session->phase = BR_UNKNOWN;
      session->last_transition = -1;
      session->peak_progress = 0.f;
      session->worst_symmetry = 1.f;
      session->last_timestamp = frame->timestamp_ms;
      *result = {session->exercise, BR_UNKNOWN, session->repetitions, 0.f,
                 0.f, session->last_rep_score, left, right};
      return BR_OK;
    }
    progress = clamp((165.f - (left + right) / 2.f) / 75.f, 0.f, 1.f);
    symmetry = 1.f - clamp(std::fabs(left - right) / 45.f, 0.f, 1.f);
    targetThreshold = 0.70f;
    start = BR_UP;
  } else {
    // Lateral raise: lift the upper arms from the sides to shoulder height.
    left = angle(p[13], p[11], p[23], aspect);
    right = angle(p[14], p[12], p[24], aspect);
    progress = clamp(((left + right) / 2.f - 20.f) / 80.f, 0.f, 1.f);
    symmetry = 1.f - clamp(std::fabs(left - right) / 45.f, 0.f, 1.f);
    targetThreshold = 0.80f;
    start = BR_DOWN;
  }
  if (!std::isfinite(left) || !std::isfinite(right) ||
      !std::isfinite(progress) || !std::isfinite(symmetry))
    return BR_INSUFFICIENT_LANDMARKS;

  const BRPhase target = start == BR_UP ? BR_DOWN : BR_UP;
  observed = progress >= targetThreshold ? target :
             progress <= 0.20f ? start : BR_UNKNOWN;
  if (session->phase == BR_UNKNOWN) {
    if (observed == start) {
      session->phase = start;
      session->last_transition = frame->timestamp_ms;
    }
  } else if (observed != BR_UNKNOWN && observed != session->phase &&
             frame->timestamp_ms - session->last_transition >= kMinimumTransitionMs) {
    if (session->phase == start && observed == target) {
      session->peak_progress = progress;
      session->worst_symmetry = symmetry;
    } else if (session->phase == target && observed == start) {
      session->last_rep_score = score(session->peak_progress,
                                      session->worst_symmetry);
      ++session->repetitions;
      session->peak_progress = 0.f;
      session->worst_symmetry = 1.f;
    }
    session->phase = observed;
    session->last_transition = frame->timestamp_ms;
  }
  if (session->phase == target) {
    session->peak_progress = std::max(session->peak_progress, progress);
    session->worst_symmetry = std::min(session->worst_symmetry, symmetry);
  }
  session->last_timestamp = frame->timestamp_ms;
  const float live_score = progress <= 0.05f ? 0.f :
      score(std::max(session->peak_progress, progress),
            std::min(session->worst_symmetry, symmetry));
  *result = {session->exercise, session->phase, session->repetitions,
             progress, live_score,
             session->last_rep_score, left, right};
  return BR_OK;
}
} // extern "C"
