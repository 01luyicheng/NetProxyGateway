FROM golang:1.25-alpine AS builder

WORKDIR /app
# go-sqlite3 is a CGo-only driver (bundles the SQLite amalgamation). The builder
# needs a C toolchain; without it CGO defaults off on alpine and the resulting
# binary's sqlite3 driver is a stub that fails at runtime (db.Ping() returns
# "go-sqlite3 requires cgo to work. This is a stub"), crashing the API on startup.
RUN apk add --no-cache gcc musl-dev
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=1 GOOS=linux go build -o api .

FROM alpine:latest
RUN apk --no-cache add ca-certificates
WORKDIR /root/
COPY --from=builder /app/api .
EXPOSE 8080
CMD ["./api"]
