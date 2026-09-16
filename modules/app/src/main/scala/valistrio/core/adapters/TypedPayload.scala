package valistrio.core.adapters

import io.circe.{Encoder, Json}
import io.circe.syntax._
import valistrio.core.domain.SchemaRef

/** A schema-tagged payload an adapter assembles from a producer's wire event: a parsed
  * [[SchemaRef]] paired with its data. Distinct from [[valistrio.core.domain.TypedData]], which
  * only ever comes from JSON that has already passed the event schema — this one is built from
  * untrusted producer input and is validated downstream by the pipeline like any other `/post` body.
  */
final case class TypedPayload(schema: SchemaRef, data: Json)

object TypedPayload {
  implicit val encoder: Encoder[TypedPayload] =
    Encoder.instance(p => Json.obj("schema" -> p.schema.asJson, "data" -> p.data))
}
