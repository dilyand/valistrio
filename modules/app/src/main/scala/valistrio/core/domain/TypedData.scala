package valistrio.core.domain

import io.circe.{Decoder, Json}

/** A schema-tagged datum: a [[SchemaRef]] paired with the data it describes.
  *
  * The structural contract (exactly `schema`/`data`, `data` is an object, ref shape) is
  * owned by the event schema, not this decoder. A [[TypedData]] is only ever produced by
  * extracting it from JSON that has already passed that schema, so it is a lenient
  * navigator, not a validator, and its existence means the pairing was sound.
  */
final case class TypedData private (schema: SchemaRef, data: Json)

object TypedData {
  implicit val decoder: Decoder[TypedData] = Decoder.instance { c =>
    for {
      schema <- c.get[SchemaRef]("schema")
      data   <- c.get[Json]("data")
    } yield TypedData(schema, data)
  }
}
