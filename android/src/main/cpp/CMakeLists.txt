cmake_minimum_required(VERSION 3.16)
project(BodyRecongnizeAndroid LANGUAGES CXX)

add_library(body_recongnize_jni SHARED
    body_recongnize_jni.cpp
    ../../../../src/body_recongnize.cpp)
target_include_directories(body_recongnize_jni PRIVATE ../../../../include)
target_compile_features(body_recongnize_jni PRIVATE cxx_std_17)
