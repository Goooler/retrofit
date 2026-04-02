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

import java.io.BufferedReader

/**
 * Parses a stream of text lines into [ServerSentEvent] objects following the
 * [W3C SSE specification](https://html.spec.whatwg.org/multipage/server-sent-events.html).
 */
internal fun parseServerSentEvents(reader: BufferedReader): Sequence<ServerSentEvent> = sequence {
  var id: String? = null
  var event: String? = null
  val dataBuffer = StringBuilder()
  var retry: Long? = null
  var hasData = false

  for (line in reader.lineSequence()) {
    if (line.isEmpty()) {
      // An empty line dispatches the event.
      if (hasData) {
        // Remove the trailing newline that was appended after the last data line.
        val data = if (dataBuffer.endsWith('\n')) dataBuffer.dropLast(1).toString() else dataBuffer.toString()
        yield(ServerSentEvent(id = id, event = event, data = data, retry = retry))
      }
      // Reset fields for the next event (id persists per spec, but retry is per-event).
      event = null
      dataBuffer.clear()
      retry = null
      hasData = false
    } else if (line.startsWith(':')) {
      // Comment line — ignore.
    } else {
      val colonIndex = line.indexOf(':')
      val field: String
      val value: String
      if (colonIndex == -1) {
        field = line
        value = ""
      } else {
        field = line.substring(0, colonIndex)
        // If the character immediately after `:` is a space, skip it.
        value = if (colonIndex + 1 < line.length && line[colonIndex + 1] == ' ') {
          line.substring(colonIndex + 2)
        } else {
          line.substring(colonIndex + 1)
        }
      }
      when (field) {
        "data" -> {
          dataBuffer.append(value).append('\n')
          hasData = true
        }
        "id" -> id = value
        "event" -> event = value
        "retry" -> retry = value.toLongOrNull()
      }
    }
  }
}
