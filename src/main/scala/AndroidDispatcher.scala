package akka.dispatch

import language.postfixOps

import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import scala.annotation.tailrec
import com.typesafe.config.Config
import akka.actor.{ ActorInitializationException, ExtensionIdProvider, ExtensionId, Extension, ExtendedActorSystem, ActorRef, ActorCell }
import akka.dispatch.sysmsg.{ SystemMessage, Suspend, Resume }
import scala.concurrent.duration._
import akka.util.Switch
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import java.util.concurrent.TimeUnit
import android.os.{Handler, Looper}

/**
 * Blatantly copied from Akka testkit. Adjusted to run code on Android main thread.
 *
 */
private[akka] object AndroidDispatcherQueues extends ExtensionId[AndroidDispatcherQueues] with ExtensionIdProvider {
  override def lookup = AndroidDispatcherQueues
  override def createExtension(system: ExtendedActorSystem): AndroidDispatcherQueues = new AndroidDispatcherQueues
}

private[akka] class AndroidDispatcherQueues extends Extension {

  // PRIVATE DATA

  private var queues = Map[AndroidMailbox, Set[WeakReference[MessageQueue]]]()
  private var lastGC = 0l

  // we have to forget about long-gone threads sometime
  private def gc(): Unit = {
    queues = (Map.newBuilder[AndroidMailbox, Set[WeakReference[MessageQueue]]] /: queues) {
      case (m, (k, v)) ⇒
        val nv = v filter (_.get ne null)
        if (nv.isEmpty) m else m += (k -> nv)
    }.result
  }

  protected[akka] def registerQueue(mbox: AndroidMailbox, q: MessageQueue): Unit = synchronized {
    if (queues contains mbox) {
      val newSet = queues(mbox) + new WeakReference(q)
      queues += mbox -> newSet
    } else {
      queues += mbox -> Set(new WeakReference(q))
    }
    val now = System.nanoTime
    if (now - lastGC > 1000000000l) {
      lastGC = now
      gc()
    }
  }

  protected[akka] def unregisterQueues(mbox: AndroidMailbox): Unit = synchronized {
    queues -= mbox
  }

  /*
   * This method must be called with "own" being this thread's queue for the
   * given mailbox. When this method returns, the queue will be entered
   * (active).
   */
  protected[akka] def gatherFromAllOtherQueues(mbox: AndroidMailbox, own: MessageQueue): Unit = synchronized {
    if (queues contains mbox) {
      for {
        ref ← queues(mbox)
        q = ref.get
        if (q ne null) && (q ne own)
      } {
        val owner = mbox.actor.self
        var msg = q.dequeue()
        while (msg ne null) {
          // this is safe because this method is only ever called while holding the suspendSwitch monitor
          own.enqueue(owner, msg)
          msg = q.dequeue()
        }
      }
    }
  }
}

object AndroidDispatcher {
  val Id = "akka.dispatch.android-dispatcher"
}

/**
 * Dispatcher modeled after CallingThreadDispatcher from Akka testkit.
 */
class AndroidDispatcher(_configurator: MessageDispatcherConfigurator) extends MessageDispatcher(_configurator) {
  import AndroidDispatcher._
  import configurator.prerequisites._

  val log = akka.event.Logging(eventStream, "AndroidDispatcher")

  override def id: String = Id

  protected[akka] override def createMailbox(actor: akka.actor.Cell, mailboxType: MailboxType) =
    new AndroidMailbox(actor, mailboxType)

  protected[akka] override def shutdown() {}

  protected[akka] override def throughput = 0
  protected[akka] override def throughputDeadlineTime = Duration.Zero
  protected[akka] override def registerForExecution(mbox: Mailbox, hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean = false

  protected[akka] override def shutdownTimeout = 1 second

  protected[akka] override def register(actor: ActorCell): Unit = {
    super.register(actor)
    actor.mailbox match {
      case mbox: AndroidMailbox ⇒
        val queue = mbox.queue
        runQueue(mbox, queue)
      case x ⇒ throw ActorInitializationException("expected AndroidMailbox, got " + x.getClass)
    }
  }

  protected[akka] override def unregister(actor: ActorCell): Unit = {
    val mbox = actor.mailbox match {
      case m: AndroidMailbox ⇒ Some(m)
      case _                       ⇒ None
    }
    super.unregister(actor)
    mbox foreach AndroidDispatcherQueues(actor.system).unregisterQueues
  }

  protected[akka] override def suspend(actor: ActorCell) {
    actor.mailbox match {
      case m: AndroidMailbox ⇒ { m.suspendSwitch.switchOn; m.suspend() }
      case m                       ⇒ m.systemEnqueue(actor.self, Suspend())
    }
  }

  protected[akka] override def resume(actor: ActorCell) {
    actor.mailbox match {
      case mbox: AndroidMailbox ⇒
        val queue = mbox.queue
        val switched = mbox.suspendSwitch.switchOff {
          AndroidDispatcherQueues(actor.system).gatherFromAllOtherQueues(mbox, queue)
          mbox.resume()
        }
        if (switched)
          runQueue(mbox, queue)
      case m ⇒ m.systemEnqueue(actor.self, Resume(causedByFailure = null))
    }
  }

  protected[akka] override def systemDispatch(receiver: ActorCell, message: SystemMessage) {
    receiver.mailbox match {
      case mbox: AndroidMailbox ⇒
        mbox.systemEnqueue(receiver.self, message)
        runQueue(mbox, mbox.queue)
      case m ⇒ m.systemEnqueue(receiver.self, message)
    }
  }

  protected[akka] override def dispatch(receiver: ActorCell, handle: Envelope) {
    receiver.mailbox match {
      case mbox: AndroidMailbox ⇒
        val queue = mbox.queue
        val execute = mbox.suspendSwitch.fold {
          queue.enqueue(receiver.self, handle)
          false
        } {
          queue.enqueue(receiver.self, handle)
          true
        }
        if (execute) runQueue(mbox, queue)
      case m ⇒ m.enqueue(receiver.self, handle)
    }
  }

  protected[akka] override def executeTask(invocation: TaskInvocation) { invocation.run }

  /*
   * This method must be called with this thread's queue.
   *
   * If the catch block is executed, then a non-empty mailbox may be stalled as
   * there is no-one who cares to execute it before the next message is sent or
   * it is suspendSwitch and resumed.
   */
  @tailrec
  private def runQueue(mbox: AndroidMailbox, queue: MessageQueue, interruptedEx: InterruptedException = null) {
    val mainHandler = new Handler(Looper.getMainLooper)

    def checkThreadInterruption(intEx: InterruptedException): InterruptedException = {
      if (Thread.interrupted()) { // clear interrupted flag before we continue, exception will be thrown later
      val ie = new InterruptedException("Interrupted during message processing")
        log.error(ie, "Interrupted during message processing")
        ie
      } else intEx
    }

    def throwInterruptionIfExistsOrSet(intEx: InterruptedException): Unit = {
      val ie = checkThreadInterruption(intEx)
      if (ie ne null) {
        Thread.interrupted() // clear interrupted flag before throwing according to java convention
        throw ie
      }
    }

    @tailrec
    def process(intEx: InterruptedException): InterruptedException = {
      var intex = intEx
      val recurse = {
        mbox.processAllSystemMessages()
        val handle = mbox.suspendSwitch.fold[Envelope](null) {
          if (mbox.isClosed) null else queue.dequeue()
        }
        if (handle ne null) {
          try {
            if (Mailbox.debug) println(mbox.actor.self + " processing message " + handle)

            mainHandler.post(new Runnable() {
              def run() {
                mbox.actor.invoke(handle)
              }
            })

            intex = checkThreadInterruption(intex)
            true
          } catch {
            case ie: InterruptedException ⇒
              log.error(ie, "Interrupted during message processing")
              Thread.interrupted() // clear interrupted flag before we continue, exception will be thrown later
              intex = ie
              true
            case NonFatal(e) ⇒
              log.error(e, "Error during message processing")
              false
          }
        } else false
      }
      if (recurse) process(intex)
      else intex
    }

    // if we own the lock then we shouldn't do anything since we are processing
    // this actors mailbox at some other level on our call stack
    if (!mbox.ctdLock.isHeldByCurrentThread) {
      var intex = interruptedEx
      val gotLock = try {
        mbox.ctdLock.tryLock(50, TimeUnit.MILLISECONDS)
      } catch {
        case ie: InterruptedException ⇒
          Thread.interrupted() // clear interrupted flag before we continue, exception will be thrown later
          intex = ie
          false
      }
      if (gotLock) {
        val ie = try {
          process(intex)
        } finally {
          mbox.ctdLock.unlock
        }
        throwInterruptionIfExistsOrSet(ie)
      } else {
        // if we didn't get the lock and our mailbox still has messages, then we need to try again
        if (mbox.hasSystemMessages || mbox.hasMessages) {
          runQueue(mbox, queue, intex)
        } else {
          throwInterruptionIfExistsOrSet(intex)
        }
      }
    }
  }
}

class AndroidDispatcherConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
  extends MessageDispatcherConfigurator(config, prerequisites) {

  private val instance = new AndroidDispatcher(this)

  override def dispatcher(): MessageDispatcher = instance
}

class AndroidMailbox(_receiver: akka.actor.Cell, val mailboxType: MailboxType)
  extends Mailbox(null) with DefaultSystemMessageQueue {

  val system = _receiver.system
  val self = _receiver.self

  private val q = new ThreadLocal[MessageQueue]() {
    override def initialValue = {
      val queue = mailboxType.create(Some(self), Some(system))
      AndroidDispatcherQueues(system).registerQueue(AndroidMailbox.this, queue)
      queue
    }
  }

  override def enqueue(receiver: ActorRef, msg: Envelope): Unit = q.get.enqueue(receiver, msg)
  override def dequeue(): Envelope = throw new UnsupportedOperationException("AndroidMailbox cannot dequeue normally")
  override def hasMessages: Boolean = q.get.hasMessages
  override def numberOfMessages: Int = 0

  def queue = q.get

  val ctdLock = new ReentrantLock
  val suspendSwitch = new Switch

  override def cleanUp(): Unit = {
    /*
     * This is called from dispatcher.unregister, i.e. under this.lock. If
     * another thread obtained a reference to this mailbox and enqueues after
     * the gather operation, tough luck: no guaranteed delivery to deadLetters.
     */
    suspendSwitch.locked {
      val qq = queue
      AndroidDispatcherQueues(actor.system).gatherFromAllOtherQueues(this, qq)
      super.cleanUp()
      qq.cleanUp(actor.self, actor.dispatcher.mailboxes.deadLetterMailbox.messageQueue)
      q.remove()
    }
  }
}