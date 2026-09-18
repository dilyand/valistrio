package valistrio.core.adapters.snowplow

import cats.effect.IO
import cats.syntax.either._
import io.circe.parser
import org.http4s.dsl.io._
import org.http4s.{HttpRoutes, Response, Status}
import valistrio.core.http.PostStatus
import valistrio.core.pipeline.Ingestion

import java.util.UUID

/** Terminates the Snowplow browser SDK's tp2 collector protocol. The SDK POSTs a `payload_data`
  * envelope to `/com.snowplowanalytics.snowplow/tp2` and reads only the response status — the body
  * is ignored. valistrio ingests one event per request, so the handler unwraps the envelope's
  * single-element `data` array, maps that event to the valistrio event document, forwards it to the
  * shared [[Ingestion]] pipeline, and returns a status-only response.
  *
  * A request that cannot be decoded or mapped — including a batch of more than one event — is 400,
  * which the SDK drops rather than retries; an owned event is 200; a transient infrastructure
  * failure passes the /post 5xx through so the SDK retries.
  */
object SnowplowRoutes {

  def apply(ingestion: Ingestion): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ POST -> Root / "com.snowplowanalytics.snowplow" / "tp2" =>
      req.bodyText.compile.string.flatMap(forward(ingestion, _))
    }

  private def forward(ingestion: Ingestion, body: String): IO[Response[IO]] =
    toDocument(body).flatMap {
      case Left(_)    => IO.pure(Response[IO](Status.BadRequest))
      case Right(doc) => ingestion.ingest(doc).map(resp => Response[IO](PostStatus.of(resp)))
    }

  /** Resolve the fallbacks the SDK may omit (a fresh event id, the current time) inside `IO`, then
    * parse and map the wire event purely. The document is handed to `Ingestion` as a string so the
    * adapter goes through the exact same /post path.
    */
  private def toDocument(body: String): IO[Either[String, String]] =
    for {
      now <- IO.realTimeInstant
      id  <- IO(UUID.randomUUID().toString)
    } yield singleEvent(body)
      .flatMap(SnowplowMapper.map(_, id, now.toString))
      .map(_.noSpaces)

  /** The tp2 body is a `payload_data` envelope `{schema, data:[…]}`. valistrio ingests one event per
    * request, so exactly one element is expected; any other count is out of contract.
    */
  private def singleEvent(body: String): Either[String, SnowplowEvent] =
    for {
      json  <- parser.parse(body).leftMap(_.message)
      data  <- json.asObject.flatMap(_("data")).flatMap(_.asArray).toRight("missing 'data' array")
      event <- data match {
                 case Vector(single) => single.as[SnowplowEvent].leftMap(_.getMessage)
                 case other          => Left(s"expected exactly one event, got ${other.size}")
               }
    } yield event
}
