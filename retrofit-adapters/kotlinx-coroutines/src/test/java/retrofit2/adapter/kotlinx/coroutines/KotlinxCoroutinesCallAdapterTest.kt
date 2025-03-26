package retrofit2.adapter.kotlinx.coroutines

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.helpers.ToStringConverterFactory
import retrofit2.http.GET
import retrofit2.http.Streaming

class KotlinxCoroutinesCallAdapterTest {

  @Rule @JvmField
  val server = MockWebServer()

  interface Service {
    @Streaming
    @GET("/answer")
    suspend fun getAnswer(): Flow<String>
  }

  @Test
  fun responseBodyStreams() = runBlocking {
    val retrofit = Retrofit.Builder()
      .baseUrl(server.url("/"))
      .addCallAdapterFactory(KotlinxCoroutinesCallAdapterFactory.create())
      .addConverterFactory(ToStringConverterFactory())
      .build()
    val service = retrofit.create(Service::class.java)

    server.enqueue(MockResponse().setBody("{\"message\":\"Hello\"}"))
    server.enqueue(MockResponse().setBody("{\"message\":\"World\"}"))

    val flow = service.getAnswer()
    val answers = mutableListOf<String>()
    flow.collect { answers.add(it) }

    assertEquals(2, answers.size)
    assertEquals(
      answers.joinToString("\n"),
      """
        {"message":"Hello"}
        {"message":"World"}
      """.trimIndent()
    )
  }
}
