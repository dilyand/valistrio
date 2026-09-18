package valistrio.core.adapters.snowplow

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

/** The fields of a Snowplow tp2 event the adapter reads. Each element of the `payload_data` `data`
  * array is a flat JSON object of string fields; only these are needed to build the valistrio event
  * document. A self-describing event (`e` = "ue") carries its body in `ue_pr` (plain JSON) or
  * `ue_px` (base64url); a page view (`e` = "pv") carries atomic `url`/`page`/`refr` instead.
  * Contexts ride `co` (plain) or `cx` (base64url). The tracker's transport/enrichment fields (`tv`,
  * `p`, `aid`, `tna`, `stm`, …) are not part of any schema, so they are not read.
  *
  * Field names mirror the wire keys so the derived decoder maps them directly.
  */
final case class SnowplowEvent(
  e: Option[String],
  eid: Option[String],
  dtm: Option[String],
  ue_pr: Option[String],
  ue_px: Option[String],
  co: Option[String],
  cx: Option[String],
  url: Option[String],
  page: Option[String],
  refr: Option[String]
)

object SnowplowEvent {
  implicit val decoder: Decoder[SnowplowEvent] = deriveDecoder
}
