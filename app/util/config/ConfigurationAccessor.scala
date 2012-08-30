package util
package config

import com.typesafe.config.{Config => TypesafeConfig}
import com.typesafe.config.{ConfigException, ConfigObject}
import scala.collection.JavaConverters._
import java.util.concurrent.atomic.AtomicReference

// Provide access to values from an underlying configuration
trait ConfigurationAccessor {

  private val _underlying: AtomicReference[Option[TypesafeConfig]] = new AtomicReference(None)

  protected def underlying = {
    ConfigWatch.tick
    _underlying.get()
  }

  protected def underlying_=(config: Option[TypesafeConfig]) {
    _underlying.set(config)
  }

  protected def getBoolean(key: String): Option[Boolean] = getValue(key, _.getBoolean(key))
  protected def getBoolean(key: String, default: Boolean): Boolean =
    getBoolean(key).getOrElse(default)

  protected def getInt(key: String): Option[Int] = getValue(key, _.getInt(key))

  protected def getIntList(key: String): List[Int] =
    getValue(key, _.getIntList(key).asScala.toList.map(_.toInt)).getOrElse(List())

  protected def getMilliseconds(key: String): Option[Long] = getValue(key, _.getMilliseconds(key))

  protected def getObjectMap(key: String): Map[String,ConfigObject] =
    getValue(key, _.getObject(key).toConfig.root.asScala.map {
      case(key: String, value: ConfigObject) => (key -> value)
    }).getOrElse(Map.empty[String,ConfigObject]).toMap

  protected def getString(key: String, default: String): String =
    getString(key)(ConfigValue.Optional).getOrElse(default)

  protected def getString(key: String)(implicit cfgv: ConfigurationRequirement): Option[String] =
    getValue(key, _.getString(key)) match {
      case None =>
        cfgv match {
          case ConfigValue.Required =>
            throw new Exception("Required configuration %s not found".format(key))
          case _ =>
            None
        }
      case o =>
        o
    }

  protected def getStringList(key: String): List[String] =
    getValue(key, _.getStringList(key).asScala.toList).getOrElse(List())

  protected def getStringSet(key: String): Set[String] = getStringList(key).toSet

  private def getValue[V](path: String, p: TypesafeConfig => V): Option[V] = try {
    underlying.flatMap(cfg => Option(p(cfg)))
  } catch {
    case e: ConfigException.Missing =>
      None
  }
}
