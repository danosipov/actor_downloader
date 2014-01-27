// Include the Android plugin
androidDefaults

// Name of your app
name := "AndroidActors"

// Version of your app
version := "0.1"

// Version number of your app
versionCode := 0

// Version of Scala
scalaVersion := "2.10.1"

// Version of the Android platform SDK
platformName := "android-18"

proguardOptions := Seq(
      "-keepclassmembers class * { ** MODULE$; }",
      "-keep class scala.Option",
      "-keep class scala.Function1",
      "-keep class scala.PartialFunction",
      "-keep class akka.**",
      "-keep class com.eaio.**",
      "-keep class java.util.concurrent.** { <init>(...); }",
      "-keep class akka.** { <init>(...); }",
      "-keep class com.typesafe.config.Config { <init>(...); }",
      "-keepclassmembers class com.eaio.**",
      "-keepclassmembers class akka.**",
      "-keep class org.omg.**",
      "-keep class scala.Tuple2",
      "-dontskipnonpubliclibraryclassmembers",
      "-dontskipnonpubliclibraryclasses")

resolvers ++= Seq(
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  "Maven Central Server" at "http://repo1.maven.org/maven2"
)
 
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.3"
)
