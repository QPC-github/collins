package controllers
package actions
package ipaddress

import models.{Asset, IpAddresses}
import util.{ApiTattler, SecuritySpecification}

import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._

// Allocate addresses for an asset
case class CreateAction(
  assetTag: String,
  spec: SecuritySpecification,
  handler: SecureController
) extends SecureAction(spec, handler) with AssetAction with AddressActionHelper {

  case class ActionDataHolder(asset: Asset, pool: String, count: Int) extends RequestDataHolder

  val dataForm = Form(tuple(
    "pool" -> optional(text),
    "count" -> optional(number(1, 10))
  ))

  override def validate(): Validation = withValidAsset(assetTag) { asset =>
    dataForm.bindFromRequest()(request).fold(
      err  => Left(RequestDataHolder.error400("Invalid pool or count specified")),
      form => {
        val (pool, count) = form
        val poolName = pool.map(convertPoolName(_))
        if (!IpAddresses.AddressConfig.isDefined)
          return Left(
            RequestDataHolder.error500("No address pools have been setup to allocate from")
          )
        poolName.filter(_.nonEmpty).foreach { p =>
          if (!IpAddresses.AddressConfig.get.hasPool(p)) {
            return Left(RequestDataHolder.error404("Pool %s does not exist".format(p)))
          }
        }
        Right(ActionDataHolder(asset, poolName.getOrElse(""), count.getOrElse(1)))
      }
    )
  }

  override def execute(rd: RequestDataHolder) = rd match {
    case ActionDataHolder(asset, pool, count) => try {
      val created = IpAddresses.createForAsset(asset, count, Some(pool))
      ApiTattler.notice(asset, userOption, "Created %d IP addresses".format(created.size))
      ResponseData(Status.Created, created.toJson)
    } catch {
      case e => handleError(RequestDataHolder.error500("Possible IP address conflict"))
    }
  }
}
