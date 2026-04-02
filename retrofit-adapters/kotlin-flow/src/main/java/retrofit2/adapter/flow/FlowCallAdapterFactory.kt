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

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.ResponseBody
import okio.Timeout
import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.Callback
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit

/**
 * A [CallAdapter.Factory] that supports [Flow] as a service-method return type.
 *
 * ## SSE (Server-Sent Events)
 *
 * When the method is also annotated with [@SSE][SSE], the adapter streams the HTTP response body as
 * Server-Sent Events, emitting each parsed [ServerSentEvent] to the flow:
 *
 * ```kotlin
 * interface Api {
 *   @SSE
 *   @GET("events")
 *   fun events(): Flow<ServerSentEvent>
 *
 *   // suspend is also supported
 *   @SSE
 *   @GET("events")
 *   suspend fun eventsAsync(): Flow<ServerSentEvent>
 * }
 * ```
 *
 * Register this factory with [Retrofit.Builder.addCallAdapterFactory]:
 *
 * ```kotlin
 * val retrofit = Retrofit.Builder()
 *   .baseUrl(baseUrl)
 *   .addCallAdapterFactory(FlowCallAdapterFactory.create())
 *   .build()
 * ```
 *
 * ## Non-SSE flows
 *
 * Without [@SSE][SSE], a `Flow<T>` return type will emit a single converted response body (like a
 * regular body call) and complete, or fail with [HttpException] / [java.io.IOException] as appropriate.
 *
 * ```kotlin
 * interface Api {
 *   @GET("user")
 *   fun getUser(): Flow<String>
 * }
 * ```
 */
class FlowCallAdapterFactory private constructor() : CallAdapter.Factory() {

  companion object {
    @JvmStatic
    fun create(): FlowCallAdapterFactory = FlowCallAdapterFactory()
  }

  override fun get(
    returnType: Type,
    annotations: Array<Annotation>,
    retrofit: Retrofit,
  ): CallAdapter<*, *>? {
    val isSse = annotations.any { it is SSE }

    // Non-suspend: fun foo(): Flow<T>
    if (getRawType(returnType) == Flow::class.java) {
      if (returnType !is ParameterizedType) {
        throw IllegalStateException(
          "Flow return type must be parameterized as Flow<Foo> or Flow<? extends Foo>"
        )
      }
      val elementType = getParameterUpperBound(0, returnType)
      // For SSE the adapter reads raw bytes itself; for a regular body call the registered
      // converter handles deserialization, so we expose the element type directly.
      val responseType: Type = if (isSse) ResponseBody::class.java else elementType
      @Suppress("UNCHECKED_CAST")
      return BodyFlowCallAdapter<Any>(responseType, isSse) as CallAdapter<*, *>
    }

    // Suspend: suspend fun foo(): Flow<T>
    // Retrofit wraps the continuation return type in Call<T>, so the adapter type seen here
    // is Call<Flow<T>>.
    if (getRawType(returnType) == Call::class.java) {
      if (returnType !is ParameterizedType) return null
      val callType = getParameterUpperBound(0, returnType)
      if (getRawType(callType) != Flow::class.java) return null
      if (callType !is ParameterizedType) {
        throw IllegalStateException(
          "Flow return type must be parameterized as Flow<Foo> or Flow<? extends Foo>"
        )
      }
      val elementType = getParameterUpperBound(0, callType)
      val responseType: Type = if (isSse) ResponseBody::class.java else elementType
      @Suppress("UNCHECKED_CAST")
      return SuspendFlowCallAdapter<Any>(responseType, isSse) as CallAdapter<*, *>
    }

    return null
  }
}

// ---------------------------------------------------------------------------
// Non-suspend adapter: adapt(Call<R>) → Flow<R>  (or Flow<ServerSentEvent> for SSE)
// ---------------------------------------------------------------------------

private class BodyFlowCallAdapter<R>(
  private val responseType_: Type,
  private val isSse: Boolean,
) : CallAdapter<R, Flow<*>> {

  override fun responseType(): Type = responseType_

  override fun adapt(call: Call<R>): Flow<*> {
    return if (isSse) {
      @Suppress("UNCHECKED_CAST")
      sseFlow(call as Call<ResponseBody>)
    } else {
      bodyFlow(call)
    }
  }
}

// ---------------------------------------------------------------------------
// Suspend adapter: adapt(Call<R>) → Call<Flow<R>>  (or Call<Flow<ServerSentEvent>> for SSE)
//
// Retrofit's SuspendForBody calls callAdapter.adapt(call) expecting a Call<ResponseT>, then
// calls KotlinExtensions.await() on it. We return a lightweight wrapper Call that, when
// enqueued, immediately delivers a cold Flow as the response body without starting the HTTP
// request yet. The actual HTTP request is deferred until the flow is collected.
// ---------------------------------------------------------------------------

private class SuspendFlowCallAdapter<R>(
  private val responseType_: Type,
  private val isSse: Boolean,
) : CallAdapter<R, Call<Flow<*>>> {

  override fun responseType(): Type = responseType_

  override fun adapt(call: Call<R>): Call<Flow<*>> {
    val flow: Flow<*> =
      if (isSse) {
        @Suppress("UNCHECKED_CAST")
        sseFlow(call as Call<ResponseBody>)
      } else {
        bodyFlow(call)
      }
    return FlowAsCall(call, flow)
  }
}

/**
 * A [Call] whose "response body" is a pre-built cold [Flow]. When enqueued it immediately
 * delivers the flow to the callback so that Retrofit's suspend machinery can resume the coroutine
 * with the flow value. The HTTP request is only started when the returned flow is collected.
 */
private class FlowAsCall<R>(
  private val delegate: Call<R>,
  private val flow: Flow<*>,
) : Call<Flow<*>> {

  override fun enqueue(callback: Callback<Flow<*>>) {
    callback.onResponse(this, Response.success(flow))
  }

  override fun execute(): Response<Flow<*>> = Response.success(flow)

  override fun isExecuted(): Boolean = delegate.isExecuted

  override fun cancel() = delegate.cancel()

  override fun isCanceled(): Boolean = delegate.isCanceled

  override fun clone(): Call<Flow<*>> = FlowAsCall(delegate.clone(), flow)

  override fun request(): okhttp3.Request = delegate.request()

  override fun timeout(): Timeout = delegate.timeout()
}

// ---------------------------------------------------------------------------
// Flow builders
// ---------------------------------------------------------------------------

/**
 * Returns a cold [Flow] that, when collected, makes the HTTP call and emits each parsed
 * [ServerSentEvent] from the response body stream. The HTTP connection is closed when the
 * stream ends or the flow is cancelled.
 *
 * The SSE response body is read on [Dispatchers.IO] so that blocking IO does not tie up the
 * caller's coroutine dispatcher.
 */
private fun sseFlow(call: Call<ResponseBody>): Flow<ServerSentEvent> = callbackFlow {
  val scope: CoroutineScope = this
  val channel: SendChannel<ServerSentEvent> = this

  call.clone().enqueue(
    object : Callback<ResponseBody> {
      override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
        if (!response.isSuccessful) {
          channel.close(HttpException(response))
          return
        }
        val body = response.body()
        if (body == null) {
          channel.close()
          return
        }
        // Read the SSE stream on an IO thread so that blocking reads do not block the
        // coroutine dispatcher. `send` suspends when the consumer is slow, providing
        // natural backpressure.
        scope.launch(Dispatchers.IO) {
          try {
            body.use { responseBody ->
              val reader = responseBody.charStream().buffered()
              for (event in parseServerSentEvents(reader)) {
                channel.send(event)
              }
            }
            channel.close()
          } catch (e: Exception) {
            channel.close(e)
          }
        }
      }

      override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
        channel.close(t)
      }
    }
  )

  awaitClose { call.cancel() }
}

/**
 * Returns a cold [Flow] that, when collected, makes the HTTP call, emits the single converted
 * response body, and completes. Errors result in [HttpException] or [java.io.IOException].
 */
private fun <R> bodyFlow(call: Call<R>): Flow<R> = callbackFlow {
  call.clone().enqueue(
    object : Callback<R> {
      override fun onResponse(call: Call<R>, response: Response<R>) {
        if (!response.isSuccessful) {
          close(HttpException(response))
          return
        }
        val body = response.body()
        if (body == null) {
          close()
          return
        }
        trySend(body)
        close()
      }

      override fun onFailure(call: Call<R>, t: Throwable) {
        close(t)
      }
    }
  )

  awaitClose { call.cancel() }
}
