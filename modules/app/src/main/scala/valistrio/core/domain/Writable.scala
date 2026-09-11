package valistrio.core.domain

import io.circe.Json

/** A keyed JSON document a sink writes to a topic: the record `key` (the event id when known)
  * and the JSON `json` body. Both the validated event and the packaged failure are writable, so
  * one sink can carry either — the topic it writes to is fixed at construction.
  */
trait Writable {
  def key: Option[String]
  def json: Json
}
