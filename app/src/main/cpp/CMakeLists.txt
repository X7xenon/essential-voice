cmake_minimum_required(VERSION 3.22)
project(essentialvoice CXX C)

set(CMAKE_CXX_STANDARD 17)

# whisper.cpp checkout lives beside the gradle project
set(WHISPER_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../whisper-src)

if (NOT EXISTS ${WHISPER_LIB_DIR}/src/whisper.cpp)
    message(FATAL_ERROR
        "whisper.cpp sources not found at ${WHISPER_LIB_DIR}. "
        "Run: git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git whisper-src")
endif()

file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)"
       VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()

# The arch flags have to be on CMAKE_C_FLAGS, not on the ggml target.
#
# ggml builds its CPU kernels in a separate target (ggml-cpu) and picks its
# vector paths from the compiler's own view of the architecture. Setting
# target_compile_options(ggml ...) leaves that target untouched: the build then
# reports FP16_VA = 0, DOTPROD = 0 and runs an order of magnitude slower —
# measured at 26s for 4s of speech, against 1.7s for 11s once these flags land.
set(EV_ARCH_FLAGS "-march=armv8.2-a+fp16+dotprod")
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} ${EV_ARCH_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${EV_ARCH_FLAGS}")

# Optimise in *every* variant, the debug one included.
#
# Gradle builds the debug variant with CMAKE_BUILD_TYPE=Debug, and the -O0 in
# CMAKE_C_FLAGS_DEBUG is appended after any per-target -O3, so it wins. That
# leaves ggml — which is where all the arithmetic actually happens —
# unoptimised: measured at 13.9s for a clip whisper-cli did in 0.8s on the same
# phone with the same model. There is no version of this app worth shipping
# with an unoptimised ggml, so the debug flags are overwritten here.
foreach(cfg DEBUG RELWITHDEBINFO MINSIZEREL)
    set(CMAKE_C_FLAGS_${cfg}   "-O3 -DNDEBUG")
    set(CMAKE_CXX_FLAGS_${cfg} "-O3 -DNDEBUG")
endforeach()

# ggml's own threadpool beats libomp here, and it is one fewer .so to ship.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Link ggml into our own library rather than shipping libggml-base.so and
# libggml-cpu.so beside it: static linking lets the linker drop what whisper
# never calls, and --gc-sections can then actually do its job.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

# ggml is consumed as a source subproject, same as the upstream android sample
include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

find_library(LOG_LIB log)

add_library(essentialwhisper SHARED
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${WHISPER_LIB_DIR}/src/aneforge/whisper-aneforge.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/jni.cpp
)

target_compile_definitions(essentialwhisper PUBLIC GGML_USE_CPU)
target_compile_definitions(essentialwhisper PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

target_include_directories(essentialwhisper PRIVATE
    ${WHISPER_LIB_DIR}
    ${WHISPER_LIB_DIR}/src
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/ggml/src
    ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)

# -O3 unconditionally, debug builds included. An unoptimised ggml turned 3.6s of
# speech into a 26 second wait on this phone; there is no build of this app in
# which that is worth having.
target_compile_options(essentialwhisper PRIVATE -O3)
target_compile_options(essentialwhisper PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_compile_options(essentialwhisper PRIVATE -ffunction-sections -fdata-sections)
target_link_options(essentialwhisper PRIVATE -Wl,--gc-sections)
target_link_options(essentialwhisper PRIVATE -Wl,--exclude-libs,ALL)

target_link_libraries(essentialwhisper ${LOG_LIB} android ggml)
