package valistrio.core.domain

import cats.data.NonEmptyList
import io.circe.{Decoder, Json}

/** The `data` object of an event: producer `meta`, the primary `body` payload, and
  * optional `contexts`. `meta` stays raw JSON — its shape is owned by the event schema,
  * and it is written to the sink verbatim, so nothing is normalised away here.
  */
final case class EventData private (
  meta: Json,
  body: TypedData,
  contexts: Option[NonEmptyList[TypedData]]
)

/** A structurally-valid event: the faithful original `json` alongside a navigable
  * projection that mirrors the wire shape (`event.data.body`, `event.data.contexts`, …).
  *
  * Built only from JSON that has already passed the event schema, so its fields are
  * guaranteed present. A failure to extract means the schema and this navigation
  * disagree — a bug, not client input.
  */
final case class Event private (json: Json, schema: SchemaRef, data: EventData)

object Event {

  implicit private val eventDataDecoder: Decoder[EventData] = Decoder.instance { c =>
    for {
      meta     <- c.get[Json]("meta")
      body     <- c.get[TypedData]("body")
      contexts <- c.get[Option[List[TypedData]]]("contexts").map(_.flatMap(NonEmptyList.fromList))
    } yield EventData(meta, body, contexts)
  }

  def fromJson(json: Json): Either[String, Event] =
    (for {
      schema <- json.hcursor.get[SchemaRef]("schema")
      data   <- json.hcursor.get[EventData]("data")
    } yield Event(json, schema, data)).left.map(_.getMessage)
}
