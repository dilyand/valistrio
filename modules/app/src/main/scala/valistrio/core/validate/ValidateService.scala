package valistrio.core.validate

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.parallel._
import io.circe.Json
import io.circe.parser
import valistrio.core.ValistrioError.ValidateError
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.domain.{SchemaName, TransportEnvelope, TypedPayload}

/** Orchestrates the full /validate request flow.
  *
  * Validation phases:
  *  1. Parse raw body as JSON         — [[MalformedJson]] on failure (non-recoverable, short-circuits)
  *  2. Decode into TransportEnvelope  — [[StructuralDecodeError]] on failure (non-recoverable, short-circuits)
  *  3. Validate in parallel (errors collected, never short-circuited):
  *     a. Envelope JSON against `com.valistrio/envelope/1.0.0`
  *     b. `event.data` against `event.schema`
  *     c. Each `context.data` against its `context.schema`
  */
class ValidateService(registry: SchemaRegistry[IO]) {

  def validate(rawBody: String): IO[ValidateResponse] =
    parser.parse(rawBody) match {
      case Left(err)   => IO.pure(failure(MalformedJson(err.message)))
      case Right(json) =>
        json.as[TransportEnvelope] match {
          case Left(err)       => IO.pure(failure(StructuralDecodeError(err.message)))
          case Right(envelope) => validateAll(json, envelope)
        }
    }

  // ---- Private ----

  private def validateAll(json: Json, envelope: TransportEnvelope): IO[ValidateResponse] = {
    val contexts: List[TypedPayload] =
      envelope.data.contexts.fold(List.empty[TypedPayload])(_.toList)

    val tasks: List[IO[Either[ValidateError, Unit]]] =
      registry.validate(ValidateService.EnvelopeSchemaName, json) ::
      registry.validate(envelope.data.event.schema, envelope.data.event.data) ::
      contexts.map(ctx => registry.validate(ctx.schema, ctx.data))

    tasks.parSequence.map { results =>
      val errors: List[ValidateResponseError] = results.collect {
        case Left(e) => ValidateResponseError.from(e).toList
      }.flatten

      NonEmptyList.fromList(errors) match {
        case None      => ValidateResponse.Success
        case Some(nel) => ValidateResponse.Failure(nel)
      }
    }
  }

  private def failure(e: ValidateError): ValidateResponse =
    ValidateResponse.Failure(ValidateResponseError.from(e))
}

object ValidateService {
  private[validate] val EnvelopeSchemaName: SchemaName =
    SchemaName.parse("com.valistrio/envelope/1.0.0")
      .getOrElse(throw new IllegalStateException("Invalid built-in schema name"))
}
