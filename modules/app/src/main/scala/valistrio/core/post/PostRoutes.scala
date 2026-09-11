package valistrio.core.post

import cats.effect.IO
import io.circe.syntax._
import io.circe.{Encoder, Json}
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.{HttpRoutes, Response, Status}
import valistrio.core.pipeline.{Ingestion, PostResponse}

/** The POST /post route: run the ingestion pipeline and translate its [[PostResponse]] outcome to
  * an HTTP response and status. Orchestration (and the sinks) live in [[Ingestion]]; the HTTP
  * dress — the wire encoding and the status mapping — lives here.
  */
object PostRoutes {

  def routes(ingestion: Ingestion): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ POST -> Root / "post" =>
      for {
        body <- req.as[String]
        resp <- ingestion.ingest(body)
        http <- respond(resp)
      } yield http
    }

  private implicit val encoder: Encoder[PostResponse] = Encoder.instance {
    case PostResponse.Written => Json.obj("accepted" -> Json.True, "written" -> "events".asJson)
    case PostResponse.Dlqd(errors) =>
      Json.obj("accepted" -> Json.True, "written" -> "dlq".asJson, "errors" -> errors.toList.asJson)
    case PostResponse.Failed(errors) =>
      Json.obj("accepted" -> Json.False, "errors" -> errors.toList.asJson)
  }

  private def respond(resp: PostResponse): IO[Response[IO]] = {
    val status = resp match {
      case PostResponse.Written        => Status.Ok
      case PostResponse.Dlqd(_)        => Status.Ok
      case PostResponse.Failed(errors) => statusFor(errors.toList.map(_.`type`).toSet)
    }
    IO(Response[IO](status).withEntity(resp.asJson))
  }

  /** Only reached for a `Failed` outcome, which carries transient (Retry) errors — owned failures
    * are salvaged to the DLQ and return 200. So this maps the registry- and sink-transient types;
    * the `else` is unreachable and signals a classification bug.
    */
  private def statusFor(types: Set[String]): Status =
    if (types.contains("schema_registry_unavailable") || types.contains("sink_unavailable")) Status.ServiceUnavailable
    else if (types.contains("schema_registry_timeout") || types.contains("sink_timeout")) Status.GatewayTimeout
    else if (types.contains("sink_write_failed")) Status.BadGateway
    else Status.InternalServerError
}
