# Stage 1: Build
FROM golang:1.26-alpine AS builder
WORKDIR /app

# Abhängigkeiten laden (go.mod und go.sum)
COPY go.mod go.sum ./
RUN go mod download

# Quellcode kopieren und kompilieren
COPY . .
# CGO_ENABLED=0 sorgt für ein statisches Binary, wichtig für Alpine
RUN CGO_ENABLED=0 GOOS=linux go build -o eve-sync-worker .

# Stage 2: Run
FROM alpine:latest
# CA-Zertifikate installieren (wichtig für HTTPS/SSL Calls zu EVE ESI)
RUN apk --no-cache add ca-certificates tzdata
WORKDIR /app

# Nur das fertige Binary aus Stage 1 übernehmen
COPY --from=builder /app/eve-sync-worker .

CMD ["./eve-sync-worker"]