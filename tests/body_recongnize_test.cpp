#include "body_recongnize.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <initializer_list>

BRFrame frame(int64_t time) {
  BRFrame f{};
  f.timestamp_ms = time;
  f.image_width = 100;
  f.image_height = 100;
  for (auto &p : f.landmarks) p = {0.5f, 0.5f, 0.9f};
  return f;
}

void arm_pose(BRFrame &f, bool raised) {
  // Shoulder angle: down 0 degrees, up 180 degrees.
  f.landmarks[11] = {0.4f, 0.4f, 0.9f};
  f.landmarks[12] = {0.6f, 0.4f, 0.9f};
  f.landmarks[23] = {0.4f, 0.7f, 0.9f};
  f.landmarks[24] = {0.6f, 0.7f, 0.9f};
  f.landmarks[13] = {0.4f, raised ? 0.2f : 0.6f, 0.9f};
  f.landmarks[14] = {0.6f, raised ? 0.2f : 0.6f, 0.9f};
  f.landmarks[15] = {0.4f, raised ? 0.1f : 0.8f, 0.9f};
  f.landmarks[16] = {0.6f, raised ? 0.1f : 0.8f, 0.9f};
}

void squat_pose(BRFrame &f, bool down) {
  f.landmarks[11] = {0.4f, 0.1f, 0.9f};
  f.landmarks[12] = {0.6f, 0.1f, 0.9f};
  f.landmarks[23] = {0.4f, down ? 0.5f : 0.35f, 0.9f};
  f.landmarks[24] = {0.6f, down ? 0.5f : 0.35f, 0.9f};
  f.landmarks[25] = {0.4f, 0.6f, 0.9f};
  f.landmarks[26] = {0.6f, 0.6f, 0.9f};
  f.landmarks[27] = {down ? 0.55f : 0.4f, down ? 0.6f : 0.85f, 0.9f};
  f.landmarks[28] = {down ? 0.75f : 0.6f, down ? 0.6f : 0.85f, 0.9f};
}

void push_up_pose(BRFrame &f, bool lowered) {
  for (int side = 0; side < 2; ++side) {
    const float dy = side * .04f;
    f.landmarks[11 + side] = {.2f, .3f + dy, .9f};
    f.landmarks[13 + side] = {.2f, .5f + dy, .9f};
    f.landmarks[15 + side] = {lowered ? .4f : .2f,
                            (lowered ? .5f : .7f) + dy, .9f};
    f.landmarks[23 + side] = {.7f, .3f + dy, .9f};
  }
}

int main() {
  assert(br_api_version() == 1);
  assert(br_create(static_cast<BRExercise>(99)) == nullptr);
  BRResult result{};
  BRSession *raise = br_create(BR_ARM_RAISE);
  auto a = frame(0); arm_pose(a, false);
  assert(br_process(raise, &a, &result) == BR_OK);
  assert(result.phase == BR_DOWN);
  assert(result.live_score == 0.f);
  assert(br_process(raise, &a, &result) == BR_NON_MONOTONIC_TIME);
  auto bad = frame(100); arm_pose(bad, true);
  bad.landmarks[13].confidence = 0.1f;
  assert(br_process(raise, &bad, &result) == BR_INSUFFICIENT_LANDMARKS);
  a = frame(200); arm_pose(a, true);
  assert(br_process(raise, &a, &result) == BR_OK);
  assert(result.phase == BR_UP);
  a = frame(400); arm_pose(a, false);
  assert(br_process(raise, &a, &result) == BR_OK);
  assert(result.repetitions == 1 && result.last_rep_score >= 95.f);
  br_reset(raise);
  a = frame(0); arm_pose(a, false);
  assert(br_process(raise, &a, &result) == BR_OK && result.repetitions == 0);
  br_destroy(raise);

  // Changing image aspect ratio must not change angles for the same pixel pose.
  BRSession *square = br_create(BR_SQUAT);
  BRSession *portrait = br_create(BR_SQUAT);
  auto normal = frame(0); squat_pose(normal, false);
  normal.landmarks[27].x = 0.5f;
  BRResult square_result{};
  assert(br_process(square, &normal, &square_result) == BR_OK);
  auto tall = normal;
  tall.image_height = 200;
  for (auto &p : tall.landmarks) p.y *= 0.5f;
  BRResult portrait_result{};
  assert(br_process(portrait, &tall, &portrait_result) == BR_OK);
  assert(std::fabs(square_result.left_angle_deg -
                   portrait_result.left_angle_deg) < 0.01f);
  auto invalid = frame(1); squat_pose(invalid, false);
  invalid.image_width = 0;
  assert(br_process(square, &invalid, &square_result) == BR_INVALID_ARGUMENT);
  br_destroy(square);
  br_destroy(portrait);

  BRSession *squat = br_create(BR_SQUAT);
  auto s = frame(0); squat_pose(s, false);
  assert(br_process(squat, &s, &result) == BR_OK && result.phase == BR_UP);
  s = frame(200); squat_pose(s, true);
  assert(br_process(squat, &s, &result) == BR_OK && result.phase == BR_DOWN);
  s = frame(400); squat_pose(s, false);
  assert(br_process(squat, &s, &result) == BR_OK && result.repetitions == 1);
  assert(result.last_rep_score > 60.f && result.last_rep_score <= 100.f);
  br_destroy(squat);

  // Starting in the target pose cannot create a repetition, and a tracking
  // gap discards the unfinished cycle while keeping the completed count.
  raise = br_create(BR_ARM_RAISE);
  a = frame(0); arm_pose(a, true);
  assert(br_process(raise, &a, &result) == BR_OK);
  assert(result.phase == BR_UNKNOWN && result.repetitions == 0);
  a = frame(200); arm_pose(a, false);
  assert(br_process(raise, &a, &result) == BR_OK && result.phase == BR_DOWN);
  a = frame(400); arm_pose(a, true);
  assert(br_process(raise, &a, &result) == BR_OK && result.phase == BR_UP);
  a = frame(2000); arm_pose(a, false);
  assert(br_process(raise, &a, &result) == BR_OK && result.repetitions == 0);
  assert(result.phase == BR_DOWN);
  a = frame(2200); arm_pose(a, true);
  assert(br_process(raise, &a, &result) == BR_OK);
  a = frame(2400); arm_pose(a, false);
  assert(br_process(raise, &a, &result) == BR_OK && result.repetitions == 1);
  br_destroy(raise);
  // All newly exposed exercises require a complete start-target-start cycle.
  for (BRExercise kind : {BR_BICEP_CURL, BR_PUSH_UP, BR_LATERAL_RAISE}) {
    BRSession *session = br_create(kind);
    for (int step = 0; step < 3; ++step) {
      auto pose = frame(step * 300);
      const bool target = step == 1;
      arm_pose(pose, false);
      if (kind == BR_PUSH_UP) {
        push_up_pose(pose, target);
      } else if (kind == BR_LATERAL_RAISE) {
        pose.landmarks[13] = {target ? 0.15f : 0.4f, target ? 0.4f : 0.6f, 0.9f};
        pose.landmarks[14] = {target ? 0.85f : 0.6f, target ? 0.4f : 0.6f, 0.9f};
      } else {
        pose.landmarks[15] = {target ? 0.48f : 0.4f, target ? 0.46f : 0.8f, 0.9f};
        pose.landmarks[16] = {target ? 0.68f : 0.6f, target ? 0.46f : 0.8f, 0.9f};
      }
      assert(br_process(session, &pose, &result) == BR_OK);
      assert(result.repetitions == (step == 2 ? 1u : 0u));
    }
    br_destroy(session);
  }
  raise = br_create(BR_ARM_RAISE);
  auto hidden_hip = frame(0); arm_pose(hidden_hip, false);
  hidden_hip.landmarks[23].confidence = 0.1f;
  assert(br_process(raise, &hidden_hip, &result) == BR_INSUFFICIENT_LANDMARKS);
  br_destroy(raise);

  // A standing arm curl must not count as a push-up, even with clear joints.
  auto push = br_create(BR_PUSH_UP);
  for (int step = 0; step < 3; ++step) {
    auto pose = frame(step * 300); arm_pose(pose, false);
    if (step == 1) {
      pose.landmarks[15] = {.48f, .46f, .9f};
      pose.landmarks[16] = {.68f, .46f, .9f};
    }
    assert(br_process(push, &pose, &result) == BR_OK);
    assert(result.phase == BR_UNKNOWN && result.repetitions == 0);
  }
  br_destroy(push);

  // Single-side input remains supported, with the hip visible on that side.
  push = br_create(BR_PUSH_UP);
  for (int step = 0; step < 3; ++step) {
    auto pose = frame(step * 300); push_up_pose(pose, step == 1);
    for (int index : {12, 14, 16, 24}) pose.landmarks[index].confidence = 0.f;
    assert(br_process(push, &pose, &result) == BR_OK);
    assert(result.repetitions == (step == 2 ? 1u : 0u));
  }
  br_destroy(push);

  // Standing up interrupts an unfinished push-up instead of completing it.
  push = br_create(BR_PUSH_UP);
  auto plank = frame(0); push_up_pose(plank, false); br_process(push, &plank, &result);
  plank = frame(300); push_up_pose(plank, true); br_process(push, &plank, &result);
  auto standing = frame(600); arm_pose(standing, false); br_process(push, &standing, &result);
  plank = frame(900); push_up_pose(plank, false); br_process(push, &plank, &result);
  assert(result.repetitions == 0);
  plank.landmarks[23].confidence = plank.landmarks[24].confidence = 0.f;
  plank.timestamp_ms = 1200;
  assert(br_process(push, &plank, &result) == BR_INSUFFICIENT_LANDMARKS);
  br_destroy(push);
  std::puts("body_recongnize tests passed");
}
