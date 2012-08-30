package util
package config

import com.typesafe.config.{ConfigException, ConfigFactory, ConfigObject}
import play.api.Configuration
import java.io.File
import scala.collection.JavaConverters._

object ConfigWatch extends FileWatcher with ApplicationConfiguration {

  def config = appConfig()

  private lazy val rootConfig: String = Option(config.underlying.origin.filename).orElse {
    val cfg = config.underlying
    val refValue = cfg.getValue("application.secret")
    val origin = refValue.origin
    Option(origin.filename)
  }.getOrElse {
    throw new Exception("Config has no file based origin, can not watch for changes")
  }

  private lazy val otherWatches: Map[String,FileWatcher] = {
    val cfg = config.underlying
    val files = cfg.root.unwrapped.asScala.keys.map { key =>
      Option(cfg.root.get(key).origin.filename)
    }.filter(_.isDefined).map(_.get).toSet - rootConfig
    files.map { file =>
      logger.info("Setting up watch on %s".format(file))
      file -> FileWatcher.watch(file, 15, true) { f =>
        onChange(new File(rootConfig))
      }
    }.toMap
  }

  override def delayInitialCheck = true
  override def millisBetweenFileChecks = 15000L
  override def filename = {
    // Do not change this ordering
    val rc = rootConfig
    otherWatches.foreach { case(key,value) => value.tick }
    rc
  }

  override def onError(file: File) {
  }
  override def onChange(file: File) {
    try {
      val config = ConfigFactory.load(
        ConfigFactory.parseFileAnySyntax(file)
      ) // this is what Play does
      Registry.onChange(config)
    } catch {
      case e =>
        logger.warn("Error loading configuration from %s: %s".format(
          file.toString, e.getMessage
        ), e)
    }
  }
}
