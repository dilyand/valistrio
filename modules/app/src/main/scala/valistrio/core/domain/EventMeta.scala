package valistrio.core.domain

import io.circe.{Decoder, Encoder, Json}

/** Producer-supplied metadata for an event occurrence.
  *
  * The decoder is intentionally lenient about unknown fields: the structural shape
  * and format constraints (UUID, ISO-8601) are enforced by the registry-stored
  * "com.valistrio/meta/x.y.z" JSON Schema. The Circe decoder only extracts the
  * fields the application currently needs, so that new fields can be added to the
  * meta schema without requiring a code change here.
  *
  * @param eventId    producer-generated stable event identifier (UUID format)
  * @param producedAt producer-supplied timestamp measured at event creation time (ISO-8601 format)
  */
final case class EventMeta(eventId: String, producedAt: String)

object EventMeta {

  implicit val decoder: Decoder[EventMeta] = Decoder.instance { c =>
    for {
      eventId    <- c.downField("event_id").as[String]
      producedAt <- c.downField("produced_at").as[String]
    } yield EventMeta(eventId, producedAt)
  }

  implicit val encoder: Encoder[EventMeta] = Encoder.instance { meta =>
    Json.obj(
      "event_id"    -> Json.fromString(meta.eventId),
      "produced_at" -> Json.fromString(meta.producedAt)
    )
  }
}
