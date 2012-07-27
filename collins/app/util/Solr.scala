package util.plugins
package solr

import akka.actor._
import akka.util.duration._

import models.{Asset, AssetFinder, AssetMeta, AssetMetaValue, AssetType, AssetView, IpAddresses, MetaWrapper, Page, PageParams, Status, Truthy}
import models.IpmiInfo.Enum._

import org.apache.solr.client.solrj.{SolrServer, SolrQuery}
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer
import org.apache.solr.common.{SolrDocument, SolrInputDocument}
import org.apache.solr.core.CoreContainer
import org.apache.solr.client.solrj.impl.{CommonsHttpSolrServer, XMLResponseParser}

import play.api.{Application, Configuration, Logger, Play, PlayException, Plugin}
import play.api.libs.concurrent._
import play.api.libs.concurrent.Akka._
import play.api.Play.current

import util.plugins.Callback
import util.views.Formatter

import scala.util.parsing.combinator._

class SolrPlugin(app: Application) extends Plugin {

  private[this] var _server: Option[SolrServer] = None

  def server = _server match {
    case Some(server) => server
    case None => throw new RuntimeException("Attempted to get Solr server when no server is initialized")
  }

  private def config = app.configuration.getConfig("solr")

  lazy val solrHome = config.flatMap{_.getString("embeddedSolrHome")}.getOrElse(throw new IllegalArgumentException("No solrHome set!"))
  override lazy val enabled = config.flatMap{_.getBoolean("enabled")}.getOrElse(false)
  lazy val useEmbedded = config.flatMap{_.getBoolean("useEmbeddedServer")}.getOrElse(true)
  lazy val repopulateOnStartup = config.flatMap{_.getBoolean("repopulateOnStartup")}.getOrElse(false)
  lazy val reactToUpdates = config.flatMap{_.getBoolean("reactToUpdates")}.getOrElse(true)
  lazy val remoteUrl: Option[String] = config.flatMap{_.getString("externalUrl")}

  val serializer = new FlatSerializer

  //this must be lazy so it gets called after the system exists
  lazy val updater = Akka.system.actorOf(Props[SolrUpdater], name = "solr_updater")


  override def onStart() {
    if (enabled) {
      System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.SimpleLog");
      System.setProperty("org.apache.commons.logging.simplelog.showdatetime", "true");
      System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http", "WARN");
      System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http.wire", "WARN");
      System.setProperty("org.apache.commons.logging.simplelog.http.wire", "WARN");
      System.setProperty("org.apache.commons.logging.simplelog.http.wire.content", "WARN");
      System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.commons.httpclient", "WARN");
      _server = Some(if (useEmbedded) {
        Solr.getNewEmbeddedServer(solrHome)
      } else {
        Solr.getNewRemoteServer(remoteUrl.getOrElse(throw new IllegalArgumentException("Missing required solr.externalUrl")))
      })

      if (repopulateOnStartup) {
        populate()
      }
      if (reactToUpdates) {
        initializeCallbacks()
      }
    }
  }

  /**
   * Setup callbacks on all operations that modify asset data, so we can
   * properly reindex the updated asset in Solr
   */
  private def initializeCallbacks() {
    val callback: java.beans.PropertyChangeEvent => Unit = event => event.getNewValue match {
      case a: Asset => if (a.deleted.isDefined) {
        removeAssetByTag(a.tag) //deletes are soft right now
      } else {
        //Logger.logger.debug("preparing to index asset " + a.toString
        updater ! a
      }
      case v: AssetMetaValue => updater ! v.getAsset
      case i: IpAddresses => updater ! i.getAsset
      case null => event.getOldValue match {
        case a: Asset => removeAssetByTag(a.tag)
        case v: AssetMetaValue => updater ! v.getAsset
        case i: IpAddresses => updater ! i.getAsset
        case other => Logger.logger.error("Unknown old value in update callback %s".format((if (other == null) "null" else other.toString)))
      }
      case other => Logger.logger.error("Unknown new value in update callback %s".format(other.toString))      
    }
    Callback.on("asset_update")(callback)
    Callback.on("asset_create")(callback)
    Callback.on("asset_delete")(callback)
    Callback.on("asset_meta_value_create")(callback)
    Callback.on("asset_meta_value_delete")(callback)
    Callback.on("ipAddresses_create")(callback)
    Callback.on("ipAddresses_update")(callback)
    Callback.on("ipAddresses_delete")(callback)

  }

  def populate() = Akka.future { 
    _server.map{ server => 
      //server.deleteByQuery( "*:*" );
      Logger.logger.debug("Populating Solr with Assets")
      updateAssets(Asset.find(PageParams(0,10000,"asc"), AssetFinder.empty).items.collect{case a: Asset => a})
    }.getOrElse(Logger.logger.warn("attempted to populate solr when no server was initialized"))
  }

  def updateAsset(asset: Asset) = {
    Logger.logger.debug("updating asset " + asset.toString)
    //updateAssets(asset :: Nil)
  }

  def updateAssets(assets: Seq[Asset]) {
    _server.map{server =>
      val docs = assets.map{asset => Solr.prepForInsertion(serializer.serialize(asset))}
      if (docs.size > 0) {
        val fuckingJava = new java.util.ArrayList[SolrInputDocument]
        docs.foreach{doc => fuckingJava.add(doc)}
        server.add(fuckingJava)
        server.commit()
        if (assets.size == 1) {
          Logger.logger.debug("Re-indexing asset " + assets.head.toString)
        } else {
          Logger.logger.info("Indexed %d assets".format(docs.size))
        }
      } else {
        Logger.logger.warn("No assets to index!")
      }
    }
  }

  def removeAssetByTag(tag: String) {
    _server.map{server => 
      if (tag != "*") {
        server.deleteByQuery("TAG:" + tag)
        server.commit()
        Logger.logger.info("Removed asset %s from index".format(tag))
      }
    }
  }

  override def onStop() {
    _server.foreach{case s: EmbeddedSolrServer => s.shutdown}
  }



}

/**
 * The SolrUpdater queues asset updates for batch updating.  Most importantly,
 * if it receives multiple requests of the same asset, it will only update the
 * asset once per batch.  This is to avoid reindexing an asset many times
 * during large updates (such as updating lshw/lldp, which triggers dozens of
 * callbacks)
 */
class SolrUpdater extends Actor {

  var queue = new collection.mutable.Queue[Asset]

  //mutex to prevent multiple concurrent scheduler calls
  var scheduled = false

  case object Reindex

  /**
   * Note, even though the callback checks if the asset is deleted, we're still
   * gonna get index requests from the delete asset's meta value deletions
   *
   * Note - also we re-fetch the asset from MySQL to avoid a possible race
   * condition where an asset is deleted immediately after it is updated
   */
  def receive = {
    case asset: Asset => if ((!queue.contains(asset)) && Asset.findByTag(asset.tag).map{_.deleted.isDefined == false}.getOrElse(false)) {
      queue += asset
      if (!scheduled) {
        context.system.scheduler.scheduleOnce(10 milliseconds, self, Reindex)
        scheduled = true
      }
    }
    case Reindex => {
      Solr.plugin.foreach{_.updateAssets(queue.toSeq)}
      queue = new collection.mutable.Queue[Asset]
      scheduled = false
    }
  }
}


object Solr {

  def plugin: Option[SolrPlugin] = Play.maybeApplication.flatMap{_.plugin[SolrPlugin]}.filter{_.enabled}

  protected def inPlugin(f: SolrPlugin => Unit): Unit = {
    Play.maybeApplication.foreach { app =>
      app.plugin[SolrPlugin].foreach{plugin=>
        f(plugin)
      }
    }
  }

  def initialize() = inPlugin {_.initialize}

  def populate() = inPlugin {_.populate()}

  def updateAssets(assets: Seq[Asset]) = inPlugin {_.updateAssets(assets)}

  def updateAsset(asset: Asset){updateAssets(asset :: Nil)}

  def updateAssetByTag(tag: String) = Asset.findByTag(tag, false).foreach{updateAsset}

  def removeAssetByTag(tag: String) = inPlugin {_.removeAssetByTag(tag)}
    
  type AssetSolrDocument = Map[String, SolrValue]

  def prepForInsertion(typedMap: AssetSolrDocument): SolrInputDocument = {
    val untyped = typedMap.map{case(k,v) => (k,v.value)}
    val input = new SolrInputDocument
    untyped.foreach{case(key,value) => input.addField(key,value)}
    input
  }

  def server: Option[SolrServer] = Play.maybeApplication.flatMap { app =>
    app.plugin[SolrPlugin].filter(_.enabled).map{_.server}
  }

  private[solr] def getNewEmbeddedServer(solrHome: String) = {
    System.setProperty("solr.solr.home",solrHome) // (╯°□°)╯︵ɐʌɐɾ
    val initializer = new CoreContainer.Initializer()
    val coreContainer = initializer.initialize()
    Logger.logger.debug("Booting embedded Solr Server with solrhome " + solrHome)
    new EmbeddedSolrServer(coreContainer, "")
  }

  private[solr] def getNewRemoteServer(remoteUrl: String) = {
    //out-of-the-box config from solrj wiki
    Logger.logger.debug("Using external Solr Server")
    val server = new CommonsHttpSolrServer(remoteUrl);
    Logger.logger.debug("test")
    server.setSoTimeout(1000);  // socket read timeout
    server.setConnectionTimeout(100);
    server.setDefaultMaxConnectionsPerHost(100);
    server.setMaxTotalConnections(100);
    server.setFollowRedirects(false);  // defaults to false
    // allowCompression defaults to false.
    // Server side must support gzip or deflate for this to have any effect.
    server.setAllowCompression(true);
    server.setMaxRetries(1); // defaults to 0.  > 1 not recommended.
    server.setParser(new XMLResponseParser()); // binary parser is used by default
    server
  }

}

import Solr.AssetSolrDocument
import AssetMeta.ValueType
import AssetMeta.ValueType._

/**
 * Any class mixing in this trait is part of the CQL AST that must translate
 * itself into a Solr Query
 *
 * This is a super-trait of SolrExpression becuase SolrValues are
 * QueryComponents, but should not require the typeCheck method of
 * SolrExpression (probably ways to refactor this but whatevah)
 */
sealed trait SolrQueryComponent {
  def toSolrQueryString(): String = toSolrQueryString(true)

  def toSolrQueryString(toplevel: Boolean): String

}

/**
 * Base trait of Solr Value ADT
 *
 * A solr value can either be a typed single value, or a multival containing a
 * seq of single values.  At the moment the Solr schema allows all meta values
 * to be multi-valued
 */
sealed trait SolrValue {
  val value: Any
  val valueType: ValueType
  val postfix: String
}

abstract class SolrSingleValue(val postfix: String, val valueType: ValueType) extends SolrValue with SolrQueryComponent

case class SolrIntValue(value: Int) extends SolrSingleValue("_meta_i", Integer) {
  def toSolrQueryString(toplevel: Boolean) = value.toString
}

case class SolrDoubleValue(value: Double) extends SolrSingleValue("_meta_d", Double) {
  def toSolrQueryString(toplevel: Boolean) = value.toString
}

case class SolrStringValue(value: String) extends SolrSingleValue("_meta_s", String) {
  def toSolrQueryString(toplevel: Boolean) = value
}

case class SolrBooleanValue(value: Boolean) extends SolrSingleValue("_meta_b", Boolean) {
  def toSolrQueryString(toplevel: Boolean) = if (value) "true" else "false"
}

//note, we don't have to bother with checking the types of the contained values
//since that's implicitly handled by AssetMeta
case class SolrMultiValue(values: Seq[SolrSingleValue], valueType: ValueType) extends SolrValue {
  require (values.size > 0, "Cannot create empty multi-value")
  def +(v: SolrSingleValue) = this.copy(values = values :+ v)

  lazy val value = values.map{_.value}.toArray

  lazy val postfix = values.head.postfix

}
object SolrMultiValue {
  def apply(values: Seq[SolrSingleValue]): SolrMultiValue = SolrMultiValue(values, values.headOption.map{_.valueType}.getOrElse(String))
}


trait AssetSolrSerializer {
  def serialize(asset: Asset): AssetSolrDocument

  val generatedFields: Map[String, ValueType]
}

/**
 * asset meta values are all converted into strings with the meta name as the
 * solr key, using group_id to group values in to multi-valued keys
 */
class FlatSerializer extends AssetSolrSerializer {

  val generatedFields = Map("NUM_DISKS" -> Integer)

  def serialize(asset: Asset) = postProcess {
    val opt = Map[String, Option[SolrValue]](
      "UPDATED" -> asset.updated.map{t => SolrStringValue(Formatter.solrDateFormat(t))},
      "DELETED" -> asset.deleted.map{t => SolrStringValue(Formatter.solrDateFormat(t))},
      "IP_ADDRESS" -> {
        val a = IpAddresses.findAllByAsset(asset, false)
        if (a.size > 0) {
          val addresses = SolrMultiValue(a.map{a => SolrStringValue(a.dottedAddress)})
          Some(addresses)
        } else {
          None
        }
      }
    ).collect{case(k, Some(v)) => (k,v)}
      
    opt ++ Map[String, SolrValue](
      "TAG" -> SolrStringValue(asset.tag),
      "STATUS" -> SolrIntValue(asset.status),
      "TYPE" -> SolrIntValue(asset.getType.id),
      "CREATED" -> SolrStringValue(Formatter.solrDateFormat(asset.created))
    ) ++ serializeMetaValues(AssetMetaValue.findByAsset(asset, false))
  }

  
  //FIXME: The parsing logic here is duplicated in AssetMeta.validateValue
  def serializeMetaValues(values: Seq[MetaWrapper]) = {
    def process(build: AssetSolrDocument, remain: Seq[MetaWrapper]): AssetSolrDocument = remain match {
      case head :: tail => {
        val newval = head.getValueType() match {
          case Boolean => SolrBooleanValue((new Truthy(head.getValue())).isTruthy)
          case Integer => SolrIntValue(java.lang.Integer.parseInt(head.getValue()))
          case Double => SolrDoubleValue(java.lang.Double.parseDouble(head.getValue()))
          case _ => SolrStringValue(head.getValue())
        }
        val solrKey = head.getName() + newval.postfix
        val mergedval = build.get(solrKey) match {
          case Some(exist) => exist match {
            case s: SolrSingleValue => SolrMultiValue(s :: newval :: Nil, newval.valueType)
            case m: SolrMultiValue => m + newval
          }
          case None => newval
        }
        process(build + (solrKey -> mergedval), tail)
      }
      case _ => build
    }
    process(Map(), values)
  }

  def postProcess(doc: Map[String, SolrValue]): Map[String, SolrValue] = {
    val disks = doc.get("DISK_SIZE_BYTES_meta_s").map{v => ("NUM_DISKS_meta_i" -> SolrIntValue(v match {
      case s:SolrSingleValue => 1
      case SolrMultiValue(vals, _) => vals.size
    }))}
    val newFields = List(disks).flatten
    doc ++ newFields
  }

}

/*
 * This is the AST and parser for CQL Expressions are basically just
 * primitive-recursive boolean expressions with a few extra operators for
 * various solr functions.  Some exmaples:
 *
 * key = "somevalue"
 * key1 = "a" AND (key2 = 3 OR key3 = true)
 */

/**
 * ADT for AST
 */
sealed trait SolrExpression extends SolrQueryComponent{ 

  /*
   * Performs type-checking and solr-key name resolution: eg converts foo to
   * foo_meta_i.  Returns Right(expr) with the new resolved expression or
   * Left(msg) if there's an error somewhere
   */
  def typeCheck: Either[String, SolrExpression]
}

abstract class SolrMultiExpr(exprs: Seq[SolrExpression], op: String) extends SolrExpression {

  def toSolrQueryString(toplevel: Boolean) = {
    val e = exprs.map{_.toSolrQueryString(false)}.mkString(" %s ".format(op))
    if (toplevel) e else "(%s)".format(e)
  }

  def create(exprs: Seq[SolrExpression]): SolrMultiExpr

  def typeCheck = {
    val r = exprs.map{_.typeCheck}.foldLeft(Right(Nil): Either[String, Seq[SolrExpression]]){(build, next) => build match {
      case l@Left(error) => l
      case Right(seq) => next match {
        case Left(error) => Left(error)
        case Right(expr) => Right(expr +: seq)
      }
    }}
    r.right.map{s => create(s)}
  }

}

case class SolrAndOp(exprs: Seq[SolrExpression]) extends SolrMultiExpr(exprs, "AND") {
  def AND(k: SolrExpression) = SolrAndOp(this :: k :: Nil)

  def create(exprs: Seq[SolrExpression]) = SolrAndOp(exprs)


}

case class SolrOrOp(exprs: Seq[SolrExpression]) extends SolrMultiExpr(exprs, "OR") {
  def OR(k: SolrExpression) = SolrOrOp(this :: k :: Nil)

  def create(exprs: Seq[SolrExpression]) = SolrOrOp(exprs)

}

trait SolrSimpleExpr extends SolrExpression {

  def AND(k: SolrExpression) = SolrAndOp(this :: k :: Nil)
  def OR(k: SolrExpression) = SolrOrOp(this :: k :: Nil)

  /**
   * each key is an "incoming" field from a query, the ValueType is the
   * expected type of the key, and the Boolean indicates whether the key in
   * Solr is static(false) or dynamic(true)
   */
  val nonMetaKeys: Map[String,(ValueType, Boolean)] = Map(
    "TAG" -> (String,false), 
    "CREATED" -> (String,false), 
    "UPDATE" -> (String,false), 
    "DELETED" -> (String,false),
    "IP_ADDRESS" -> (String,false),
    IpmiAddress.toString -> (String, true),
    IpmiUsername.toString -> (String, true),
    IpmiPassword.toString -> (String, true),
    IpmiGateway.toString -> (String, true),
    IpmiNetmask.toString -> (String, true)
  ) ++ Solr.plugin.map{_.serializer.generatedFields.map{case (k,v) => (k,(v,true))}}.getOrElse(Map())

  val enumKeys = Map[String, String => Option[Int]](
    "TYPE" -> ((s: String) => try Some(AssetType.Enum.withName(s.toUpperCase).id) catch {case _ => None}),
    "STATUS" -> ((s: String) => Status.findByName(s).map{_.id})
  )

  def typeLeft(key: String, expected: ValueType, actual: ValueType): Either[String, (String, SolrSingleValue)] = 
    Left("Key %s expects type %s, got %s".format(key, expected.toString, actual.toString))


  type TypeEither = Either[String, (String, SolrSingleValue)]

  /**
   * returns Left(error) or Right(solr_key_name)
   */
  def typeCheckValue(key: String, value: SolrSingleValue):Either[String, (String, SolrSingleValue)] = {
    val ukey = key.toUpperCase
    val a: Option[TypeEither] = nonMetaKeys.get(ukey).map {case (valueType, transformKey) =>
      if (valueType == value.valueType) {
        Right((if (transformKey) ukey + value.postfix else ukey) -> value)

      } else {
        typeLeft(key, valueType, value.valueType)
      }
    } orElse{enumKeys.get(ukey).map{f => value match {
      case SolrStringValue(e) => f(e) match {
        case Some(i) => Right(ukey -> SolrIntValue(i))
        case _ => Left("Invalid %s: %s".format(key, e))
      }
      case s:SolrIntValue => Right(ukey -> value) : Either[String, (String, SolrSingleValue)]
      case other => typeLeft(key, String, other.valueType)
    }}}
    a.getOrElse(AssetMeta.findByName(key) match {
      case Some(meta) => if (meta.valueType == value.valueType) {
        //FIXME: perhaps centralize asset meta key formatting
        Right(ukey + value.postfix -> value)
      } else {
        typeLeft(key, meta.valueType, value.valueType)
      }
      case None => Left("Unknown key \"%s\"".format(key))
    })
  }

}



case class SolrNotOp(expr: SolrExpression) extends SolrSimpleExpr {

  def typeCheck = expr.typeCheck.right.map{e => SolrNotOp(e)}

  def toSolrQueryString(toplevel: Boolean) = "NOT " + expr.toSolrQueryString

}

case class SolrKeyVal(key: String, value: SolrSingleValue) extends SolrSimpleExpr {

  def toSolrQueryString(toplevel: Boolean) = key + ":" + value.toSolrQueryString(false)

  def typeCheck = typeCheckValue(key, value).right.map{case (solrKey, cleanValue) => SolrKeyVal(solrKey, cleanValue)}

}

case class SolrKeyRange(key: String, low: Option[SolrSingleValue], high: Option[SolrSingleValue]) extends SolrSimpleExpr {

  def toSolrQueryString(toplevel: Boolean) = {
    val l = low.map{_.toSolrQueryString}.getOrElse("*")
    val h = high.map{_.toSolrQueryString}.getOrElse("*")
    key + ":[" + l + " TO " + h + "]"
  }

  def t(v: Option[SolrSingleValue]): Either[String, (String, Option[SolrSingleValue])] = v match {
    case None => Right(key, None)//FIXME, need to separate key resolution and value type-checking!!
    case Some(s) => typeCheckValue(key, s) match {
      case Left(e) => Left(e)
      case Right((k,v)) => Right((k,Some(v)))
    }
  }

  def typeCheck = {
    (t(low), t(high)) match {
      case (Right((k,l)), Right((_,h))) => Right(SolrKeyRange(k, l, h))
      case (Left(e), _) => Left(e)
      case (_, Left(e)) => Left(e)
    }
  }


}

/**
 * needs some work
 */
object CollinsQueryDSL {
  class CollinsQueryString(val s: String) {
    lazy val query: SolrExpression = (new CollinsQueryParser).parseQuery(s).right.get
  }
  implicit def str2collins(s: String): CollinsQueryString = new CollinsQueryString(s)
  implicit def collins2str(c: CollinsQueryString): String = c.s
  implicit def int_tuple2keyval(t: Tuple2[String, Int]):SolrKeyVal = SolrKeyVal(t._1, SolrIntValue(t._2))
  implicit def string_tuple2keyval(t: Tuple2[String, String]):SolrKeyVal = SolrKeyVal(t._1, SolrStringValue(t._2))
  implicit def double_tuple2keyval(t: Tuple2[String, Double]):SolrKeyVal = SolrKeyVal(t._1, SolrDoubleValue(t._2))
  implicit def boolean_tuple2keyval(t: Tuple2[String, Boolean]):SolrKeyVal = SolrKeyVal(t._1, SolrBooleanValue(t._2))

  def not(exp: SolrExpression) = SolrNotOp(exp)



}

class CollinsQueryException(m: String) extends PlayException("CQL", m)

/** 
 * Parses CQL strings into a SolrExpression AST
 */
class CollinsQueryParser extends JavaTokenParsers {

  def parseQuery(input: String): Either[String, SolrExpression] = parse(expr, input.trim) match {
    case Success(exp, next) => if (next.atEnd) {
      Right(exp)
    } else {
      Left("Unexpected stuff after query at position %s: %s".format(next.pos.toString, next.first))
    }
    case Failure(wtf, _) => Left("Error parsing query: %s".format(wtf.toString))
  }

  def expr: Parser[SolrExpression] = orOp

  def orOp          = rep1sep(andOp , "(?iu)OR".r) ^^ {i => if (i.tail == Nil) i.head else SolrOrOp(i)}
  def andOp         = rep1sep(simpleExpr , "(?iu)AND".r)  ^^ {i => if (i.tail == Nil) i.head else SolrAndOp(i)}
  def notExpr       = "(?iu)NOT".r ~> simpleExpr ^^ {e => SolrNotOp(e)}
  def simpleExpr:Parser[SolrExpression]    = notExpr | rangeKv | kv | "(" ~> expr <~ ")" 

  //def inKv          = ident ~ "(?iu)IN".r ~> "[" ~>
  def rangeKv       = ident ~ "=" ~ "[" ~ valueOpt ~ "," ~ valueOpt <~ "]" ^^ {case key ~ "=" ~ "[" ~ low ~ "," ~ high => SolrKeyRange(key,low,high)}
  def kv            = ident ~ "=" ~ value ^^{case k ~ "=" ~ v => SolrKeyVal(k,v)}
  def valueOpt: Parser[Option[SolrSingleValue]]      = "*"^^^{None} | value ^^{other => Some(other)}
  def value         = booleanValue | ipAddress | numberValue | stringValue
  def numberValue   = decimalNumber ^^{case n => if (n contains ".") {
    SolrDoubleValue(java.lang.Double.parseDouble(n))
  } else {
    SolrIntValue(java.lang.Integer.parseInt(n))
  }}
  def ipAddress  = """^(\*|[0-9]{1,3}\.(\*|[0-9]{1,3}\.(\*|[0-9]{1,3}\.(\*|[0-9]{1,3}))))$""".r ^^{s => SolrStringValue(s)}
  def stringValue   = quotedString | unquotedString
  def quotedString = stringLiteral  ^^ {s => SolrStringValue(s.substring(1,s.length-1))}
  def unquotedString = "\\*?[a-zA-Z0-9_\\-.]+\\*?".r  ^^ {s => SolrStringValue(s)}
  def booleanValue  = ("true" | "false") ^^ {case "true" => SolrBooleanValue(true) case _ =>  SolrBooleanValue(false)}

}

/**
 * This class is a full search query, which includes an expression along with
 * sorting and pagination parameters
 */
case class CollinsSearchQuery(query: SolrExpression, page: PageParams, sortField: String) {

  def getResults(): Either[String, (Seq[AssetView], Long)] = Solr.server.map{server =>
    val q = new SolrQuery
    val s = query.toSolrQueryString
    Logger.logger.debug("SOLR: " + s)
    q.setQuery(s)
    q.setStart(page.offset)
    q.setRows(page.size)
    q.addSortField(sortField.toUpperCase, (if (page.sort == "ASC") SolrQuery.ORDER.asc else SolrQuery.ORDER.desc))
    val response = server.query(q)
    val results = response.getResults
    Right((results.toArray.toSeq.map{
      case doc: SolrDocument => Asset.findByTag(doc.getFieldValue("TAG").toString)
      case other => {
        Logger.logger.warn("Got something weird back from Solr %s".format(other.toString))
        None
      }
    }.flatten, results.getNumFound))
  }.getOrElse(Left("Solr Plugin not initialized!"))



  def getPage(): Either[String, Page[AssetView]] = getResults().right.map{case (results, total) =>
    Page(results, page.page, page.size, total)
  }

}
