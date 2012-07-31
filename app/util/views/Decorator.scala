package util
package views

import models.MetaWrapper

import play.api.Configuration
import play.api.mvc.Content

case class DecoratorConfigurationException(source: String, key: String)
  extends Exception("Didn't find key %s in decorator configuration for %s".format(key, source))

case class Decorator(decorator: String, parser: DecoratorParser, config: Configuration) {
  private val delimiter: String = config.getString("between").getOrElse("")
  def format(key: String, value: String): String = {
    parser.parse(value).zipWithIndex.map { case(v, i) =>
      newString(key, v, i)
    }.mkString(delimiter)
  }
  def format(key: String, values: Seq[String]): String = {
    values.map { value =>
      parser.parse(value).zipWithIndex.map { case(v, i) =>
        newString(key, v, i)
      }.mkString(delimiter)
    }.mkString(delimiter)
  }
  def format(meta: MetaWrapper): String = {
    parser.parse(meta.getValue).zipWithIndex.map { case(v, i) =>
      newString(v, i, meta)
    }.mkString(delimiter)
  }
  protected def newString(key: String, value: String, idx: Int) = {
    val idxConfig = config.getConfig(idx.toString)
    val replacers: Seq[(String,String)] = Seq(
      ("name", key),
      ("value", value)
    ) ++ idxConfig.map { cfg =>
      cfg.keys.foldLeft(Seq[(String,String)]()) { case(total,current) =>
        Seq(("i.%s".format(current), cfg.getString(current).get)) ++ total
      }
    }.getOrElse(Seq[(String,String)]())
    replacers.foldLeft(decorator) { case(total, current) =>
      total.replace("{%s}".format(current._1), current._2)
    }
  }
  protected def newString(value: String, idx: Int, meta: MetaWrapper) = {
    val idxConfig = config.getConfig(idx.toString) // with keys like label, thingamajig, etc
    val replacers: Seq[(String,String)] = Seq(
      ("name", meta.getName),
      ("label", meta.getLabel),
      ("description", meta.getDescription),
      ("value", value)
    ) ++ idxConfig.map { cfg =>
      cfg.keys.foldLeft(Seq[(String,String)]()) { case(total,current) =>
        Seq(("i.%s".format(current), cfg.getString(current).get)) ++ total
      }
    }.getOrElse(Seq[(String,String)]())
    replacers.foldLeft(decorator) { case(total, current) =>
      total.replace("{%s}".format(current._1), current._2)
    }
  }
}
