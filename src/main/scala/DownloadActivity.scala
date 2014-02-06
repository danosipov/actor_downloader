package com.danosipov.asyncandroid.actordownloader

import android.view.{ViewGroup, LayoutInflater, View}
import android.os.Bundle
import android.widget.{Button, TextView, ProgressBar}
import akka.actor._
import android.app.Fragment

class DownloadActivity extends TypedActivity {

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_download);

    if (savedInstanceState eq null) {
      getFragmentManager().beginTransaction()
        .add(R.id.container, new DownloadFragment())
        .commit();
    }
  }

  /**
   * Main fragment in our application
   * Decoupled from the parent activity to allow rotation handling.
   */
  class DownloadFragment extends Fragment {
    import TypedResource._

    // Implicit conversion that lets us use anonymous functions as onClickListeners
    implicit def onClickListener(f: (View => Unit)): View.OnClickListener = {
      new View.OnClickListener() {
        override def onClick(v: View) {
          f(v)
        }
      }
    }

    // Need to wrap views in an Option, since they are not
    // available when the class is initialized.
    var progressBar: Option[ProgressBar] = None
    var downloadProgressTextView: Option[TextView] = None

    val system = getApplication().asInstanceOf[DownloadApplication].actorSystem
    val downloadButtonActor = system.actorOf(Props[DownloadButton]().withDispatcher("akka.actor.main-thread"))
    val resetButtonActor = system.actorOf(Props[ResetButton].withDispatcher("akka.actor.main-thread"))
    val fragmentCompanion = system.actorOf(Props(new DownloadFragmentActor()).withDispatcher("akka.actor.main-thread"))
    val downloader = system.actorOf(Props(new Downloader()), "downloader")

    override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
      setRetainInstance(true)
      val rootView = inflater.inflate(R.layout.fragment_download, container, false);

      rootView.findView(TR.downloadButton).setOnClickListener((v: View) => {
        downloadButtonActor.tell(ClickEvent(v.asInstanceOf[Button]), fragmentCompanion)
      })
      rootView.findView(TR.resetButton).setOnClickListener((v: View) => {
       resetButtonActor.tell(ClickEvent(v.asInstanceOf[Button]), fragmentCompanion)
      })

      progressBar = Some(rootView.findView(TR.downloadProgressBar))
      downloadProgressTextView = Some(rootView.findView(TR.downloadProgressTextView))

      // The actor holds the state of the download button
      downloadButtonActor ! RestoreButtonState(rootView.findView(TR.downloadButton))
      rootView
    }


    class DownloadFragmentActor extends Actor {
      def receive = {
        case event: DownloadProgressEvent => updateProgress(event)
        case DownloadFinishedEvent => {
          // Nothing that we need to do
        }
        case TriggerStartDownloadEvent => {
          downloader ! StartDownload(findView(TR.urlEditText).getText.toString)
        }
        case TriggerPauseDownloadEvent => downloader ! PauseEvent
        case TriggerResumeDownloadEvent => downloader ! ResumeEvent
        case TriggerResetDownloadEvent => {
          updateProgress(DownloadProgressEvent(0, 0))
          downloadButtonActor ! ResetButtonState(findView(TR.downloadButton))
          downloader ! ResetEvent
        }
      }

      def updateProgress(progress: DownloadProgressEvent) {
        // View elements are wrapped in an Option, so foreach will
        // apply only to ones that are not None/null.
        progressBar foreach (_.setProgress(progress.getProgress))
        downloadProgressTextView foreach (_.setText(String.format("%s / %s",
          progress.getLoadedBytes, progress.getTotalBytes)))
      }
    }
  }
}

case class ClickEvent[B <: Button](v: B)
case object DownloadFinishedEvent
case object TriggerStartDownloadEvent
case object TriggerPauseDownloadEvent
case object TriggerResumeDownloadEvent
case object TriggerResetDownloadEvent
case class RestoreButtonState(v: Button)
case class ResetButtonState(v: Button)
case class DownloadProgressEvent(loaded: Long, total: Long) {
  def getProgress: Int = {
    ((loaded * 100).toDouble / total).toInt
  }

  def getLoadedBytes: String = {
    humanReadableByteCount(loaded, si=true)
  }

  def getTotalBytes: String = {
    humanReadableByteCount(total, si=true)
  }

  /**
   * Thanks to helpful StackOverflow answer!
   * http://stackoverflow.com/questions/3758606/how-to-convert-byte-size-into-human-readable-format-in-java
   * Translated into Scala
   */
  def humanReadableByteCount(bytes: Long, si: Boolean): String = {
    val unit: Int = if(si) 1000 else 1024
    if (bytes < unit) bytes + " B" else {
      val exp: Int = (Math.log(bytes) / Math.log(unit)).toInt
      val pre: String = (if(si) "kMGTPE" else "KMGTPE").charAt(exp-1) + (if(si) "" else "i")
      "%.1f %sB".format(bytes / Math.pow(unit, exp), pre)
    }
  }
}

class DownloadButton extends Actor with ActorLogging {
  def receive = download

  val download: Receive = {
    case ClickEvent(v: Button) => {
      sender ! TriggerStartDownloadEvent
      v.setText("Pause")
      context.become(pause)
    }
    case RestoreButtonState(v: Button) => v.setText("Download")
    case ResetButtonState(v: Button) => performReset(v)
  }

  val pause: Receive = {
    case ClickEvent(v: Button) => {
      sender ! TriggerPauseDownloadEvent
      v.setText("Resume")
      context.become(resume)
    }
    case RestoreButtonState(v: Button) => v.setText("Pause")
    case ResetButtonState(v: Button) => performReset(v)
  }

  val resume: Receive = {
    case ClickEvent(v: Button) => {
      sender ! TriggerResumeDownloadEvent
      v.setText("Pause")
      context.become(pause)
    }
    case RestoreButtonState(v: Button) => v.setText("Resume")
    case ResetButtonState(v: Button) => performReset(v)
  }

  def performReset(v: Button) {
    v.setText("Download")
    context.become(download)
  }
}

class ResetButton extends Actor with ActorLogging {
  def receive = {
    case ClickEvent(v: View) => {
      sender ! TriggerResetDownloadEvent

    }
  }
}