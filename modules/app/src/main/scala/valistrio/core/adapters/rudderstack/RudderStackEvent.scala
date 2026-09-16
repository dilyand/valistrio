package valistrio.core.adapters.rudderstack

import io.circe.{Decoder, JsonObject}
import io.circe.generic.semiauto.deriveDecoder

/** The fields of a RudderStack data-plane event the adapter reads. The browser SDK POSTs one event
  * per request to `/v1/<type>` as a flat JSON object; only these are needed to build the valistrio
  * event document — `type`, `event`, `context`, `anonymousId`, `sentAt` and the rest are ignored.
  *
  * The producer carries the valistrio body schema ref as `properties.schema` and any contexts as
  * `properties.contexts`; [[RudderStackMapper]] lifts both out of `properties`.
  */
final case class RudderStackEvent(
  properties: Option[JsonObject],
  messageId: Option[String],
  originalTimestamp: Option[String]
)

object RudderStackEvent {
  implicit val decoder: Decoder[RudderStackEvent] = deriveDecoder
}
