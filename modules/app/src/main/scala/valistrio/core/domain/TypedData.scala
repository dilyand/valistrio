package valistrio.core.domain

import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import io.circe.syntax._

/** A self-describing payload: a schema reference paired with the data it describes.
  *
  * Structural contract (enforced by the decoder):
  *  - exactly two keys: "schema" and "data"
  *  - unknown fields are rejected (structural error, not a schema validation failure)
  *  - "data" must be a JSON object
  */
final case class TypedData(schema: SchemaRef, data: Json)

object TypedData {

  private val AllowedKeys = Set("schema", "data")

  implicit val decoder: Decoder[TypedData] = Decoder.instance { c =>
    for {
      obj <- c.value.asObject.toRight(
        DecodingFailure("Expected a JSON object for TypedData", c.history)
      )
      _ <- {
        val unknown = obj.keys.filterNot(AllowedKeys)
        if (unknown.isEmpty) Right(())
        else Left(DecodingFailure(s"Unknown field(s) in TypedData: ${unknown.mkString(", ")}", c.history))
      }
      schema <- c.downField("schema").as[SchemaRef]
      data   <- c.downField("data").as[Json].flatMap { json =>
        if (json.isObject) Right(json)
        else Left(DecodingFailure("'data' must be a JSON object", c.downField("data").history))
      }
    } yield TypedData(schema, data)
  }

  implicit val encoder: Encoder[TypedData] = Encoder.instance { payload =>
    Json.obj(
      "schema" -> payload.schema.asJson,
      "data"   -> payload.data
    )
  }
}
