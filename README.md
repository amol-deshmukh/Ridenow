# RideNow: a gRPC demo for Android system design interviews

An Uber-style ride-hailing sample that uses **all 4 gRPC call types** end to end:
an **Android client** (Kotlin, Compose, MVVM, Coroutines/Flow) and a **local Kotlin/JVM gRPC server**, sharing one `.proto` contract.

> Revision path: run it (§2), map each feature to its RPC type (§3), read the concepts (§5–§9), then drill the interview Q&A (§11).

---

## Table of contents
1. [Project structure](#1-project-structure)
2. [Run it locally](#2-run-it-locally)
3. [Use case → RPC type mapping](#3-use-case--rpc-type-mapping)
4. [Guided demo: what to click and what to watch](#4-guided-demo-what-to-click-and-what-to-watch)
5. [gRPC fundamentals](#5-grpc-fundamentals)
6. [Protobuf and API evolution](#6-protobuf-and-api-evolution)
7. [The 4 RPC types in detail (with code pointers)](#7-the-4-rpc-types-in-detail)
8. [Production concerns on Android](#8-production-concerns-on-android)
9. [gRPC vs REST vs WebSocket vs SSE vs GraphQL](#9-grpc-vs-rest-vs-websocket-vs-sse-vs-graphql)
10. [Testing gRPC](#10-testing-grpc)
11. [Interview cheat sheet and Q&A](#11-interview-cheat-sheet--qa)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Project structure

| Module | Gradle path | Package |
|---|---|---|
| Contract (generated code) | `:proto` | `com.ridenow.proto.v1` (proto package `ridenow.v1`) |
| Server | `:server` | `com.ridenow.server` |
| Android app | `:app` | `com.ridenow.android` (also the `applicationId`) |

```
GRPCDemo/                                   ← folder name; Gradle project name is "RideNow"
├── proto/                                  ← SHARED CONTRACT (JVM library)
│   ├── build.gradle.kts                    protoc + grpc-java + grpc-kotlin codegen (lite)
│   └── src/main/proto/ridenow/v1/ride_service.proto
│
├── server/                                 ← LOCAL gRPC SERVER (Kotlin/JVM + Netty)
│   ├── build.gradle.kts                    `application` plugin → ./gradlew :server:run
│   └── src/main/kotlin/com/ridenow/server/
│       ├── Main.kt                         server bootstrap, keepalive, graceful shutdown
│       ├── Interceptors.kt                 Auth / Logging / Chaos (fault injection)
│       ├── RideServiceImpl.kt              the 5 RPCs
│       └── Domain.kt                       pricing, driver simulation, geo helpers
│   └── src/test/.../RideServiceTest.kt     in-process transport tests (8 tests)
│
└── app/                                    ← ANDROID CLIENT
    └── src/main/java/com/ridenow/android/
        ├── RideNowApp.kt                   Application + AppContainer (manual DI)
        ├── data/
        │   ├── GrpcClient.kt               ManagedChannel: OkHttp, keepalive, retry policy
        │   ├── ClientInterceptors.kt       Auth header + logging interceptor + call log
        │   ├── RideRepository.kt           wraps the coroutine stub (only gRPC-aware class)
        │   └── GrpcErrors.kt               Status code → user message
        └── ui/
            ├── ride/    RideViewModel + RideScreen      (unary + server streaming)
            ├── driver/  DriverViewModel + DriverScreen  (client streaming)
            ├── chat/    ChatViewModel + ChatScreen      (bidi streaming)
            └── logs/    LogsScreen                      (interceptor output)
```

**Why a separate `:proto` module?** It is the single source of truth. Server and app both depend on it, so any breaking contract change fails the build. In real companies the `.proto` files live in a shared repo (or a schema registry like Buf BSR), and each platform generates its own code from them.

### Architecture (client)

```
 Compose UI ──collectAsStateWithLifecycle──▶ ViewModel (StateFlow<UiState>)
                                                │  viewModelScope.launch / Flow.collect
                                                ▼
                                         RideRepository   ◀── only class that knows gRPC
                                                │  RideServiceCoroutineStub (generated)
                                                ▼
                                  ManagedChannel (ONE per app, app-scoped)
                                  ├─ AuthClientInterceptor   (adds "authorization")
                                  ├─ LoggingClientInterceptor
                                  ├─ service config: per-method timeout + retry policy
                                  └─ AndroidChannelBuilder → OkHttp transport
                                                │  single HTTP/2 connection (multiplexed)
                                                ▼
                                  Netty server :50051 ── Logging → Auth → Chaos → RideServiceImpl
```

### Tech stack and versions

| Piece | Library | Version |
|---|---|---|
| gRPC Java core / OkHttp / Netty | `io.grpc:*` | 1.85.1 |
| Kotlin coroutine stubs | `io.grpc:grpc-kotlin-stub` | 1.5.0 (needs Java 17) |
| Protobuf (lite runtime + protoc) | `com.google.protobuf` | 4.36.2 |
| Gradle codegen | `com.google.protobuf` plugin | 0.10.0 |
| Coroutines | `kotlinx-coroutines` | 1.11.0 |

All versions are in `gradle/libs.versions.toml`.

---

## 2. Run it locally

### Prerequisites
- Android Studio (AGP 9.x), JDK 17+ (the bundled JBR works)
- An emulator (API 24+), or a physical device

### Step 1: start the server
```bash
./gradlew :server:run
# [11:00:54] RideNow gRPC server listening on port 50051 (chaos=0.0)
```
Options:
```bash
./gradlew :server:run --args="--port 50051 --chaos 0.4"   # 40% of unary calls fail with UNAVAILABLE
```
`--chaos` lets you **watch automatic retries** happen (see §4).

### Step 2: run the app
- **Emulator:** press Run in Android Studio. The app connects to `10.0.2.2:50051`. `10.0.2.2` is the emulator's alias for your Mac's `localhost`.
- **Physical device (USB), the easiest option:**
  ```bash
  adb reverse tcp:50051 tcp:50051
  ```
  Then add `grpc.host=127.0.0.1` to `local.properties` and rebuild.
- **Physical device over Wi-Fi:** add `grpc.host=<your Mac's LAN IP>` to `local.properties` (find it with `ipconfig getifaddr en0`), and add that IP to `res/xml/network_security_config.xml`.

### Step 3: run the server tests
```bash
./gradlew :server:test
```

### Optional: call the server from the terminal with grpcurl
```bash
brew install grpcurl
# The server uses protobuf-lite (no reflection), so pass the .proto file explicitly:
grpcurl -plaintext -import-path proto/src/main/proto -proto ridenow/v1/ride_service.proto \
  -H 'authorization: Bearer demo-alice' \
  -d '{"pickup":{"lat":12.9352,"lng":77.6245},"dropoff":{"lat":12.9784,"lng":77.6408},"ride_type":"RIDE_TYPE_ECONOMY"}' \
  localhost:50051 ridenow.v1.RideService/GetFareEstimate

# Without a token → UNAUTHENTICATED (rejected by AuthInterceptor)
grpcurl -plaintext -import-path proto/src/main/proto -proto ridenow/v1/ride_service.proto \
  -d '{}' localhost:50051 ridenow.v1.RideService/GetFareEstimate
```

---

## 3. Use case → RPC type mapping

| Feature | RPC | Type | Why this type? |
|---|---|---|---|
| Fare quote | `GetFareEstimate` | **Unary** | Plain request/response |
| Book a car | `RequestRide` | **Unary** | Plus an `idempotency_key` so retries can't double-book |
| Live driver on map | `TrackDriver` | **Server streaming** | The server pushes updates and the client doesn't poll. One request, N responses |
| Driver flushes offline GPS buffer | `UploadLocations` | **Client streaming** | N small messages over one stream, one summary at the end. Much cheaper than N HTTP requests |
| Rider ↔ driver chat | `TripChat` | **Bidirectional** | Both sides send at any time, independently |

---

## 4. Guided demo: what to click and what to watch

Keep the **server terminal** and the app's **Logs tab** side by side.

1. **Ride tab → Get fare.** Unary call.
   Server: `--> GetFareEstimate (UNARY)` … `<-- OK (305ms)`.
2. **Request ride.** Unary call, then the app immediately opens the `TrackDriver` stream. The dot moves along the mini map: `ARRIVING → ARRIVED → IN_PROGRESS → COMPLETED`. After that the **server** closes the stream with `OK`, and the client log shows `← TrackDriver OK | sent=1 recv=44`.
3. **Stop stream (cancel)** in the middle of a ride. Server log: `TrackDriver(...): client cancelled the stream`. Cancelling the coroutine cancelled the RPC, the server coroutine was cancelled too, and nothing leaks.
   **Resume stream** picks up at the correct position, because the server computes the location from its own state.
4. **Retries:** restart the server with `--chaos 0.4` and request a few fares. Server log:
   ```
   --> RideService/RequestRide (UNARY)
   <-- RideService/RequestRide UNAVAILABLE - Chaos: simulated outage
   --> RideService/RequestRide (UNARY) [retry #1]
   <-- RideService/RequestRide OK
   ```
   The **client** Logs tab shows only **one** call. Interceptors added with `channel.intercept()` sit *above* the retry layer, so retries are invisible to app code. The retry policy is declared in the service config in `GrpcClient.kt`.
5. **Kill the server (Ctrl+C) while tracking.** The app shows `reconnecting (attempt n)…`. Restart the server and the stream resumes. (Rides are in memory, so a restarted server returns `NOT_FOUND` for old rides. That's also a good error-path demo.)
6. **Driver tab → Upload trip.** Client streaming. The progress bar fills, the server logs `received 10/20/… points`, and then a **single** summary response arrives *after* the client half-closes.
7. **Chat tab → Connect → send "hi" / "where are you?".** Bidi streaming. With no driver connected, a bot replies.
   For a real conversation, run two emulators: one joins as Rider and one as Driver, both in the same room (ride id). Messages come back from the server (with a server timestamp/id), so everyone sees the same order.
   **Leave** = graceful half-close (status OK). **Cancel** = abrupt cancel (server sees CANCELLED).

---

## 5. gRPC fundamentals

**gRPC** is a high-performance RPC framework from Google. You define services and messages in **Protocol Buffers (IDL)**, generate strongly-typed client and server code, and the calls run over **HTTP/2**.

### How a call travels over the wire
```
Client stub ──serialize (protobuf)──▶ HTTP/2 stream on a shared TCP connection
   HEADERS frame : :method POST, :path /ridenow.v1.RideService/GetFareEstimate,
                   content-type: application/grpc, authorization: Bearer…, grpc-timeout: 5S
   DATA frame(s) : [1 byte compressed-flag][4 bytes length][protobuf bytes]  ← "length-prefixed message"
Server ──▶ HEADERS (:status 200) ──▶ DATA (response msgs) ──▶ TRAILERS (grpc-status: 0, grpc-message)
```
- The **gRPC status lives in trailers**, not in the HTTP status. HTTP is almost always `200`. This is why plain HTTP/1.1 proxies and browsers can't speak native gRPC (browsers use **gRPC-Web** through a proxy like Envoy).
- **Deadline** is sent as the `grpc-timeout` header, so the server knows how long the client will wait and can propagate the deadline to downstream calls.

### HTTP/2 features gRPC relies on
| Feature | Why it matters |
|---|---|
| **Multiplexing** | Many concurrent calls and streams share ONE TCP connection. No head-of-line blocking at the HTTP layer and no connection-pool juggling. |
| **Binary framing** | Efficient to parse |
| **HPACK header compression** | Repeated headers (auth token, path) cost a few bytes after the first call |
| **Flow control** (per stream and per connection) | Built-in **backpressure** for streams |
| **Long-lived bidirectional streams** | Enables server, client, and bidi streaming |
| **PING / GOAWAY** | Keepalive and graceful server drain |

> Caveat: TCP-level head-of-line blocking still exists, because one lost packet stalls all streams. HTTP/3 (QUIC) fixes that. Cronet on Android supports QUIC (see §8).

### Key building blocks (with names from this repo)
| Concept | What it is | Here |
|---|---|---|
| **Channel** (`ManagedChannel`) | Virtual connection to a server: name resolution, load balancing, connection management, and reconnect. **Expensive, so create one per server and share it.** | `GrpcClient.channel` |
| **Stub** | Generated client proxy. **Cheap**, so create one per call if you like. Kinds: blocking, future, async (Java), and coroutine (Kotlin). | `RideServiceCoroutineStub` |
| **CallOptions** | Per-call config: deadline, compression, executor | `stub.withDeadlineAfter(...)` |
| **Metadata** | Key-value headers/trailers (like HTTP headers). `-bin` suffix = binary value | `authorization` header |
| **Interceptor** | Middleware on client or server for auth, logging, metrics, tracing | `ClientInterceptors.kt`, `Interceptors.kt` |
| **Status** | Code + description, carried in trailers | `StatusException(Status.NOT_FOUND…)` |
| **Context** (server) | Request-scoped values plus cancellation and deadline propagation | `Auth.USER_ID` set by `AuthInterceptor` |
| **Service config** | Declarative per-method timeouts, retries, and LB policy | `GrpcClient.serviceConfig` |

### Status codes you must know
| Code | Meaning | Retry? | Example here |
|---|---|---|---|
| `OK` (0) | Success | – | |
| `CANCELLED` (1) | Caller cancelled | No | User pressed "Stop stream" |
| `INVALID_ARGUMENT` (3) | Bad input (client bug) | **No** | Missing pickup |
| `DEADLINE_EXCEEDED` (4) | Ran out of time | Maybe, if idempotent | |
| `NOT_FOUND` (5) | Entity missing | No | Unknown ride_id |
| `PERMISSION_DENIED` (7) | Authenticated but not allowed | No | Tracking someone else's ride |
| `RESOURCE_EXHAUSTED` (8) | Rate limited / quota | Yes, with backoff | |
| `FAILED_PRECONDITION` (9) | System not in the required state | No (fix state first) | Estimate expired |
| `UNIMPLEMENTED` (12) | Method not on the server | No | Old server, new client |
| `INTERNAL` (13) | Server bug | No | |
| `UNAVAILABLE` (14) | Transient: server down or network gone | **Yes** | `--chaos` |
| `UNAUTHENTICATED` (16) | No or invalid credentials | Refresh token, then retry | Missing bearer token |

---

## 6. Protobuf and API evolution

```proto
message FareEstimateResponse {
  string estimate_id = 1;          // field NUMBER (tag) is what goes on the wire, not the name
  int64 fare_minor_units = 2;      // money as integer paise/cents, never float
  ...
}
```

**Wire format:** each field is encoded as `(field_number << 3 | wire_type)` followed by the value. Ints use **varints** (small numbers take 1 byte). Unset fields aren't sent at all. A typical message is **3–10× smaller** than JSON and much faster to parse, because there's no string keys and no text→number conversion.

### Rules for backward/forward compatibility (critical for mobile)
Old app versions stay in the wild for **years**, so every server change must keep them working.
- ✅ Add new fields with **new numbers**. Old clients **ignore unknown fields**, and new clients see defaults when old servers don't send them.
- ✅ Add new RPCs and new enum values. Clients must handle `UNSPECIFIED` or unknown values gracefully.
- ❌ Never change a field's **number** or **type** incompatibly.
- ❌ Never **reuse** a deleted field's number. Use `reserved 6; reserved "driver_phone";` (see `RideResponse`).
- ❌ Don't rename packages or services in place. Breaking changes go into a **new version**: `ridenow.v2`.
- proto3 has no "required". Validate in code (see `getFareEstimate`).
- Enum value `0` must be `*_UNSPECIFIED`, because unset decodes as 0.
- Use `optional` (proto3) or wrapper types when you must tell "unset" apart from the default 0/"".
- `oneof` is a tagged union (see `ChatClientEvent { join | text }`).
- Tooling: `buf breaking` in CI catches breaking changes automatically.

### Lite runtime on Android
We generate with `option("lite")` (`protobuf-javalite`). That gives no descriptors or reflection, a much smaller method count and APK, and it works well with R8. The trade-off: no `JsonFormat` and no server reflection, which is why grpcurl needs `-proto`. Consumer R8 rules ship inside the libraries, so no custom keep rules are needed (the release build in this repo minifies successfully).

### Codegen in this repo (`proto/build.gradle.kts`)
| Plugin | Output |
|---|---|
| protoc builtin `java` (lite) | `FareEstimateRequest` etc. (immutable messages + builders) |
| protoc builtin `kotlin` (lite) | DSL builders: `fareEstimateRequest { pickup = …; rideType = … }` |
| `protoc-gen-grpc-java` | `RideServiceGrpc` (method descriptors, Java stubs) |
| `protoc-gen-grpc-kotlin` | `RideServiceGrpcKt` (coroutine stub + `RideServiceCoroutineImplBase`) |

---

## 7. The 4 RPC types in detail

grpc-kotlin maps them to idiomatic Kotlin:

| Type | `.proto` | Server signature | Client usage |
|---|---|---|---|
| Unary | `rpc A(Req) returns (Resp)` | `suspend fun a(req): Resp` | `val r = stub.a(req)` |
| Server stream | `returns (stream Resp)` | `fun a(req): Flow<Resp>` | `stub.a(req).collect { }` |
| Client stream | `(stream Req)` | `suspend fun a(reqs: Flow<Req>): Resp` | `stub.a(flowOfReqs)` |
| Bidi | `(stream Req) returns (stream Resp)` | `fun a(reqs: Flow<Req>): Flow<Resp>` | `stub.a(outFlow).collect { }` |

### 7.1 Unary: `GetFareEstimate`, `RequestRide`
```
Client                         Server
  │── HEADERS + DATA(req) ───────▶│
  │◀── HEADERS + DATA(resp) + TRAILERS(status) ──│
```
- `RideViewModel.getEstimate()` → `RideRepository.getFareEstimate()` → `stub.getFareEstimate()`.
- The **deadline (5 s)** and **retries** come from the service config, not the call site.
- **Idempotency:** the client generates `idempotency_key` once per booking intent. The server maps key → ride, so retries (automatic or the user tapping again) return the **same** ride. Without it, a lost response plus a retry means **two cars booked**. This is a classic interview probe.

### 7.2 Server streaming: `TrackDriver`
```
Client                         Server
  │── HEADERS + DATA(req) + END_STREAM ─▶│   (client half-closes right away)
  │◀────────── DATA(loc 1) ──────────────│
  │◀────────── DATA(loc 2) ──────────────│   every 1s
  │◀────────── …          ───────────────│
  │◀────────── TRAILERS(OK) ─────────────│   ride COMPLETED → server ends stream
```
- Server: `flow { while(true) { emit(snapshot); delay(1s) } }`. Cancellation is automatic: when the client goes away the coroutine is cancelled (`onCompletion` logs it).
- **State is server-authoritative.** Location is derived from server time, so the stream is a *view*. Reconnecting simply resumes, and the client never needs to replay history.
- Client: `retryWhen` reconnects on `UNAVAILABLE` with exponential backoff (1, 2, 4, 8, 16 s). **Built-in gRPC retries do NOT replay a stream once the client has received a response message**, so long-lived streams need app-level reconnect.
- **No deadline** on long-lived streams. Instead, rely on keepalive to detect dead connections.
- Lifecycle: the stream runs in `viewModelScope`, so it survives rotation and ends when the ViewModel is cleared. The UI collects with `collectAsStateWithLifecycle`, so it stops rendering in the background.
  *Real app:* while backgrounded, stop the stream (save battery) and rely on **FCM push** for status changes. During an active trip you'd use a **foreground service**.

### 7.3 Client streaming: `UploadLocations`
```
Client                         Server
  │── HEADERS ─────────────────▶│
  │── DATA(p1) DATA(p2) … DATA(p60) ─▶│   server collects incrementally
  │── END_STREAM (half-close) ─▶│
  │◀── DATA(summary) + TRAILERS ─│     exactly ONE response
```
- The client provides a cold `Flow<LocationPoint>`. grpc-kotlin collects it, and **HTTP/2 flow control suspends `emit` when the receiver is slow**, which is backpressure without extra code.
- Real use: a driver app buffers GPS fixes in Room while offline, then flushes them in one stream instead of 60 HTTP requests (60 × headers/TLS/latency).
- `stub.withDeadlineAfter(60s)`: note the deadline is **absolute and computed when the stub is created**, so derive a fresh stub per call and never cache one in a field.

### 7.4 Bidirectional streaming: `TripChat`
```
Client                          Server
  │── DATA(join) ───────────────▶│  first message must be `join` (oneof)
  │◀────────── DATA(system: joined)
  │── DATA("hi") ───────────────▶│
  │◀────────── DATA(rider: "hi")      broadcast back with server id + timestamp
  │◀────────── DATA(driver: "Hello!") bot or another device, at any time
  │── END_STREAM (Leave) ───────▶│
  │◀────────── TRAILERS(OK)
```
- The two directions are **independent**. Client: a `Channel<ChatClientEvent>` turned into a Flow is the outgoing side, and `collect` is the incoming side. Server: `channelFlow`, with one coroutine collecting requests and another relaying the room's `SharedFlow`.
- Request and response types differ (`ChatClientEvent` vs `ChatMessage`). That's fine and common.
- **Half-close vs cancel:** `outgoing.close()` says "I'm done sending" and the server finishes gracefully with OK. `job.cancel()` sends RST_STREAM and the server sees CANCELLED.
- **Ordering:** the server timestamps and broadcasts. Clients render server echoes, not local copies. (Production: an optimistic local bubble reconciled by a client message id, plus delivery and read receipts.)

---

## 8. Production concerns on Android

### Channel management
- **One `ManagedChannel` per backend, app-scoped** (`AppContainer`, or Hilt `@Singleton`). A new channel per screen means a new TCP + TLS handshake (100s of ms on mobile).
- `idleTimeout(5 min)`: drops the TCP connection when idle and reconnects transparently on the next call. Good for battery and for server socket count.
- **`AndroidChannelBuilder`** (`grpc-android`) listens to `ConnectivityManager`. On Wi-Fi ↔ LTE switches it resets backoff and reconnects immediately, instead of waiting out an exponential backoff on a dead socket.
- `channel.shutdown()` on logout or app teardown. In a single-process app the channel usually lives for the whole process.

### Transport: OkHttp vs Netty vs Cronet
| Transport | Where | Notes |
|---|---|---|
| **grpc-okhttp** | Android (this app) | Small and Android-friendly. |
| grpc-netty(-shaded) | Server / JVM | Full-featured, but too heavy for Android. |
| **grpc-cronet** | Android | Chrome's network stack: **QUIC/HTTP3**, better on lossy mobile networks, shared connection pool with other Cronet traffic. Used by Google apps. |

### Keepalive
- The client sends HTTP/2 PINGs (`keepAliveTime 30s`) to detect dead connections caused by NAT timeouts, load balancer idle cuts, or radio changes.
- The server must **permit** it (`permitKeepAliveTime 20s` ≤ client's 30 s). Otherwise it replies `GOAWAY too_many_pings`.
- `keepAliveWithoutCalls(false)`: never wake the radio when nothing is happening (battery).

### Deadlines, cancellation and retries
- **Always set deadlines on unary calls.** Without one, a call can hang forever. Deadlines **propagate** (`grpc-timeout`), so backends stop working on requests whose client already gave up.
- Cancel = coroutine cancellation. `viewModelScope` ties the RPC lifetime to the screen.
- Retries: **service config retryPolicy** (`maxAttempts`, exponential backoff + jitter, `retryableStatusCodes: [UNAVAILABLE]`). Only retry **idempotent** operations, or make them idempotent with keys.
- gRPC also does **transparent retries** for requests that never left the client, which are always safe.
- Global **retry throttling** (`retryThrottling` in the service config) prevents retry storms when the backend is struggling.
- Hedging (send duplicate requests in parallel to cut tail latency) is also supported. Mention it, but it's rarely used on mobile.

### Security
- **TLS everywhere** in production: remove `usePlaintext()`. Optionally add **certificate pinning** (`OkHttpChannelBuilder.sslSocketFactory` / custom `HostnameVerifier`).
- **Auth:** OAuth/JWT bearer token in the `authorization` metadata, attached by a client interceptor (`AuthClientInterceptor`). gRPC also has `CallCredentials` for async token fetch. On `UNAUTHENTICATED`: refresh the token once, then retry.
- The server validates in an interceptor and puts the identity on the `Context`. Business code does **authorization** (`PERMISSION_DENIED` for someone else's ride).
- Local cleartext is whitelisted only for dev hosts in `network_security_config.xml`.

### Payload, battery and performance
- Protobuf is smaller and faster to parse than JSON, which means less radio time and CPU, and so better battery life.
- Enable **gzip** for large payloads (`stub.withCompression("gzip")`). It's not worth it for small messages.
- `maxInboundMessageSize` guards against huge responses (client 4 MB, server 1 MB here).
- **Batch** (client streaming) instead of chatty unary calls. The radio's tail energy is what kills battery.
- Location streams: lower the update frequency when the driver is far away, and send deltas.

### Observability
- Interceptors log method, status and latency (`LoggingInterceptor`, `LoggingClientInterceptor`).
- In production: OpenTelemetry / Perfetto, `grpc-census`, per-RPC latency percentiles (p50/p95/p99), error-rate dashboards per status code, and app version in metadata for debugging old clients.

### Offline-first (how it fits into a mobile architecture)
- gRPC is the transport, not the source of truth. Pattern: **Room/SQLite as the single source of truth → UI observes the DB → sync layer (WorkManager) pushes/pulls via gRPC**. Upload queues map naturally to client streaming.

### Load balancing (they will ask)
- HTTP/2 connections are long-lived, so **L4 load balancers balance connections, not requests**. One client sticks to one backend.
- Solutions: an **L7 proxy** (Envoy) that understands gRPC streams, or client-side LB (`round_robin` with DNS or **xDS**). Mobile clients typically go through an Envoy edge proxy.
- Servers send **GOAWAY** periodically (`maxConnectionAge`) to force rebalancing.

---

## 9. gRPC vs REST vs WebSocket vs SSE vs GraphQL

| | **gRPC** | **REST/JSON** | **WebSocket** | **SSE** | **GraphQL** |
|---|---|---|---|---|---|
| Transport | HTTP/2 | HTTP/1.1 or 2 | TCP upgrade from HTTP/1.1 | HTTP | HTTP |
| Payload | Protobuf (binary) | JSON (text) | Anything (you define it) | Text events | JSON |
| Contract | **Strict** `.proto` + codegen | OpenAPI (optional) | None (DIY) | None | Schema + codegen |
| Streaming | **4 modes** built in | ✗ (polling / long-poll) | Bidi | Server → client only | Subscriptions (usually over WS) |
| Browser support | Needs gRPC-Web + proxy | Native | Native | Native | Native |
| Caching | ✗ (POST only) | **HTTP/CDN caching** | ✗ | ✗ | Hard (POST) |
| Debuggability | Harder (binary; grpcurl) | **Easy** (curl, browser) | Medium | Easy | Medium |
| Deadlines / cancel / status codes | **Built in** | DIY | DIY | DIY | DIY |
| Best for | Internal microservices, **mobile ↔ backend with strict contracts**, streaming | Public APIs, cacheable resources, simple CRUD | Chat, games, custom real-time protocols | Live feeds, notifications | Flexible client-driven data fetching, many screens/clients |

**How to answer "Why gRPC for the rider app?"**
- Strong typed contract shared by iOS, Android, and backend, with codegen, so no hand-written JSON parsing bugs.
- Smaller payloads mean less data and battery. Multiplexing means one connection for everything.
- Native streaming for location tracking, so no polling.
- Deadlines, cancellation, and standard status codes all come for free.

**When NOT to use it:** public or third-party APIs, CDN-cacheable content (images, catalogs), browser-first products, or teams with no proto tooling. **Hybrid is common:** gRPC for app ↔ backend real-time and core flows, REST/CDN for static content, and FCM/APNs push for background wake-ups.

**Polling vs streaming vs push, for tracking a driver:**
- Polling every N seconds is simple but wastes battery and data and adds latency.
- A long-lived stream is real-time, but only while the app is in the foreground. It needs reconnect logic.
- Push (FCM) works in the background but is low-frequency, has no ordering guarantees, and can be throttled.
- **Answer:** stream while the screen is visible, push for state transitions in the background (driver arrived), and on resume re-fetch with a unary call and then reopen the stream.

---

## 10. Testing gRPC

- **Server:** `RideServiceTest` uses `InProcessServerBuilder` / `InProcessChannelBuilder`. That gives full gRPC semantics (metadata, interceptors, status codes, streaming) **without sockets**, fast and deterministic. It covers all 4 RPC types, auth failure, permission denied, and idempotency.
- **Client:**
  - Unit-test ViewModels against a **fake repository** (the repository is the only gRPC-aware class).
  - Or run an in-process server with a fake `RideServiceCoroutineImplBase` and point the real stub at it (`grpc-testing` has `GrpcCleanupRule`).
- **Contract:** `buf lint` and `buf breaking` in CI.
- **Manual:** grpcurl (§2), or Postman / Insomnia / BloomRPC-style GUIs.

---

## 11. Interview cheat sheet and Q&A

### 30-second pitch
> "gRPC is an RPC framework where the API is defined in Protobuf, client and server code is generated, and calls run over HTTP/2. On mobile that gives me a strict, versioned contract across platforms, compact binary payloads, one multiplexed connection, and native streaming. For a ride-hailing app I'd use unary calls for quotes and booking (with idempotency keys), server streaming for live driver location, client streaming to flush buffered GPS points, and bidi streaming for in-trip chat. I'd add deadlines, retries with backoff on UNAVAILABLE, keepalive, and an auth interceptor."

### Likely questions

**Q: How does gRPC differ from REST?**
Contract-first with codegen, binary protobuf, HTTP/2 multiplexing, 4 streaming modes, built-in deadlines, cancellation, and status codes. On the downside: no HTTP caching, harder to debug, and it needs gRPC-Web for browsers. See §9.

**Q: Why HTTP/2?**
Multiplexed streams on one connection, binary framing, header compression, flow control (backpressure), and long-lived bidirectional streams. These are exactly what the 4 RPC types need.

**Q: How do you handle a flaky mobile network?**
Deadlines on every unary call. Retry policy on `UNAVAILABLE` with exponential backoff and jitter, plus retry throttling. Idempotency keys for mutations. App-level reconnect for long-lived streams (`retryWhen`), with server-authoritative state so a reconnect simply resumes. Keepalive to detect dead connections. `AndroidChannelBuilder` to react to network changes. Offline queue (Room + WorkManager) for writes.

**Q: Why can't you just retry `RequestRide`?**
The response may have been lost *after* the server booked the car, so a blind retry books two cars. Use an idempotency key: the server dedupes on it (`ridesByIdempotencyKey`).

**Q: How do you version a gRPC API used by mobile apps that never update?**
Additive changes only within `v1`: new field numbers, `reserved` for removed fields, unknown fields ignored, enum default `UNSPECIFIED`. Breaking changes go into `v2` and the server runs both until v1 traffic is gone (watch it by app version in metadata). Enforce with `buf breaking` in CI. Use minimum-supported-version gating (force-upgrade screen) as a last resort.

**Q: How does auth work?**
A bearer token in metadata, attached by a client interceptor (or `CallCredentials`), over TLS. The server interceptor validates it and puts the identity on the `Context`. Business logic checks authorization. On `UNAUTHENTICATED`, refresh and retry once.

**Q: Deadline vs timeout?**
A timeout is a relative duration ("5 s"). A deadline is an absolute point in time. gRPC propagates the deadline across service hops, so every hop knows the remaining budget and the whole chain stops when the client gives up. In grpc-java, `withDeadlineAfter` fixes the absolute time when the stub is created.

**Q: What happens when the user leaves the screen mid-call?**
`viewModelScope` is cancelled, which cancels the coroutine and therefore the RPC. RST_STREAM is sent, the server coroutine is cancelled, and no work leaks. For work that must finish, such as an upload, use WorkManager instead.

**Q: How do you keep a stream alive in the background?**
Generally you don't. Android restricts background network use (Doze, App Standby). Use a foreground service only during an active trip (a user-visible notification is required), FCM high-priority push for state changes otherwise, and on resume do a unary sync and then reopen the stream.

**Q: What is backpressure in gRPC?**
HTTP/2 flow-control windows. If the receiver doesn't read, the sender's window fills and writes block. In grpc-kotlin, `emit` simply suspends. In grpc-java async, use `isReady()` / `setOnReadyHandler`.

**Q: How do you load balance gRPC?**
Long-lived HTTP/2 connections defeat L4 load balancing. Use an L7 proxy (Envoy) or client-side load balancing (round_robin / xDS), plus `maxConnectionAge` for GOAWAY-driven rebalancing.

**Q: How would you design live driver tracking at Uber scale (mobile view)?**
The driver app sends batched GPS points every few seconds (client stream or periodic unary) to an ingestion service, which writes to Kafka. A geo index (H3/S2 cells in Redis) holds current positions. The rider's `TrackDriver` stream is served by a stateless gateway that subscribes to the ride's location topic (pub/sub). The client interpolates or animates between updates, adapts frequency to distance and app state, reconnects with backoff, and falls back to push in the background.

**Q: How do you make gRPC observable?**
Interceptors for logs, metrics, and tracing (OpenTelemetry). Track the status-code distribution, p95/p99 latency per method, and app version / platform in metadata.

**Q: When would you choose WebSocket over gRPC bidi?**
Browser clients without a gRPC-Web proxy, existing WS infrastructure, or message formats that don't fit Protobuf. gRPC bidi gives typed messages, deadlines, status codes, and codegen, and on Android it's usually the better choice when the backend already speaks gRPC.

**Q: What are the downsides of gRPC on mobile?**
APK size (smaller with lite + OkHttp, still several hundred KB). Harder debugging (no Chrome devtools / Charles-style plaintext without plugins). No HTTP caching. Corporate proxies or firewalls sometimes break HTTP/2. Needs proto tooling and discipline.

### EM-level talking points
- **Schema governance:** a shared proto repo, code owners per package, `buf` in CI, a deprecation policy, and an API review process.
- **Cross-platform alignment:** iOS, Android, and backend generate from the same contract, so you get fewer integration bugs and can work in parallel against fake servers.
- **Rollout safety:** server-driven feature flags, a kill switch for new RPCs, staged rollout watching error rate by app version.
- **Developer experience:** local server and in-process tests (like this repo), grpcurl, mock servers for UI work.
- **Metrics that matter:** crash-free sessions, p95 latency of the booking flow, stream reconnect rate, data used per trip, and battery cost.

---

## 12. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `UNAVAILABLE: Unable to resolve host` / connection refused | The server isn't running, or the host is wrong. Emulator → `10.0.2.2`. Device → `adb reverse` or LAN IP. |
| `UNAVAILABLE` on a physical device over Wi-Fi | Mac firewall, or the device is on a different network. Set `grpc.host` in `local.properties` and whitelist it in `network_security_config.xml`. |
| `GOAWAY ... too_many_pings` | Client `keepAliveTime` is lower than the server's `permitKeepAliveTime`. |
| `UNAUTHENTICATED` | Missing or invalid `authorization: Bearer demo-<user>` header. |
| `NOT_FOUND Unknown ride_id` after restarting the server | Rides live in memory. Book a new ride. |
| Gradle: `grpc-kotlin-stub ... only compatible with JVM 17` | grpc-kotlin 1.5+ needs Java 17 (the app and `:proto` target 17). |
| `INSTALL_FAILED ... not enough space` | The emulator's data partition is full. Use an AVD with a larger internal storage. |
| Generated classes not found in the IDE | Build → Rebuild, or `./gradlew :proto:build`. Generated sources are in `proto/build/generated/sources/proto/main/`. |
