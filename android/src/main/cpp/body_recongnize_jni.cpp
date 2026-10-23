#include <jni.h>
#include "body_recongnize.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_bodyrecongnize_BodySession_nativeCreate(JNIEnv *, jobject, jint exercise) {
  return reinterpret_cast<jlong>(br_create(static_cast<BRExercise>(exercise)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_bodyrecongnize_BodySession_nativeDestroy(JNIEnv *, jobject, jlong handle) {
  br_destroy(reinterpret_cast<BRSession *>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_bodyrecongnize_BodySession_nativeReset(JNIEnv *, jobject, jlong handle) {
  br_reset(reinterpret_cast<BRSession *>(handle));
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_bodyrecongnize_BodySession_nativeProcess(JNIEnv *env, jobject,
                                                   jlong handle, jfloatArray values,
                                                   jint width, jint height,
                                                   jlong timestamp_ms) {
  if (!handle || !values || env->GetArrayLength(values) != BR_LANDMARK_COUNT * 3)
    return nullptr;
  jfloat raw[BR_LANDMARK_COUNT * 3];
  env->GetFloatArrayRegion(values, 0, BR_LANDMARK_COUNT * 3, raw);
  if (env->ExceptionCheck()) return nullptr;
  BRFrame frame{};
  frame.timestamp_ms = timestamp_ms;
  frame.image_width = width > 0 ? static_cast<uint32_t>(width) : 0;
  frame.image_height = height > 0 ? static_cast<uint32_t>(height) : 0;
  for (int i = 0; i < BR_LANDMARK_COUNT; ++i)
    frame.landmarks[i] = {raw[i * 3], raw[i * 3 + 1], raw[i * 3 + 2]};
  BRResult result{};
  const BRStatus status = br_process(reinterpret_cast<BRSession *>(handle),
                                     &frame, &result);
  jfloat output[] = {static_cast<jfloat>(status), static_cast<jfloat>(result.phase),
                     static_cast<jfloat>(result.repetitions), result.progress,
                     result.live_score, result.last_rep_score,
                     result.left_angle_deg, result.right_angle_deg};
  jfloatArray array = env->NewFloatArray(8);
  if (array) env->SetFloatArrayRegion(array, 0, 8, output);
  return array;
}
