package valistrio.it

import cats.effect.IO
import io.circe.Json
import io.circe.parser
import org.http4s.client.Client
import org.http4s.client.middleware.{Retry, RetryPolicy}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.{Request, Response, Status}

import scala.concurrent.duration.DurationInt

/** Shared HTTP client for integration tests.
  *
  * Wraps an ember client with exponential-backoff retry so transient startup
  * delays don't cause flaky failures. Tests call [[status]], [[bodyJson]], or
  * [[run]] and receive an `IO`-wrapped result, compatible with the `CatsEffect`
  * mixin used in the specs.
  */
object TestHttp {

  private val MaxRetries = 5
  private val MaxWait    = 2.seconds

  private val retryPolicy: RetryPolicy[IO] =
    RetryPolicy[IO](RetryPolicy.exponentialBackoff(MaxWait, MaxRetries))

  val client: cats.effect.Resource[IO, Client[IO]] =
    EmberClientBuilder.default[IO].build.map(Retry(retryPolicy)(_))

  def status(req: Request[IO]): IO[Status] =
    client.use(_.status(req))

  def statusAndBody(req: Request[IO]): IO[(Status, Json)] =
    client.use { c =>
      c.run(req).use { resp =>
        resp.as[String].map { body =>
          (resp.status, parser.parse(body).getOrElse(Json.Null))
        }
      }
    }
}
