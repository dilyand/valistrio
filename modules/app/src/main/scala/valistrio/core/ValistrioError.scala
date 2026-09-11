package valistrio.core

import cats.data.NonEmptyList
import valistrio.core.domain.SchemaRef

sealed abstract class ValistrioError extends Throwable {
  val msg: String
}

object ValistrioError {
  sealed trait ConfigError extends ValistrioError
  final object ConfigError {
    final case class NotBase64(error: String) extends ConfigError {
      val msg = s"Could not base64-decode string. Error: $error"
    }

    final case class TypesafeConfigError(error: String) extends ConfigError {
      val msg = s"Could not derive Typesafe Config instance from string. Error: $error"
    }

    final case class ParsingError(error: String) extends ConfigError {
      val msg = s"Could not parse Typesafe Config. Error: $error"
    }
  }

  sealed trait ValidateError extends ValistrioError
  final object ValidateError {
    // Non-recoverable (HTTP 400): the body is not JSON at all.

    final case class MalformedJson(message: String) extends ValidateError {
      val msg = s"Request body is not valid JSON. $message"
    }

    // Recoverable, ops-side (HTTP 404 / 503 / 504): fixable by acting on the system.

    final case class SchemaNotFound(schemaName: SchemaRef) extends ValidateError {
      val msg = s"Schema not found: $schemaName"
    }

    final case class SchemaRegistryUnavailable(cause: String) extends ValidateError {
      val msg = s"Schema registry unavailable. $cause"
    }

    case object SchemaRegistryTimeout extends ValidateError {
      val msg = "Schema registry request timed out."
    }

    // Recoverable, payload-side (HTTP 422): the payload or schema can be fixed and reprocessed.

    final case class ValidationFailed(errors: NonEmptyList[ValidationError]) extends ValidateError {
      val msg = s"Payload failed schema validation with ${errors.size} error(s)."
    }
  }

  /** An individual schema validation error, produced by the JSON Schema validator. */
  final case class ValidationError(path: String, message: String)

  /** Aggregate raised by the validate service when an event fails validation — carries every
    * collected per-payload [[ValidateError]]. Recovered at the HTTP boundary to build the response.
    */
  final case class ValidationErrors(errors: NonEmptyList[ValidateError]) extends ValistrioError {
    val msg: String = {
      val details = errors.toList.flatMap {
        case ValidateError.ValidationFailed(fieldErrors) => fieldErrors.toList.map(fe => s"${fe.path}: ${fe.message}")
        case other                                       => List(other.msg)
      }
      val rendered  = details.mkString("; ")
      val truncated = if (rendered.length > 512) rendered.take(512) + "…" else rendered
      s"Event failed validation with ${errors.size} error(s): $truncated"
    }
  }

  sealed trait SinkError extends ValistrioError
  final object SinkError {
    final case class Unavailable(cause: String) extends SinkError {
      val msg = s"Sink unavailable. $cause"
    }

    case object Timeout extends SinkError {
      val msg = "Sink write timed out."
    }

    final case class WriteFailed(cause: String) extends SinkError {
      val msg = s"Sink write failed. $cause"
    }
  }
}

