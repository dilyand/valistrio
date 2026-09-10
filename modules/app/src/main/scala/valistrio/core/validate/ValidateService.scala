package valistrio.core.validate

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.parallel._
import io.circe.Json
import io.circe.parser
import valistrio.core.ValistrioError.ValidateError
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.domain.{SchemaRef, SchemaVersion, Event, TypedData}

/** Orchestrates the full /validate request flow.
  *
  * Validation phases:
  *  1. Parse raw body as JSON         — [[MalformedJson]] on failure (non-recoverable, short-circuits)
  *  2. Decode into Event  — [[StructuralDecodeError]] on failure (non-recoverable, short-circuits)
  *  3. Validate in parallel (errors collected, never short-circuited):
  *     a. Envelope JSON against `com.valistrio/envelope/1.0.0`
  *     b. `event.data` against `event.schema`
  *     c. Each `context.data` against its `context.schema`
  */
class ValidateService(registry: SchemaRegistry) {

  def validate(rawBody: String): IO[ValidateResponse] =
    ValidateService.parseAndDecode(rawBody) match {
      case Left(err)               => IO.pure(failure(err))
      case Right((json, envelope)) => validateAll(json, envelope)
    }

  /** Validates an already-decoded envelope against the registry, reusable by callers
    * (e.g. the /post service) that have already parsed and decoded the body themselves.
    */
  def validateAll(json: Json, envelope: Event): IO[ValidateResponse] = {
    val contexts: List[TypedData] =
      envelope.data.contexts.fold(List.empty[TypedData])(_.toList)

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
  private[validate] val EnvelopeSchemaName: SchemaRef =
    SchemaRef("com.valistrio", "envelope", SchemaVersion(1, 0, 0))

  /** Parses the raw body as JSON and decodes it into a [[Event]].
    *
    * Shared by [[ValidateService.validate]] and the /post service, so both
    * short-circuit on the same [[MalformedJson]]/[[StructuralDecodeError]] errors.
    */
  def parseAndDecode(rawBody: String): Either[ValidateError, (Json, Event)] =
    parser.parse(rawBody).left.map(err => MalformedJson(err.message): ValidateError).flatMap { json =>
      json.as[Event].left.map(err => StructuralDecodeError(err.message): ValidateError).map(env => (json, env))
    }
}
