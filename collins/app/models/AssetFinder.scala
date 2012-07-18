package models

import conversions._

import org.squeryl.PrimitiveTypeMode._
import org.squeryl.dsl.ast.{BinaryOperatorNodeLogicalBoolean, LogicalBoolean}
import util.plugins.solr._
import IpmiInfo.Enum._

import java.util.Date

case class AssetFinder(
  tag: Option[String],
  status: Option[Status.Enum],
  assetType: Option[AssetType.Enum],
  createdAfter: Option[Date],
  createdBefore: Option[Date],
  updatedAfter: Option[Date],
  updatedBefore: Option[Date])
{
  def asLogicalBoolean(a: Asset): LogicalBoolean = {
    val tagBool = tag.map((a.tag === _))
    val statusBool = status.map((a.status === _.id))
    val typeBool = assetType.map((a.asset_type === _.id))
    val createdAfterTs = createdAfter.map((a.created gte _.asTimestamp))
    val createdBeforeTs = createdBefore.map((a.created lte _.asTimestamp))
    val updatedAfterTs = Some((a.updated gte updatedAfter.map(_.asTimestamp).?))
    val updatedBeforeTs = Some((a.updated lte updatedBefore.map(_.asTimestamp).?))
    val ops = Seq(tagBool, statusBool, typeBool, createdAfterTs, createdBeforeTs, updatedAfterTs,
      updatedBeforeTs).filter(_ != None).map(_.get)
    ops.reduceRight((a,b) => new BinaryOperatorNodeLogicalBoolean(a, b, "and"))
  }

  /**
   * converts the finder into a sequence of key/value tuples, used as part of forwarding searches
   * to remote collins instances (see RemoteAssetFinder for why it's not a map)
   */
  def toSeq: Seq[(String, String)] = {
    val items:Seq[Option[(String, String)]] = (
      tag.map{"tag" -> _} ::
      status.map{"status" -> _.toString} ::
      assetType.map{"type" -> _.toString} ::
      createdAfter.map{"createdAfter" -> _.toString} ::
      createdBefore.map{"createdBefore" -> _.toString} ::
      updatedAfter.map{"updatedAfter" -> _.toString} ::
      updatedBefore.map{"updatedBefore" -> _.toString} ::
      Nil
    )
    items.flatten
  }

  def toSolrKeyVals = {
    val items = tag.map{t => SolrKeyVal("tag", SolrStringValue(t))} ::
      status.map{t => SolrKeyVal("status" , SolrStringValue(t.toString))} ::
      assetType.map{t => SolrKeyVal("assetType" , SolrStringValue(t.toString))} ::
      Nil
    val cOpt = (createdBefore.map{d =>SolrStringValue(d.toString)}, createdAfter.map{d =>SolrStringValue(d.toString)}) match {
      case (None, None) => None
      case (bOpt, aOpt) => Some(SolrKeyRange("created", bOpt, aOpt))
    }
    val uOpt = (updatedBefore.map{d =>SolrStringValue(d.toString)}, updatedAfter.map{d =>SolrStringValue(d.toString)}) match {
      case (None, None) => None
      case (bOpt, aOpt) => Some(SolrKeyRange("updated", bOpt, aOpt))
    }
    (cOpt :: uOpt :: items).flatten
  }


}

object AssetFinder {

  val empty = AssetFinder(None,None,None,None,None,None,None)
}
