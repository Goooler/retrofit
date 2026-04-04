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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.Timeout
import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.Callback
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Streaming

/**
 * A [CallAdapter.Factory] that supports [Flow] as a **suspend** service-method return type.
 *
 * ## SSE (Server-Sent Events)
 *
 * When the method is also annotated with [@Streaming][retrofit2.http.Streaming], the adapter
 * streams the HTTP response body as Server-Sent Events, emitting each parsed [ServerSentEvent] to
 * the flow:
 *
 * ```kotlin
 * interface Api {
 *   @Streaming
 *   @GET("events")
 *   suspend fun events(): Flow<ServerSentEvent>
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
 * Without [@Streaming][retrofit2.http.Streaming], a `suspend fun foo(): Flow<T>` return type will
 * emit a single converted response body (like a regular body call) and complete, or fail with
 * [HttpException] / [java.io.IOException] as appropriate.
 *
 * ```kotlin
 * interface Api {
 *   @GET("user")
 *   suspend fun getUser(): Flow<String>
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
    val isSse = annotations.any { it is Streaming }

    // Only support suspend functions: suspend fun foo(): Flow<T>
    // Retrofit wraps the continuation return type in Call<T>, so the adapter type seen here
    // is Call<Flow<T>>.
    if (getRawType(returnType) != Call::class.java) return null
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
    val eventSourceFactory = if (isSse) EventSources.createFactory(retrofit.callFactory()) else null
    @Suppress("UNCHECKED_CAST")
    return SuspendFlowCallAdapter<Any>(responseType, isSse, eventSourceFactory) as CallAdapter<*, *>
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
  private val eventSourceFactory: EventSource.Factory?,
) : CallAdapter<R, Call<Flow<*>>> {

  override fun responseType(): Type = responseType_

  override fun adapt(call: Call<R>): Call<Flow<*>> {
    val flow: Flow<*> =
      if (isSse) {
        sseFlow(call.request(), eventSourceFactory!!)
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
 * Returns a cold [Flow] that, when collected, opens an OkHttp [EventSource] for the given
 * [request] and emits each parsed [ServerSentEvent]. The connection is closed when the stream ends
 * or the flow is cancelled.
 */
private fun sseFlow(
  request: Request,
  eventSourceFactory: EventSource.Factory,
): Flow<ServerSentEvent> = callbackFlow {
  val eventSource =
    eventSourceFactory.newEventSource(
      request,
      object : EventSourceListener() {
        override fun onEvent(
          eventSource: EventSource,
          id: String?,
          type: String?,
          data: String,
        ) {
          trySend(ServerSentEvent(id = id, event = type, data = data))
        }

        override fun onClosed(eventSource: EventSource) {
          close()
        }

        override fun onFailure(
          eventSource: EventSource,
          t: Throwable?,
          response: okhttp3.Response?,
        ) {
          close(
            t
              ?: response?.let {
                HttpException(Response.error<Nothing>(it.body, it))
              }
          )
        }
      },
    )
  awaitClose { eventSource.cancel() }
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

