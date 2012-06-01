package models

import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.WS
import play.api._
import play.api.cache.Cache
import play.api.mvc._
import play.api.Play.current

/**
 * Just a combination of everything needed to do a search.  Probably should
 * combine all this in the future somehow
 *
 * This does not include pagination since pagination for a single remote
 * instance is different from the pagination of the overall request
 */
case class AssetSearchParameters(
  params: util.AttributeResolver.ResultTuple, 
  afinder: AssetFinder, 
  operation: Option[String] = None //"and" or "or"

) {
  def toQueryString: Map[String, String] = {
    val q1: Map[String, String] = (
      params._1.map{case (enum, value) => (enum.toString, value)} ++ 
      params._2.map{case (assetMeta,value) => ("attribute" -> "%s;%s".format(assetMeta.name, value))} ++ 
      params._3.map{i => ("ip_address" -> i)}
    ).toMap ++ afinder.toMap + ("details" -> "true")
    operation.map{op => q1 + ("operation" -> op)}.getOrElse(q1)
  }

  def paginationKey = toQueryString.map{case (k,v) => k + "_" + v}.mkString("&")

}

/**
 * Executes the actual search request to a remote collins instance, returning a
 * sequence of result RemoteAssets on success
 */
trait RemoteAssetClient {
  val tag: String //identifier to match up with cached paginations
  def getRemoteAssets(params: AssetSearchParameters, page: PageParams): Seq[AssetView]
  def getTotal: Long
}

class HttpRemoteAssetClient(val host: String, val user: String, val pass: String) extends RemoteAssetClient {
  val tag = host

  val queryUrl = host + app.routes.Api.getAssets().toString
  val authenticationTuple = (user, pass, com.ning.http.client.Realm.AuthScheme.BASIC)

  var total: Option[Long] = None

  def getTotal = total.getOrElse(0)

  def getRemoteAssets(params: AssetSearchParameters, page: PageParams) = {
    Logger.logger.debug("retrieving assets from %s: %s".format(host, page.toString))
    val request = WS.url(queryUrl).copy(
      queryString = params.toQueryString ++ page.toQueryString,
      auth = Some(authenticationTuple)
    )
    val result = request.get.await.get
    val json = Json.parse(result.body)
    total = (json \ "data" \ "Pagination" \ "TotalResults").asOpt[Long]
    (json \ "data" \ "Data") match {
      case JsArray(items) => items.map{
        case obj: JsObject => Some(new RemoteAsset(host, obj))
        case _ => {
          Logger.logger.warn("Invalid asset in response data")
          None
        }
      }.flatten
      case _ => {
        Logger.logger.warn("Invalid response from %s".format(host))
        Nil
      }
    }
  }

}

//class MockRemoteAssetClient

object LocalAssetClient extends RemoteAssetClient {
  val tag = "local"

  var total = 0L

  def getRemoteAssets(params: AssetSearchParameters, page: PageParams) = {
    val localPage = Asset.find(page, params.params, params.afinder, params.operation)
    total = localPage.total
    localPage.items
  }

  def getTotal = total
}


/**
 * A peek-able queue of assets from a remote location.  Assets are read from
 * the queue one at a time, but it will fetch remote assets in pages
 * from the remote collins instance as needed
 *
 */
class RemoteAssetQueue(val client: RemoteAssetClient, val params: AssetSearchParameters) {

  val PAGE_SIZE = 20
  val SORT = "ASC"

  val cachedAssets = new collection.mutable.Queue[AssetView]
  var nextRetrievedPage: Option[Int] = None
  var eof = false
  
  private[this] def retrieveHead: Option[AssetView] = cachedAssets.headOption match {
    case None if (!eof) => {
      val page = nextRetrievedPage.getOrElse(0)
      val pageParams = PageParams(page, PAGE_SIZE, SORT)
      val results = client.getRemoteAssets(params, pageParams)
      if (results.size > 0) {
        cachedAssets ++= results
        nextRetrievedPage = Some(page + 1)
        cachedAssets.headOption
      } else {
        eof = true
        None
      }
    }
    case someOrNone => someOrNone
  }

  /**
   * returns the next asset without removing it from the stream
   */
  def peek: Option[AssetView] = retrieveHead

  /**
   * returns the next asset and removes it from the stream
   */
  def get: Option[AssetView] = retrieveHead.map{h => cachedAssets.dequeue}
}


/**
 * A stream of assets pulled from multiple collins instances and combined using
 * merge-sort.  Backed using a Scala Stream for memoization
 */
class RemoteAssetStream(clients: Seq[RemoteAssetClient], searchParams: AssetSearchParameters) {
  
  val queues = clients.map{client => new RemoteAssetQueue(client, searchParams)}

  /**
   * Returns the ordering to merge-sort assets.  currently you cannot specify a
   * sort key in the API, so for now sorting is just based on tag alphabetical
   * order
   */
  def getOrdering: Ordering[AssetView] = new Ordering[AssetView] {
    def compare(a: AssetView, b: AssetView) = {
      Logger.logger.debug("comparing %s to %s".format(a.tag, b.tag))
      a.tag compareToIgnoreCase b.tag
    }
  }

  implicit val ordering = getOrdering

  /**
   * uses merge-sort to grab the next item
   */
  private[this] def getNextAsset: Option[AssetView] = queues
    .map{ s => s.peek.map{p => (s,p)}}
    .flatten
    .sortBy(_._2)
    .headOption
    .flatMap{_._1.get}

  /**
   * Create an infinite stream of assets
   * (see http://www.scala-lang.org/api/current/scala/collection/immutable/Stream.html)
   */
  val assets: Stream[Option[AssetView]] = {
    def n(asset: Option[AssetView]): Stream[Option[AssetView]] = asset #:: n(getNextAsset)
    n(getNextAsset)
  }

  def aggregateTotal: Long = clients.map{_.getTotal}.sum

  /** 
   * NOTE - this will not scale past a few thousand total assets when doing
   * searches that return large numbers of assets and requests are made for
   * high offsets in the result set, after that we'll need some kind of search
   * index
   */
  def slice(from: Int, to: Int): Seq[AssetView] = assets.slice(from, to).flatten

}

object RemoteAssetFinder {


  /**
   */
  def apply(clients: Seq[RemoteAssetClient], pageParams: PageParams, searchParams: AssetSearchParameters): (Seq[AssetView], Long) = {
    val key = searchParams.paginationKey + clients.map{_.tag}.mkString("_")
    val stream = Cache.getAs[RemoteAssetStream](key).getOrElse(new RemoteAssetStream(clients, searchParams))
    val results = stream.slice(pageParams.page * pageParams.size, (pageParams.page +1) * (pageParams.size))
    Cache.set(key, stream, 30)
    (results, stream.aggregateTotal)
  }
    
}
