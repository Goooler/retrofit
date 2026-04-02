/*
 * Copyright (C) 2024 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package retrofit2.adapter.flow

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.lang.reflect.Type
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import retrofit2.Converter
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.GET

class FlowAdapterIntegrationTest {
  @get:Rule val server = MockWebServer()

  interface Service {
    @SSE
    @GET("/")
    suspend fun sseEvents(): Flow<ServerSentEvent>

    @GET("/")
    suspend fun body(): Flow<String>
  }

  private fun buildRetrofit(): Retrofit =
    Retrofit.Builder()
      .baseUrl(server.url("/"))
      .addConverterFactory(StringConverterFactory())
      .addCallAdapterFactory(FlowCallAdapterFactory.create())
      .build()

  // ---------------------------------------------------------------------------
  // SSE (suspend)
  // ---------------------------------------------------------------------------

  @Test
  fun sseEvents() {
    runBlocking {
      server.enqueue(
        MockResponse()
          .setHeader("Content-Type", "text/event-stream")
          .setBody(
            "id: 1\nevent: ping\ndata: hello\n\n" +
              "id: 2\ndata: world\n\n"
          )
      )
      val service = buildRetrofit().create(Service::class.java)
      val events = service.sseEvents().toList()
      assertThat(events)
        .containsExactly(
          ServerSentEvent(id = "1", event = "ping", data = "hello", retry = null),
          ServerSentEvent(id = "2", event = null, data = "world", retry = null),
        )
        .inOrder()
    }
  }

  @Test
  fun sseEventsMultilineData() {
    runBlocking {
      server.enqueue(
        MockResponse()
          .setHeader("Content-Type", "text/event-stream")
          .setBody("data: line one\ndata: line two\n\n")
      )
      val service = buildRetrofit().create(Service::class.java)
      val events = service.sseEvents().toList()
      assertThat(events)
        .containsExactly(
          ServerSentEvent(id = null, event = null, data = "line one\nline two", retry = null)
        )
    }
  }

  @Test
  fun sseEventsWithRetry() {
    runBlocking {
      server.enqueue(
        MockResponse()
          .setHeader("Content-Type", "text/event-stream")
          .setBody("retry: 3000\ndata: reconnect\n\n")
      )
      val service = buildRetrofit().create(Service::class.java)
      val events = service.sseEvents().toList()
      assertThat(events)
        .containsExactly(
          ServerSentEvent(id = null, event = null, data = "reconnect", retry = 3000L)
        )
    }
  }

  @Test
  fun sseEventsCommentsIgnored() {
    runBlocking {
      server.enqueue(
        MockResponse()
          .setHeader("Content-Type", "text/event-stream")
          .setBody(": this is a comment\ndata: real\n\n")
      )
      val service = buildRetrofit().create(Service::class.java)
      val events = service.sseEvents().toList()
      assertThat(events)
        .containsExactly(
          ServerSentEvent(id = null, event = null, data = "real", retry = null)
        )
    }
  }

  @Test
  fun sseEventsHttpError() {
    runBlocking {
      server.enqueue(MockResponse().setResponseCode(500))
      val service = buildRetrofit().create(Service::class.java)
      try {
        service.sseEvents().toList()
        fail("Expected HttpException")
      } catch (e: HttpException) {
        assertThat(e.code()).isEqualTo(500)
      }
    }
  }

  @Test
  fun sseEventsNetworkFailure() {
    runBlocking {
      server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
      val service = buildRetrofit().create(Service::class.java)
      try {
        service.sseEvents().toList()
        fail("Expected IOException")
      } catch (_: IOException) {
        // expected
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Non-SSE body flow (suspend)
  // ---------------------------------------------------------------------------

  @Test
  fun bodyFlow() {
    runBlocking {
      server.enqueue(MockResponse().setBody("hello"))
      val service = buildRetrofit().create(Service::class.java)
      val values = service.body().toList()
      assertThat(values).containsExactly("hello")
    }
  }

  @Test
  fun bodyFlowHttpError() {
    runBlocking {
      server.enqueue(MockResponse().setResponseCode(404))
      val service = buildRetrofit().create(Service::class.java)
      try {
        service.body().toList()
        fail("Expected HttpException")
      } catch (e: HttpException) {
        assertThat(e.code()).isEqualTo(404)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Converter factory that converts ResponseBody to String
  // ---------------------------------------------------------------------------

  private class StringConverterFactory : Converter.Factory() {
    override fun responseBodyConverter(
      type: Type,
      annotations: Array<Annotation>,
      retrofit: Retrofit,
    ): Converter<ResponseBody, *>? {
      return if (type == String::class.java) {
        Converter<ResponseBody, String> { it.string() }
      } else {
        null
      }
    }
  }
}

