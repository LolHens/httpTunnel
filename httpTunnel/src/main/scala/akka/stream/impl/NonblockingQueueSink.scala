package akka.stream.impl

import akka.stream.Attributes.InputBuffer
import akka.stream.impl.NonblockingQueueSink.{Output, Pull, TryPull}
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.scaladsl.{SinkNonblockingQueueWithCancel, SinkQueueWithCancel}
import akka.stream.stage._
import akka.stream.{Attributes, Inlet, SinkShape}

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

private[stream] object NonblockingQueueSink {

  sealed trait Output[+T]

  final case class Pull[T](promise: Promise[Option[T]]) extends Output[T]

  final case class TryPull[T](promise: Promise[Option[Option[T]]]) extends Output[T]

  case object Cancel extends Output[Nothing]

}

/**
  * INTERNAL API
  */
final class NonblockingQueueSink[T]() extends GraphStageWithMaterializedValue[SinkShape[T], SinkNonblockingQueueWithCancel[T]] {
  type Requested[E] = Promise[Option[E]]

  val in = Inlet[T]("queueSink.in")
  override def initialAttributes = DefaultAttributes.queueSink
  override val shape: SinkShape[T] = SinkShape.of(in)

  override def toString: String = "QueueSink"

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val stageLogic = new GraphStageLogic(shape) with CallbackWrapper[Output[T]] with InHandler {
      type Received[E] = Try[Option[E]]

      val maxBuffer = inheritedAttributes.getAttribute(classOf[InputBuffer], InputBuffer(16, 16)).max
      require(maxBuffer > 0, "Buffer size must be greater than 0")

      var buffer: Buffer[Received[T]] = _
      var currentRequest: Option[Requested[T]] = None

      override def preStart(): Unit = {
        // Allocates one additional element to hold stream
        // closed/failure indicators
        buffer = Buffer(maxBuffer + 1, materializer)
        setKeepGoing(true)
        initCallback(callback.invoke)
        pull(in)
      }

      override def postStop(): Unit = stopCallback {
        case Pull(promise) ⇒ promise.failure(new IllegalStateException("Stream is terminated. QueueSink is detached"))
        case TryPull(promise) ⇒ promise.failure(new IllegalStateException("Stream is terminated. QueueSink is detached"))
        case _ ⇒ //do nothing
      }

      private val callback: AsyncCallback[Output[T]] =
        getAsyncCallback {
          case NonblockingQueueSink.Pull(pullPromise) ⇒ currentRequest match {
            case Some(_) ⇒
              pullPromise.failure(new IllegalStateException("You have to wait for previous future to be resolved to send another request"))
            case None ⇒
              if (buffer.isEmpty) currentRequest = Some(pullPromise)
              else {
                if (buffer.used == maxBuffer) tryPull(in)
                sendDownstream(pullPromise)
              }
          }
          case NonblockingQueueSink.TryPull(pullPromise) ⇒
            if (buffer.isEmpty) pullPromise.success(None)
            else {
              if (buffer.used == maxBuffer) tryPull(in)
              sendDownstream(new Promise[Option[T]] {
                override def future: Future[Option[T]] = ???
                override def tryComplete(result: Try[Option[T]]): Boolean = pullPromise.tryComplete(result.map(Some(_)))
                override def isCompleted: Boolean = pullPromise.isCompleted
              })
            }
          case NonblockingQueueSink.Cancel ⇒ completeStage()
        }

      def sendDownstream(promise: Requested[T]): Unit = {
        val e = buffer.dequeue()
        promise.complete(e)
        e match {
          case Success(_: Some[_]) ⇒ //do nothing
          case Success(None) ⇒ completeStage()
          case Failure(t) ⇒ failStage(t)
        }
      }

      def enqueueAndNotify(requested: Received[T]): Unit = {
        buffer.enqueue(requested)
        currentRequest match {
          case Some(p) ⇒
            sendDownstream(p)
            currentRequest = None
          case None ⇒ //do nothing
        }
      }

      def onPush(): Unit = {
        enqueueAndNotify(Success(Some(grab(in))))
        if (buffer.used < maxBuffer) pull(in)
      }

      override def onUpstreamFinish(): Unit = enqueueAndNotify(Success(None))
      override def onUpstreamFailure(ex: Throwable): Unit = enqueueAndNotify(Failure(ex))

      setHandler(in, this)
    }

    (stageLogic, new SinkNonblockingQueueWithCancel[T] {
      override def pull(): Future[Option[T]] = {
        val p = Promise[Option[T]]
        stageLogic.invoke(Pull(p))
        p.future
      }
      override def tryPull(): Future[Option[Option[T]]] = {
        val p = Promise[Option[Option[T]]]
        stageLogic.invoke(TryPull(p))
        p.future
      }
      override def cancel(): Unit = {
        stageLogic.invoke(NonblockingQueueSink.Cancel)
      }
    })
  }
}

/*final class SinkNonblockingQueueAdapter[T](delegate: SinkQueueWithCancel[T]) extends akka.stream.javadsl.SinkQueueWithCancel[T] {
  import akka.dispatch.ExecutionContexts.{ sameThreadExecutionContext ⇒ same }
  def pull(): CompletionStage[Optional[T]] = delegate.pull().map(_.asJava)(same).toJava
  def cancel(): Unit = delegate.cancel()
}*/
