cmake_minimum_required(VERSION 3.22.1)
project(narasi_ai LANGUAGES C)

add_library(narasi_ai SHARED
    rnnoise_bridge.c
    rnnoise/src/denoise.c
    rnnoise/src/pitch.c
    rnnoise/src/kiss_fft.c
    rnnoise/src/celt_lpc.c
    rnnoise/src/rnn.c
    rnnoise/src/rnn_data.c
)
target_include_directories(narasi_ai PRIVATE
    rnnoise/include
    rnnoise/src
)
target_compile_features(narasi_ai PRIVATE c_std_99)
target_compile_options(narasi_ai PRIVATE -O3 -fvisibility=hidden)
target_link_libraries(narasi_ai m log)
