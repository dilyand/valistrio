package valistrio.core.adapters.rudderstack

import cats.syntax.either._
import cats.syntax.traverse._
import io.circe.{Json, JsonObject}
import valistrio.core.adapters.{EventDocument, TypedPayload}
import valistrio.core.domain.SchemaRef

/** Maps a decoded [[RudderStackEvent]] into a valistrio event document.
  *
  * The producer attaches the body schema ref as `properties.schema` and any contexts as
  * `properties.contexts` (an array of `{schema, data}`); both are lifted out and the remaining
  * properties become the body data. `messageId` is the idempotency key (`event_id`) and
  * `originalTimestamp` the `produced_at`; each falls back to a caller-supplied default when the
  * producer omits it.
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
      contexts <- props("contexts").fold(List.empty[TypedPayload].asRight[String])(contextsFrom)
      bodyData  = Json.fromJsonObject(props.remove("schema").remove("contexts"))
    } yield EventDocument.build(
      eventId = event.messageId.filter(_.nonEmpty).getOrElse(fallbackEventId),
      producedAt = event.originalTimestamp.filter(_.nonEmpty).getOrElse(fallbackProducedAt),
      body = TypedPayload(bodyRef, bodyData),
      contexts = contexts
    )

  private def contextsFrom(json: Json): Either[String, List[TypedPayload]] =
    json.asArray.toRight("'properties.contexts' must be an array").flatMap(_.toList.traverse(contextFrom))

  private def contextFrom(json: Json): Either[String, TypedPayload] =
    for {
      obj  <- json.asObject.toRight("each context must be an object")
      ref  <- schemaRef(obj, "context schema")
      data <- obj("data").toRight("each context must have 'data'")
    } yield TypedPayload(ref, data)

  private def schemaRef(obj: JsonObject, where: String): Either[String, SchemaRef] =
    obj("schema")
      .flatMap(_.asString)
      .toRight(s"missing '$where'")
      .flatMap(s => SchemaRef.parse(s).toEither.leftMap(errs => s"invalid '$where': ${errs.toList.mkString("; ")}"))
}
