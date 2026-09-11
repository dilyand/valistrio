package valistrio.core.resources

import cats.effect.IO
import cats.syntax.applicativeError._
import fs2.kafka._
import org.apache.kafka.common.errors.{
  RecordTooLargeException,
  SerializationException,
  TimeoutException => KafkaTimeoutException,
  UnknownTopicOrPartitionException
}
import valistrio.core.ValistrioError.SinkError._
import valistrio.core.domain.Writable

/** A [[Sink]] backed by an `fs2-kafka` producer, writing the record's JSON to `topic` keyed by
  * its (optional) key. A write failure is raised as a
  * [[valistrio.core.ValistrioError.SinkError]] on the IO error channel.
  *
  * The producer is supplied from outside (see [[Kafka.producer]]) so the events and DLQ sinks
  * share one connection.
  */
final class KafkaSink[A <: Writable](producer: KafkaProducer[IO, String, String], topic: String) extends Sink[A] {
  def write(a: A): IO[Unit] =
    producer.produceOne_(ProducerRecord(topic, a.key.orNull, a.json.noSpaces)).flatten.void.adaptError {
      case _: KafkaTimeoutException            => Timeout
      case e: UnknownTopicOrPartitionException => Unavailable(s"unknown topic or partition: ${e.getMessage}")
      case e: RecordTooLargeException          => WriteFailed(s"record exceeds the broker's max size: ${e.getMessage}")
      case e: SerializationException           => WriteFailed(s"serialization failed: ${e.getMessage}")
      case e                                   => WriteFailed(e.getMessage)
    }
}
