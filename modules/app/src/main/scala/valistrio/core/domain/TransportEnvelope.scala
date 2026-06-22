package valistrio.core.domain

import cats.data.NonEmptyList
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.syntax._

/** The decoded contents of the envelope's "data" field. */
final case class EnvelopeData(
  meta: EventMeta,
  event: TypedPayload,
  contexts: Option[NonEmptyList[TypedPayload]]
)

/** The full request body for /validate and future /post:
  * a self-describing JSON whose schema is "com.valistrio/envelope/x.y.z"
  * and whose data is an [[EnvelopeData]].
  */
final case class TransportEnvelope(schema: SchemaName, data: EnvelopeData)

object TransportEnvelope {

  private val EnvelopeDataAllowedKeys  = Set("meta", "event", "contexts")
  private val EnvelopeAllowedKeys      = Set("schema", "data")

  implicit val envelopeDataDecoder: Decoder[EnvelopeData] = Decoder.instance { c =>
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
      event    <- c.downField("event").as[TypedPayload]
      contexts <- c.downField("contexts").as[Option[List[TypedPayload]]].flatMap {
        case None => Right(None)
        case Some(Nil) =>
          Left(DecodingFailure("'contexts' must be a non-empty array if present", c.downField("contexts").history))
        case Some(head :: tail) =>
          Right(Some(NonEmptyList(head, tail)))
      }
    } yield EnvelopeData(meta, event, contexts)
  }

  implicit val transportEnvelopeDecoder: Decoder[TransportEnvelope] = Decoder.instance { c =>
    for {
      obj <- c.value.asObject.toRight(
        DecodingFailure("Expected a JSON object for the transport envelope", c.history)
      )
      _ <- {
        val unknown = obj.keys.filterNot(EnvelopeAllowedKeys)
        if (unknown.isEmpty) Right(())
        else Left(DecodingFailure(s"Unknown field(s) in transport envelope: ${unknown.mkString(", ")}", c.history))
      }
      schema <- c.downField("schema").as[SchemaName]
      data   <- c.downField("data").as[EnvelopeData]
    } yield TransportEnvelope(schema, data)
  }

  implicit val envelopeDataEncoder: Encoder[EnvelopeData] = Encoder.instance { data =>
    Json.obj(
      "meta"     -> data.meta.asJson,
      "event"    -> data.event.asJson,
      "contexts" -> data.contexts.map(_.toList).asJson
    )
  }

  implicit val transportEnvelopeEncoder: Encoder[TransportEnvelope] = Encoder.instance { envelope =>
    Json.obj(
      "schema" -> envelope.schema.asJson,
      "data"   -> envelope.data.asJson
    )
  }
}
