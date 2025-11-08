cmake_minimum_required(VERSION 3.16)
project(ScreenFlow VERSION 1.0.0 LANGUAGES CXX)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build type
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# Find required packages
find_package(PkgConfig REQUIRED)
find_package(SDL2 REQUIRED)
find_package(OpenGL REQUIRED)

# Find FFmpeg
find_path(AVCODEC_INCLUDE_DIR libavcodec/avcodec.h)
find_library(AVCODEC_LIBRARY avcodec)
find_path(AVUTIL_INCLUDE_DIR libavutil/avutil.h)
find_library(AVUTIL_LIBRARY avutil)
find_path(SWSCALE_INCLUDE_DIR libswscale/swscale.h)
find_library(SWSCALE_LIBRARY swscale)

# Find OpenSSL
find_package(OpenSSL REQUIRED)

# Include directories
include_directories(${SDL2_INCLUDE_DIRS})
include_directories(${OPENSSL_INCLUDE_DIR})
include_directories(src)

# Check if all required libraries are found
if(NOT SDL2_FOUND)
    message(FATAL_ERROR "SDL2 not found")
endif()

if(NOT AVCODEC_LIBRARY OR NOT AVUTIL_LIBRARY OR NOT SWSCALE_LIBRARY)
    message(FATAL_ERROR "FFmpeg libraries not found")
endif()

# Compiler-specific options
if(MSVC)
    add_compile_options(/W4)
else()
    add_compile_options(-Wall -Wextra -pedantic)
endif()

# Source files
set(SOURCES
    src/main.cpp
    src/network/ConnectionManager.cpp
    src/video/VideoDecoder.cpp
    src/input/InputHandler.cpp
    src/config/PairingManager.cpp
)

# Header files
set(HEADERS
    src/network/ConnectionManager.h
    src/video/VideoDecoder.h
    src/input/InputHandler.h
    src/config/PairingManager.h
    src/common/Types.h
)

# Create executable
add_executable(${PROJECT_NAME} ${SOURCES} ${HEADERS})

# Link libraries
target_link_libraries(${PROJECT_NAME}
    ${SDL2_LIBRARIES}
    ${OPENSSL_LIBRARIES}
    ${AVCODEC_LIBRARY}
    ${AVUTIL_LIBRARY}
    ${SWSCALE_LIBRARY}
    ${CMAKE_DL_LIBS}
)

# Platform-specific libraries
if(WIN32)
    target_link_libraries(${PROJECT_NAME} ws2_32 wsock32)
elseif(APPLE)
    find_library(COREFOUNDATION_FRAMEWORK CoreFoundation)
    find_library(IOKIT_FRAMEWORK IOKit)
    target_link_libraries(${PROJECT_NAME} ${COREFOUNDATION_FRAMEWORK} ${IOKIT_FRAMEWORK})
endif()

# Installation
install(TARGETS ${PROJECT_NAME}
    RUNTIME DESTINATION bin
)

# Create package config
include(CMakePackageConfigHelpers)
write_basic_package_version_file(
    "${CMAKE_CURRENT_BINARY_DIR}/ScreenFlowConfigVersion.cmake"
    VERSION ${PROJECT_VERSION}
    COMPATIBILITY SameMajorVersion
)

# Optional: Add tests
option(BUILD_TESTS "Build tests" OFF)
if(BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()