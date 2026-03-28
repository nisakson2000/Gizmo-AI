FROM docker.io/python:3.12-slim

# Python libraries
RUN pip install --no-cache-dir numpy pandas matplotlib sympy scipy \
    reportlab openpyxl python-docx python-pptx && \
    rm -rf /root/.cache

# Additional language runtimes
RUN apt-get update && apt-get install -y --no-install-recommends \
    nodejs \
    gcc \
    g++ \
    libc6-dev \
    lua5.4 \
    golang-go \
    && rm -rf /var/lib/apt/lists/*

# Go needs writable GOPATH and GOCACHE — use /tmp (tmpfs)
ENV GOPATH=/tmp/go
ENV GOCACHE=/tmp/go-cache
ENV PATH="/tmp/go/bin:${PATH}"

USER nobody
WORKDIR /tmp

CMD ["python3", "-c", "print('sandbox ready')"]
