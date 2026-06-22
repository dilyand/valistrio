package valistrio.core.post

import cats.effect.{IO, Resource}
import fs2.kafka._
import io.circe.syntax._
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.errors.{TimeoutException => KafkaTimeoutException}
import org.apache.kafka.common.serialization.StringSerializer
import valistrio.core.Config.KafkaConfig
import valistrio.core.ValistrioError.SinkError
import valistrio.core.ValistrioError.SinkError._
import valistrio.core.domain.TransportEnvelope

import java.util.Properties
import scala.concurrent.duration._

object KafkaSink {

  private val ConnectivityCheckTimeout = 5.seconds

  /** Creates a [[Sink]][IO] backed by an `fs2-kafka` producer, writing to `config.topic`.
    *
    * On resource acquisition, probes the broker for connectivity (`partitionsFor`) so
    * the application fails fast at startup if Kafka is unreachable, rather than only
    * discovering it on the first `/post` request.
    */
  def resource(config: KafkaConfig): Resource[IO, Sink[IO]] =
    Resource.eval(checkConnectivity(config)).flatMap { _ =>
      producerResource(config).map(new LiveKafkaSink(_, config.topic))
    }

  // ---- Private implementation ----

  private def checkConnectivity(config: KafkaConfig): IO[Unit] =
    IO.blocking {
      val props = new Properties()
      props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
      props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
      props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
      props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, ConnectivityCheckTimeout.toMillis.toString)

      val probe = new org.apache.kafka.clients.producer.KafkaProducer[String, String](props)
      try probe.partitionsFor(config.topic)
      finally probe.close()
    }.timeout(ConnectivityCheckTimeout).void

  private def producerResource(config: KafkaConfig): Resource[IO, KafkaProducer[IO, String, String]] = {
    val settings = ProducerSettings(Serializer[IO, String], Serializer[IO, String])
      .withBootstrapServers(config.bootstrapServers)
      .withAcks(Acks.All)

    KafkaProducer.resource(settings)
  }

  private class LiveKafkaSink(producer: KafkaProducer[IO, String, String], topic: String) extends Sink[IO] {

    def write(envelope: TransportEnvelope): IO[Either[SinkError, Unit]] = {
      val record = ProducerRecord(topic, envelope.data.meta.eventId, envelope.asJson.noSpaces)
      producer
        .produceOne_(record)
        .flatten
        .attempt
        .map {
          case Right(_)                       => Right(())
          case Left(_: KafkaTimeoutException) => Left(Timeout)
          case Left(e)                        => Left(WriteFailed(e.getMessage))
        }
    }
  }
}
