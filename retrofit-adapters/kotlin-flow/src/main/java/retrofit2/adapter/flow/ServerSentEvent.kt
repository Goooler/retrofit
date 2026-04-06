/*
 * Copyright (C) 2026 Square, Inc.
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

/**
 * Represents a single Server-Sent Event as defined by the
 * [W3C SSE specification](https://html.spec.whatwg.org/multipage/server-sent-events.html).
 *
 * @property id The last event ID, or `null` if none was set.
 * @property event The event type, or `null` if the default "message" type.
 * @property data The data payload. Multiple `data:` lines are joined with `\n`.
 */
data class ServerSentEvent(val id: String?, val event: String?, val data: String)
