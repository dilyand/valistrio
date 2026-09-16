package valistrio.core.resources

import cats.effect.{IO, Resource}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** The application's single logger, acquired once and threaded into the other resources and the
  * server.
  */
object Logging {
  val resource: Resource[IO, Logger[IO]] = Resource.eval(Slf4jLogger.create[IO])
}
