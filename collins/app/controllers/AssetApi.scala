package controllers

import views.html
import models.{Status => AStatus}
import models._
import util._

import play.api.data._
import play.api.data.Forms._
import play.api.http.{Status => StatusValues}
import play.api.mvc._
import play.api.libs.json._

import java.util.Date

trait AssetApi {
  this: Api with SecureController =>

  // GET /api/asset/:tag
  def getAsset(tag: String) = Authenticated { user => Action { implicit req =>
    val result = Api.withAssetFromTag(tag) { asset =>
      val exposeCredentials = user.get.canSeePasswords
      val allAttributes = asset.getAllAttributes.exposeCredentials(exposeCredentials)
      Right(ResponseData(Results.Ok, allAttributes.toJsonObject, attachment = Some(allAttributes)))
    }
    result match {
      case Left(err) =>
        if (OutputType.isHtml(req)) {
          Redirect(app.routes.Resources.index).flashing(
            "message" -> ("Could not find asset with tag " + tag)
          )
        } else {
          formatResponseData(err)
        }
      case Right(success) =>
        if (OutputType.isHtml(req)) {
          val attribs = success.attachment.get.asInstanceOf[Asset.AllAttributes]
          Results.Ok(html.asset.show(attribs, user.get))
        } else {
          formatResponseData(success)
        }
    }
  }}(Permissions.AssetApi.GetAsset)

  // GET /api/assets?params
  private val finder = new actions.FindAsset()
  def getAssets(page: Int, size: Int, sort: String, details: String) = SecureAction { implicit req =>
    val detailsBoolean = details.trim.toLowerCase match {
      case "true" | "1" | "yes" => true
      case _ => false
    }
    val rd = finder(page, size, sort) match {
      case Left(err) => Api.getErrorMessage(err)
      case Right(success) =>
        actions.FindAsset.formatResultAsRd(success, detailsBoolean)
    }
    formatResponseData(rd)
  }(Permissions.AssetApi.GetAssets)

  // PUT /api/asset/:tag
  private val assetCreator = new actions.CreateAsset()
  def createAsset(tag: String) = SecureAction { implicit req =>
    formatResponseData(assetCreator(tag))
  }(Permissions.AssetApi.CreateAsset)

  // POST /api/asset/:tag
  def updateAsset(tag: String) = SecureAction { implicit req =>
    actions.UpdateAsset.get().execute(tag) match {
      case Left(l) => formatResponseData(l)
      case Right(s) => formatResponseData(Api.statusResponse(s))
    }
  }(Permissions.AssetApi.UpdateAsset)

  def updateAssetForMaintenance(tag: String) = SecureAction { implicit req =>
    def processRequest(status: String, reason: String) = {
      Asset.findByTag(tag).map { asset =>
        if (status.isEmpty || reason.isEmpty) {
          Api.getErrorMessage("status and reason must be specified")
        } else {
          val st = if (status.toLowerCase == "maintenance") {
            plugins.Maintenance.toMaintenance(asset, reason)
          } else {
            plugins.Maintenance.fromMaintenance(asset, reason, status)
          }
          st match {
            case true => Api.statusResponse(true)
            case false => Api.getErrorMessage("Failed setting status")
          }
        }
      }.getOrElse(Api.getErrorMessage("Asset with specified tag not found"))
    }
    Form(tuple(
      "status" -> text,
      "reason" -> text
    )).bindFromRequest.fold(
      err => formatResponseData(Api.getErrorMessage("status and reason must be specified")),
      succ => formatResponseData(processRequest(succ._1, succ._2))
    )
  }(Permissions.AssetApi.UpdateAssetForMaintenance)

  // DELETE /api/asset/attribute/:attribute/:tag
  def deleteAssetAttribute(tag: String, attribute: String) = SecureAction { implicit req =>
    Api.withAssetFromTag(tag) { asset =>
      val gid = Form("groupId" -> optional(number(0))).bindFromRequest.fold(
        err => None,
        gid => gid.map(_.toString)
      )
      val updateMap = Map(attribute -> "") ++ gid.map(g => Map("groupId" -> g)).getOrElse(Map.empty)
      AssetLifecycle.updateAssetAttributes(asset, updateMap)
      .left.map(err => Api.getErrorMessage("Error deleting asset attributes", Results.InternalServerError, Some(err)))
      .right.map(status => Api.statusResponse(status, Results.Status(StatusValues.ACCEPTED)))
    }.fold(l => l, r => r).map(s => formatResponseData(s))
  }(Permissions.AssetApi.DeleteAssetAttribute)

  // DELETE /api/asset/:tag
  def deleteAsset(tag: String) = SecureAction { implicit req =>
    val options = Form("reason" -> optional(text(1))).bindFromRequest.fold(
      err => None,
      reason => reason.map { r => Map("reason" -> r) }
    ).getOrElse(Map.empty)
    val result = Api.withAssetFromTag(tag) { asset =>
      AssetLifecycle.decommissionAsset(asset, options)
        .left.map { e =>
          val msg = "Illegal state transition: %s".format(e.getMessage)
          Api.getErrorMessage(msg, Results.Status(StatusValues.CONFLICT))
        }
        .right.map(s => ResponseData(Results.Ok, JsObject(Seq("SUCCESS" -> JsBoolean(s)))))
    }
    val responseData = result.fold(l => l, r => r)
    formatResponseData(responseData)
  }(Permissions.AssetApi.DeleteAsset)


}
