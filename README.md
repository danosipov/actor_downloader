Actor Downloader
================
This application demonstrates the use of [Akka framework](http://akka.io) on Android.

Building
--------------------

To build and run this application either on an emulator or a real device you need to have [SBT](http://www.scala-sbt.org) and the [Android SDK](https://developer.android.com/sdk/index.html)

When you run

```
$ echo $ANDROID_HOME
```

Make sure the path to the Android SDK is printed out. If not do:

```
$ export ANDROID_HOME=/path/to/android-sdk
```

If you don't have android SBT plugin, checkout [android-plugin](https://github.com/jberkel/android-plugin) and publish it locally:

```
$ git clone git://github.com/jberkel/android-plugin.git
$ cd android-plugin
$ sbt publish-local
```

This will create plugin of version 0.7.1-SNAPSHOT. You can now go back to actor_downloader and run:

```
$ sbt start
```

If you have the device connected or emulator running (verify with ```adb devices```), it will deploy and start the APK.

