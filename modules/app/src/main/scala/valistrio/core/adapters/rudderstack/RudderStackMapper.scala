package valistrio.core.adapters.rudderstack

import cats.syntax.either._
import cats.syntax.traverse._
import io.circe.{Json, JsonObject}
import valistrio.core.adapters.EventFromThirdParty
import valistrio.core.domain.SchemaRef

/** Maps a decoded [[RudderStackEvent]] into a valistrio event document.
  *
  * The producer packs everything the schemas require into `properties`: the body schema ref as
  * `properties.schema`, any contexts as `properties.contexts` (an array of `{schema, data}`), and
  * the event's own fields as the rest of `properties`. This lifts the two ref-carrying keys out and
  * forwards the remaining properties as the body data — a faithful format-to-format map, not a
  * projection. `messageId` becomes the idempotency key (`event_id`) and `originalTimestamp` the
  * `produced_at`; each falls back to a caller-supplied default when the producer omits it. The SDK's
  * own transport fields (`context`, `anonymousId`, `sentAt`, …) sit outside `properties` and are not
  * part of any schema, so they are not carried.
  *
  * A missing/invalid schema ref or malformed contexts is a `Left`: the caller returns it as a 400,
  * which the RudderStack SDK drops (4xx bar 429 are non-retryable) rather than retrying a
  * permanently-bad request.
  */
object RudderStackMapper {

  def map(event: RudderStackEvent, fallbackEventId: String, fallbackProducedAt: String): Either[String, Json] =
    for {
      props    <- event.properties.toRight("missing 'properties'")
      bodyRef  <- schemaRef(props, "properties.schema")
      contexts <- props("contexts").fold(List.empty[(SchemaRef, Json)].asRight[String])(contextsFrom)
      bodyData  = Json.fromJsonObject(props.remove("schema").remove("contexts"))
    } yield EventFromThirdParty.build(
      eventId = event.messageId.filter(_.nonEmpty).getOrElse(fallbackEventId),
      producedAt = event.originalTimestamp.filter(_.nonEmpty).getOrElse(fallbackProducedAt),
      bodySchema = bodyRef,
      bodyData = bodyData,
      contexts = contexts
    )

  private def contextsFrom(json: Json): Either[String, List[(SchemaRef, Json)]] =
    json.asArray.toRight("'properties.contexts' must be an array").flatMap(_.toList.traverse(contextFrom))

  private def contextFrom(json: Json): Either[String, (SchemaRef, Json)] =
    for {
      obj  <- json.asObject.toRight("each context must be an object")
      ref  <- schemaRef(obj, "context schema")
      data <- obj("data").toRight("each context must have 'data'")
    } yield (ref, data)

  private def schemaRef(obj: JsonObject, where: String): Either[String, SchemaRef] =
    obj("schema")
      .flatMap(_.asString)
      .toRight(s"missing '$where'")
      .flatMap(s => SchemaRef.parse(s).toEither.leftMap(errs => s"invalid '$where': ${errs.toList.mkString("; ")}"))
}
