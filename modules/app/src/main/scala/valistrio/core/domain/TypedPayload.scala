package valistrio.core.domain

import io.circe.{Decoder, DecodingFailure, Json}

/** A self-describing payload: a schema reference paired with the data it describes.
  *
  * Structural contract (enforced by the decoder):
  *  - exactly two keys: "schema" and "data"
  *  - unknown fields are rejected (structural error, not a schema validation failure)
  *  - "data" must be a JSON object
  */
final case class TypedPayload(schema: SchemaName, data: Json)

object TypedPayload {

  private val AllowedKeys = Set("schema", "data")

  implicit val decoder: Decoder[TypedPayload] = Decoder.instance { c =>
    for {
      obj <- c.value.asObject.toRight(
        DecodingFailure("Expected a JSON object for TypedPayload", c.history)
      )
      _ <- {
        val unknown = obj.keys.filterNot(AllowedKeys)
        if (unknown.isEmpty) Right(())
        else Left(DecodingFailure(s"Unknown field(s) in TypedPayload: ${unknown.mkString(", ")}", c.history))
      }
      schema <- c.downField("schema").as[SchemaName]
      data   <- c.downField("data").as[Json].flatMap { json =>
        if (json.isObject) Right(json)
        else Left(DecodingFailure("'data' must be a JSON object", c.downField("data").history))
      }
    } yield TypedPayload(schema, data)
  }
}
