package com.danosipov.asyncandroid.actordownloader

import akka.actor.{ActorRef, Actor}
import android.os.Environment
import java.io.{InputStream, FileOutputStream, File}
import java.net.{URL, HttpURLConnection}
import scala.concurrent.{Future, Promise, ExecutionContext}

// Downloader messages
case class StartDownload(url: String)
case object ResetEvent
case object PauseEvent
case object ResumeEvent

/**
 * Downloader actor
 *
 */
class Downloader extends Actor {
  var reportListener: Option[ActorRef] = None
  @volatile var running = true
  @volatile var killed = false

  def receive = awaitingDownload

  val awaitingDownload: Receive = {
    case StartDownload(url: String) => {
      reportListener = Some(sender)
      download(url)
      context.become(downloading, true)
    }
  }

  val downloading: Receive = {
    case ResetEvent => killed = true
    case PauseEvent => running = false
    case ResumeEvent => running = true
  }

  def download(url: String) = {
    implicit val ec = context.system.dispatcher
    Future {
      try {
        var totalSize: Long = 0
        var loadedSize: Long = 0
        val toDownload: URL = new URL(url)
        val urlConnection: HttpURLConnection = toDownload.openConnection().asInstanceOf[HttpURLConnection]

        urlConnection.setRequestMethod("GET")
        // Causes issues on ICS:
        //urlConnection.setDoOutput(true)
        urlConnection.connect()

        val sdCardRoot: File = Environment.getExternalStorageDirectory
        val outFile: File = new File(sdCardRoot, "outfile")
        val fileOutput: FileOutputStream  = new FileOutputStream(outFile)

        val inputStream: InputStream = urlConnection.getInputStream
        totalSize = urlConnection.getContentLength
        loadedSize = 0

        var buffer: Array[Byte] = new Array[Byte](1024)
        var bufferLength = Int.MaxValue; //used to store a temporary size of the buffer
        while (!killed && bufferLength > 0) {
          while(!running && !killed) {
            Thread.sleep(500)
          }
          bufferLength = inputStream.read(buffer)
          if (!killed && bufferLength > 0) {
            fileOutput.write(buffer, 0, bufferLength)
            loadedSize += bufferLength
            reportListener foreach (_ ! DownloadProgressEvent(loadedSize, totalSize))
          }
        }

        fileOutput.close()
        if (killed && outFile.exists()) {
          outFile.delete()
        }
      } catch {
        case e: Exception => e.printStackTrace()
      }
      context.become(awaitingDownload)
      true
    }
  }
}
