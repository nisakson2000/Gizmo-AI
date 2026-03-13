FROM docker.io/nvidia/cuda:12.4.1-devel-ubuntu22.04 AS builder

RUN apt-get update && apt-get install -y \
    git cmake build-essential libcurl4-openssl-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
RUN git clone https://github.com/ggml-org/llama.cpp.git && \
    cd llama.cpp && \
    cmake -B build \
        -DGGML_NATIVE=OFF \
        -DGGML_CUDA=ON \
        -DGGML_BACKEND_DL=ON \
        -DGGML_CPU_ALL_VARIANTS=ON \
        -DCMAKE_CUDA_ARCHITECTURES=89 \
        -DLLAMA_BUILD_TESTS=OFF \
        -DCMAKE_EXE_LINKER_FLAGS="-Wl,--allow-shlib-undefined" && \
    cmake --build build --config Release -j$(nproc) --target llama-server && \
    mkdir -p /app/lib && \
    find build -name "*.so*" -exec cp -P {} /app/lib \; && \
    cp build/bin/llama-server /app/

FROM docker.io/nvidia/cuda:12.4.1-runtime-ubuntu22.04
RUN apt-get update && apt-get install -y curl libgomp1 && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/lib/ /app/
COPY --from=builder /app/llama-server /app/
ENV LD_LIBRARY_PATH=/app
WORKDIR /models
EXPOSE 8080
ENTRYPOINT ["/app/llama-server"]
