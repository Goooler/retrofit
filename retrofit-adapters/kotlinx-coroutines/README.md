Kotlinx Coroutines Adapter
==============

An `Adapter` for adapting [kotlinx.coroutines][1] types.

Available types:

 * `Flow<T>`, and `Flow<Response<T>>`, where `T` is the body type.


Usage
-----

Add `KotlinxCoroutinesCallAdapterFactory` as a `Call` adapter when building your `Retrofit` instance:
```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://example.com/")
    .addCallAdapterFactory(KotlinxCoroutinesCallAdapterFactory.create())
    .build()
```

Your service methods can now use any of the above types as their return type.
```kotlin
interface MyService {
  @Streaming
  @GET("/answer")
  suspend fun getAnswer(): Flow<Answer>
}
```


Download
--------

Download [the latest JAR][2] or grab via [Maven][3]:
```xml
<dependency>
  <groupId>com.squareup.retrofit2</groupId>
  <artifactId>adapter-kotlinx-coroutines</artifactId>
  <version>latest.version</version>
</dependency>
```
or [Gradle][3]:
```groovy
implementation 'com.squareup.retrofit2:adapter-kotlinx-coroutines:latest.version'
```

Snapshots of the development version are available in [Sonatype's `snapshots` repository][snap].



 [1]: https://github.com/Kotlin/kotlinx.coroutines
[2]: https://search.maven.org/remote_content?g=com.squareup.retrofit2&a=adapter-kotlinx-coroutines&v=LATEST
[3]: https://search.maven.org/search?q=g:com.squareup.retrofit2%20a:adapter-kotlinx-coroutines
[snap]: https://s01.oss.sonatype.org/content/repositories/snapshots/
