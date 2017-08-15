package akka.stream.scaladsl

import scala.concurrent.Future

trait SinkNonblockingQueueWithCancel[T] extends SinkQueueWithCancel[T] {
  def tryPull(): Future[Option[Option[T]]]
}
