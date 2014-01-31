package com.danosipov.asyncandroid.actordownloader

import android.app.Application
import java.io.{InputStreamReader, BufferedReader}
import akka.actor.{Inbox, Props, ActorSystem}
import com.typesafe.config.ConfigFactory

class DownloadApplication extends Application {
  var actorSystem: ActorSystem = null

  override def onCreate() {
    super.onCreate()

    // Start up Akka system for the application
    val actorConf = new BufferedReader(new InputStreamReader((getResources().openRawResource(R.raw.akka))))
    actorSystem = ActorSystem("app", ConfigFactory.load(ConfigFactory.parseReader(actorConf)))
    val inbox = Inbox.create(actorSystem)
  }

  override def onTerminate() {
    super.onTerminate()
    actorSystem.shutdown()
  }

}
