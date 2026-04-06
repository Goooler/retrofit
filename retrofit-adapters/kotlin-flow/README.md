Kotlin Flow Adapter
===================

A `CallAdapter.Factory` for adapting [Kotlin coroutine `Flow`][1] return types in Retrofit `suspend`
service methods.

Supported return types:

* `Flow<T>` — emits the single converted response body and completes.
* `Flow<ServerSentEvent>` (with `@Streaming`) — streams Server-Sent Events from the response body,
  emitting one `ServerSentEvent` per event.


Usage
-----

Add `FlowCallAdapterFactory` as a call adapter when building your `Retrofit` instance:

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addCallAdapterFactory(FlowCallAdapterFactory.create())
    .build()
```

### Regular body flow

Annotate a `suspend` service method with any Retrofit HTTP annotation and return `Flow<T>`. The flow
emits the single converted response body when collected, then completes. On a non-2xx response or a
network failure the flow fails with `HttpException` or `IOException` respectively.

```kotlin
interface MyService {
    @GET("/user")
    suspend fun getUser(): Flow<User>
}
```

### Server-Sent Events (SSE)

Add `@Streaming` to stream a response as [Server-Sent Events][2]. The return type must be
`Flow<ServerSentEvent>`. The flow emits one `ServerSentEvent` for each event dispatched by the
server and completes when the connection is closed. Cancelling the flow cancels the underlying
OkHttp `EventSource`.

```kotlin
interface MyService {
    @Streaming
    @GET("/events")
    suspend fun events(): Flow<ServerSentEvent>
}
```

`ServerSentEvent` exposes the fields defined by the [W3C SSE specification][2]:

| Property | Type      | Description                                                |
| -------- | --------- | ---------------------------------------------------------- |
| `id`     | `String?` | Last event ID, or `null` if not set.                       |
| `event`  | `String?` | Event type, or `null` for the default `"message"` type.    |
| `data`   | `String`  | Data payload; multiple `data:` lines are joined with `\n`. |

Parsing and connection management are delegated to OkHttp's `okhttp-sse` library. The `Accept:
text/event-stream` header is added automatically.


Download
--------

Download [the latest JAR][3] or grab via [Maven][4]:

```xml
<dependency>
  <groupId>com.squareup.retrofit2</groupId>
  <artifactId>adapter-kotlin-flow</artifactId>
  <version>latest.version</version>
</dependency>
```

or [Gradle][4]:

```kotlin
implementation("com.squareup.retrofit2:adapter-kotlin-flow:latest.version")
```

Snapshots of the development version are available in [Sonatype's `snapshots` repository][snap].



 [1]: https://kotlinlang.org/docs/flow.html
 [2]: https://html.spec.whatwg.org/multipage/server-sent-events.html
 [3]: https://search.maven.org/remote_content?g=com.squareup.retrofit2&a=adapter-kotlin-flow&v=LATEST
 [4]: https://search.maven.org/search?q=g:com.squareup.retrofit2%20a:adapter-kotlin-flow
 [snap]: https://s01.oss.sonatype.org/content/repositories/snapshots/
