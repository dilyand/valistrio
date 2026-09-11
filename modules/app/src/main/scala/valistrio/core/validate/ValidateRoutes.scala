package valistrio.core.validate

import cats.effect.IO
import cats.syntax.applicativeError._
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.{HttpRoutes, Response, Status}
import valistrio.core.ValistrioError.ValidationErrors
import valistrio.core.domain.ResponseError
import valistrio.core.pipeline.Validation

/** The POST /validate route: run the validation pipeline and report whether the event conforms.
  * Orchestration lives in [[Validation]]; this only decodes the request, delegates, and maps the
  * outcome to an HTTP response and status.
  */
object ValidateRoutes {

  def routes(validation: Validation): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case req @ POST -> Root / "validate" =>
      for {
        body <- req.as[String]
        resp <- outcome(validation, body)
        http <- respond(resp)
      } yield http
    }

  private def outcome(validation: Validation, body: String): IO[ValidateResponse] =
    Validation.parse(body) match {
      case Left(err) => IO.pure(ValidateResponse.Failure(ResponseError.from(err)))
      case Right(json) =>
        validation.validate(json).attemptNarrow[ValidationErrors].map {
          case Right(_)                     => ValidateResponse.Success
          case Left(ValidationErrors(errs)) => ValidateResponse.Failure(errs.flatMap(ResponseError.from))
        }
    }

  private def respond(resp: ValidateResponse): IO[Response[IO]] = {
    val status = resp match {
      case ValidateResponse.Success         => Status.Ok
      case ValidateResponse.Failure(errors) => statusFor(errors.toList.map(_.`type`).toSet)
    }
    IO(Response[IO](status).withEntity(resp.asJson))
  }

  /** Only non-JSON is 400; a well-formed request whose referenced schema is missing or whose
    * payload doesn't conform is 422 (the request URI itself is fine, so 404 would mislead).
    */
  private def statusFor(types: Set[String]): Status =
    if (types.contains("malformed_json")) Status.BadRequest
    else if (types.contains("schema_registry_unavailable")) Status.ServiceUnavailable
    else if (types.contains("schema_registry_timeout")) Status.GatewayTimeout
    else Status.UnprocessableContent // schema_not_found, schema_validation_failed
}
