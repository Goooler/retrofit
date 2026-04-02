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
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.GET

class FlowCallAdapterFactoryTest {
  private val factory = FlowCallAdapterFactory.create()
  private val retrofit =
    Retrofit.Builder()
      .baseUrl("http://localhost:1/")
      .addCallAdapterFactory(factory)
      .build()

  // Interface used to extract the real @SSE annotation via reflection.
  interface SseHelper {
    @SSE
    @GET("/")
    suspend fun events(): Flow<ServerSentEvent>
  }

  @Test
  fun nonFlowTypeReturnsNull() {
    val adapter = factory.get(String::class.java, emptyArray(), retrofit)
    assertThat(adapter).isNull()
  }

  /** Non-suspend Flow<T> must not be handled — the factory should return null. */
  @Test
  fun nonSuspendFlowTypeReturnsNull() {
    val adapter = factory.get(flowOf(String::class.java), emptyArray(), retrofit)
    assertThat(adapter).isNull()
  }

  /** suspend fun foo(): Flow<T> → responseType is the element type T. */
  @Test
  fun suspendFlowResponseTypeIsElementType() {
    val type = callOf(flowOf(String::class.java))
    val adapter = factory.get(type, emptyArray(), retrofit)!!
    assertThat(adapter.responseType()).isEqualTo(String::class.java)
  }

  /** suspend fun foo(): Flow<T> with @SSE → responseType is ResponseBody. */
  @Test
  fun suspendFlowSseResponseTypeIsResponseBody() {
    val type = callOf(flowOf(ServerSentEvent::class.java))
    val adapter = factory.get(type, sseAnnotations(), retrofit)!!
    assertThat(adapter.responseType()).isEqualTo(okhttp3.ResponseBody::class.java)
  }

  /** Unparameterized Flow inside Call should throw. */
  @Test
  fun rawFlowInsideCallThrows() {
    val type = callOf(Flow::class.java)
    try {
      factory.get(type, emptyArray(), retrofit)
      fail()
    } catch (e: IllegalStateException) {
      assertThat(e).hasMessageThat().contains("parameterized")
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Extracts annotations (including [@SSE][SSE]) from [SseHelper.events] for use in tests. */
  private fun sseAnnotations(): Array<Annotation> =
    SseHelper::class.java
      .getMethod("events", kotlin.coroutines.Continuation::class.java)
      .annotations
      .filterIsInstance<Annotation>()
      .toTypedArray()

  private fun flowOf(type: Type): Type =
    object : ParameterizedType {
      override fun getActualTypeArguments(): Array<Type> = arrayOf(type)

      override fun getRawType(): Type = Flow::class.java

      override fun getOwnerType(): Type? = null
    }

  /** Wraps [innerType] in Call<innerType>, matching what Retrofit presents for suspend functions. */
  private fun callOf(innerType: Type): Type =
    object : ParameterizedType {
      override fun getActualTypeArguments(): Array<Type> = arrayOf(innerType)

      override fun getRawType(): Type = Call::class.java

      override fun getOwnerType(): Type? = null
    }
}

