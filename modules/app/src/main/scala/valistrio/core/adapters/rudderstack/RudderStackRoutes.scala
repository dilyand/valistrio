package valistrio.core.adapters.rudderstack

import cats.effect.IO
import cats.syntax.either._
import io.circe.parser
import org.http4s.dsl.io._
import org.http4s.{HttpRoutes, Response, Status}
import valistrio.core.http.PostStatus
import valistrio.core.pipeline.Ingestion

import java.util.UUID

/** Terminates the RudderStack browser SDK's data-plane protocol. The SDK POSTs one event per
  * request to `/v1/track` and `/v1/page` and reads only the response status — the body is ignored —
  * so each handler decodes the event, maps it to the valistrio event document, forwards it to the
  * shared [[Ingestion]] pipeline, and returns a status-only response.
  *
  * A request that cannot be decoded or mapped is 400, which the SDK drops rather than retries; an
  * owned event is 200; a transient infrastructure failure passes the /post 5xx through so the SDK
  * retries. Any other `/v1` type falls through unmatched (404).
  */
object RudderStackRoutes {

  private val supportedTypes = Set("track", "page")

  def apply(ingestion: Ingestion): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ POST -> Root / "v1" / eventType if supportedTypes(eventType) =>
      req.as[String].flatMap(forward(ingestion, _))
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
    } yield parser
      .parse(body)
      .leftMap(_.message)
      .flatMap(_.as[RudderStackEvent].leftMap(_.getMessage))
      .flatMap(RudderStackMapper.map(_, id, now.toString))
      .map(_.noSpaces)
}
