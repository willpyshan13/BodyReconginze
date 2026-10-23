cmake_minimum_required(VERSION 3.16)
project(BodyRecongnize LANGUAGES CXX)

add_library(body_recongnize STATIC src/body_recongnize.cpp)
target_include_directories(body_recongnize PUBLIC include)
target_compile_features(body_recongnize PUBLIC cxx_std_17)

include(CTest)
if(BUILD_TESTING)
  add_executable(body_recongnize_test tests/body_recongnize_test.cpp)
  target_link_libraries(body_recongnize_test PRIVATE body_recongnize)
  add_test(NAME body_recongnize_test COMMAND body_recongnize_test)
endif()
