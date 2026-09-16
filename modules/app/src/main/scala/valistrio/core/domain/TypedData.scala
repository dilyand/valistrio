package valistrio.core.domain

import io.circe.{Decoder, Json}
import io.circe.generic.semiauto.deriveDecoder

/** A schema-tagged datum: a [[SchemaRef]] paired with the data it describes.
  *
  * The structural contract (exactly `schema`/`data`, `data` is an object, ref shape) is owned by
  * the event schema, not this decoder. A [[TypedData]] is only ever produced by extracting it from
  * JSON that has already passed that schema, so the derived decoder is a lenient navigator, not a
  * validator, and its existence means the pairing was sound.
  */
final case class TypedData private (schema: SchemaRef, data: Json)

object TypedData {
  implicit val decoder: Decoder[TypedData] = deriveDecoder[TypedData]
}
