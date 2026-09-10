package valistrio.core.domain

import cats.data.NonEmptyList
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.syntax._

/** The decoded contents of the envelope's "data" field. */
final case class EventData(
  meta: EventMeta,
  event: TypedData,
  contexts: Option[NonEmptyList[TypedData]]
)

/** The full request body for /validate and future /post:
  * a self-describing JSON whose schema is "com.valistrio/envelope/x.y.z"
  * and whose data is an [[EventData]].
  */
final case class Event(schema: SchemaRef, data: EventData)

object Event {

  private val EnvelopeDataAllowedKeys  = Set("meta", "event", "contexts")
  private val EnvelopeAllowedKeys      = Set("schema", "data")

  implicit val envelopeDataDecoder: Decoder[EventData] = Decoder.instance { c =>
    for {
      obj <- c.value.asObject.toRight(
        DecodingFailure("Expected a JSON object for envelope data", c.history)
      )
      _ <- {
        val unknown = obj.keys.filterNot(EnvelopeDataAllowedKeys)
        if (unknown.isEmpty) Right(())
        else Left(DecodingFailure(s"Unknown field(s) in envelope data: ${unknown.mkString(", ")}", c.history))
      }
      meta     <- c.downField("meta").as[EventMeta]
      event    <- c.downField("event").as[TypedData]
      contexts <- c.downField("contexts").as[Option[List[TypedData]]].flatMap {
        case None => Right(None)
        case Some(Nil) =>
          Left(DecodingFailure("'contexts' must be a non-empty array if present", c.downField("contexts").history))
        case Some(head :: tail) =>
          Right(Some(NonEmptyList(head, tail)))
      }
    } yield EventData(meta, event, contexts)
  }

  implicit val transportEnvelopeDecoder: Decoder[Event] = Decoder.instance { c =>
    for {
      obj <- c.value.asObject.toRight(
        DecodingFailure("Expected a JSON object for the transport envelope", c.history)
      )
      _ <- {
        val unknown = obj.keys.filterNot(EnvelopeAllowedKeys)
        if (unknown.isEmpty) Right(())
        else Left(DecodingFailure(s"Unknown field(s) in transport envelope: ${unknown.mkString(", ")}", c.history))
      }
      schema <- c.downField("schema").as[SchemaRef]
      data   <- c.downField("data").as[EventData]
    } yield Event(schema, data)
  }

  implicit val envelopeDataEncoder: Encoder[EventData] = Encoder.instance { data =>
    Json.obj(
      "meta"     -> data.meta.asJson,
      "event"    -> data.event.asJson,
      "contexts" -> data.contexts.map(_.toList).asJson
    )
  }

  implicit val transportEnvelopeEncoder: Encoder[Event] = Encoder.instance { envelope =>
    Json.obj(
      "schema" -> envelope.schema.asJson,
      "data"   -> envelope.data.asJson
    )
  }
}
