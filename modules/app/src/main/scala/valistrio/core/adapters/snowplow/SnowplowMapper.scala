package valistrio.core.adapters.snowplow

import cats.syntax.either._
import cats.syntax.traverse._
import io.circe.{Json, JsonObject}
import io.circe.parser
import valistrio.core.adapters.EventFromThirdParty
import valistrio.core.domain.SchemaRef

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/** Maps one decoded [[SnowplowEvent]] (an element of a tp2 `payload_data` batch) into a valistrio
  * event document.
  *
  * Two shapes reach the collector. A self-describing event (`e` = "ue") carries its body inside
  * `ue_pr` (plain JSON) or `ue_px` (base64url): an `unstruct_event` wrapper whose `data` is the
  * inner `{schema, data}` self-describing event. A page view (`e` = "pv") carries no schema — its
  * atomic `url`/`page`/`refr` fields become a Snowplow-native page-view body. Contexts ride `co`
  * (plain) or `cx` (base64url) as a wrapper whose `data` is an array of `{schema, data}`.
  *
  * Every schema on the wire is an iglu URI, translated to a valistrio [[SchemaRef]] via
  * [[IgluSchema]]. `eid` becomes the idempotency key (`event_id`) and `dtm` (epoch-millis) the
  * `produced_at`; each falls back to a caller-supplied default when the tracker omits it. The
  * tracker's transport/enrichment fields are part of no schema, so they are not carried.
  *
  * A malformed body, an unsupported event type, or an invalid iglu ref is a `Left`: the caller
  * returns it as a 400, which the SDK drops rather than retrying a permanently-bad request.
  */
object SnowplowMapper {

  val PageViewSchema: SchemaRef =
    SchemaRef
      .parse("com.snowplowanalytics.snowplow/page_view/1.0.0")
      .getOrElse(throw new IllegalStateException("invalid Snowplow page-view schema ref"))

  def map(event: SnowplowEvent, fallbackEventId: String, fallbackProducedAt: String): Either[String, Json] =
    for {
      body       <- bodyFrom(event)
      contexts   <- contextsFrom(event)
      producedAt <- producedAtFrom(event, fallbackProducedAt)
    } yield EventFromThirdParty.build(
      eventId = event.eid.filter(_.nonEmpty).getOrElse(fallbackEventId),
      producedAt = producedAt,
      bodySchema = body._1,
      bodyData = body._2,
      contexts = contexts
    )

  private def bodyFrom(event: SnowplowEvent): Either[String, (SchemaRef, Json)] =
    event.e.filter(_.nonEmpty).toRight("missing event type 'e'").flatMap {
      case "ue"   => selfDescribing(event)
      case "pv"   => Right((PageViewSchema, pageViewData(event)))
      case other  => Left(s"unsupported event type '$other'")
    }

  /** Unwrap `ue_pr`/`ue_px`: the outer `unstruct_event` wrapper's `data` is the inner
    * `{schema, data}` self-describing event, whose iglu schema and data become the body.
    */
  private def selfDescribing(event: SnowplowEvent): Either[String, (SchemaRef, Json)] =
    for {
      raw   <- decoded(event.ue_pr, event.ue_px, "ue_pr")
      inner <- raw.asObject.flatMap(_("data")).flatMap(_.asObject).toRight("'ue_pr' is missing the inner event 'data'")
      ref   <- igluRef(inner, "ue_pr.data")
      data  <- inner("data").toRight("the inner event is missing 'data'")
    } yield (ref, data)

  private def pageViewData(event: SnowplowEvent): Json =
    Json.fromFields(
      List("url" -> event.url, "page" -> event.page, "refr" -> event.refr)
        .collect { case (key, Some(value)) if value.nonEmpty => key -> Json.fromString(value) }
    )

  private def contextsFrom(event: SnowplowEvent): Either[String, List[(SchemaRef, Json)]] =
    (event.co.filter(_.nonEmpty), event.cx.filter(_.nonEmpty)) match {
      case (None, None) => Right(Nil)
      case _ =>
        for {
          raw  <- decoded(event.co, event.cx, "co")
          arr  <- raw.asObject.flatMap(_("data")).flatMap(_.asArray).toRight("'co' is missing a 'data' array")
          ctxs <- arr.toList.traverse(contextFrom)
        } yield ctxs
    }

  private def contextFrom(json: Json): Either[String, (SchemaRef, Json)] =
    for {
      obj  <- json.asObject.toRight("each context must be an object")
      ref  <- igluRef(obj, "context")
      data <- obj("data").toRight("each context must have 'data'")
    } yield (ref, data)

  private def producedAtFrom(event: SnowplowEvent, fallback: String): Either[String, String] =
    event.dtm.filter(_.nonEmpty) match {
      case None => Right(fallback)
      case Some(dtm) =>
        Either
          .catchOnly[NumberFormatException](dtm.toLong)
          .leftMap(_ => s"invalid 'dtm': '$dtm' is not epoch-millis")
          .map(millis => Instant.ofEpochMilli(millis).toString)
    }

  private def igluRef(obj: JsonObject, where: String): Either[String, SchemaRef] =
    obj("schema")
      .flatMap(_.asString)
      .toRight(s"missing '$where' schema")
      .flatMap(uri => IgluSchema.toSchemaRef(uri).leftMap(err => s"invalid '$where' schema: $err"))

  /** Read a payload field that is plain JSON (`ue_pr`/`co`) or base64url-encoded JSON
    * (`ue_px`/`cx`), preferring the plain form, and parse it.
    */
  private def decoded(plain: Option[String], base64: Option[String], where: String): Either[String, Json] =
    plain.filter(_.nonEmpty) match {
      case Some(s) => parse(s, where)
      case None =>
        base64.filter(_.nonEmpty).toRight(s"missing '$where'").flatMap(fromBase64(_, where)).flatMap(parse(_, where))
    }

  private def parse(s: String, where: String): Either[String, Json] =
    parser.parse(s).leftMap(err => s"'$where' is not valid JSON: ${err.message}")

  private def fromBase64(s: String, where: String): Either[String, String] = {
    val padded = s + ("=" * ((4 - s.length % 4) % 4))
    Either
      .catchNonFatal(new String(Base64.getUrlDecoder.decode(padded), StandardCharsets.UTF_8))
      .leftMap(_ => s"'$where' is not valid base64url")
  }
}
