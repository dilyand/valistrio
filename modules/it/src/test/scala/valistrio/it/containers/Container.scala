package valistrio.it.containers

import org.testcontainers.containers.{GenericContainer => JGenericContainer}

import java.util.concurrent.Semaphore

/** Base trait for shared Testcontainers singletons.
  *
  * Implements reference counting via a [[Semaphore]] so that the underlying
  * container is started on the first call to [[start]] and stopped only when
  * the last outstanding [[stop]] call brings the count back to zero.
  *
  * This allows multiple spec objects (each calling `beforeAll`/`afterAll`) to
  * share the same container instance from a `TestRig` companion object without
  * one suite tearing down the container while another is still using it.
  */
trait Container {
  val container: JGenericContainer[_]

  private val MaxPermits = Int.MaxValue
  private val permits    = new Semaphore(MaxPermits)

  def host: String = container.getHost

  final def start(): Unit = synchronized {
    permits.acquire()
    if (!container.isRunning) container.start()
  }

  final def stop(): Unit = synchronized {
    permits.release()
    if (permits.availablePermits() == MaxPermits && container.isRunning)
      container.stop()
  }
}
