package controllers

import actions.asset.{CreateAction, DeleteAction, DeleteAttributeAction, FindAction, GetAction}
import actions.asset.UpdateForMaintenanceAction

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
  def getAsset(tag: String) = GetAction(tag, Permissions.AssetApi.GetAsset, this)

  // GET /api/assets?params
  def getAssets(page: Int, size: Int, sort: String) = FindAction(
    PageParams(page, size, sort), Permissions.AssetApi.GetAssets, this
  )

  // PUT /api/asset/:tag
  def createAsset(tag: String) = CreateAction(Some(tag), None, Permissions.AssetApi.CreateAsset, this)

  // POST /api/asset/:tag
  def updateAsset(tag: String) = SecureAction { implicit req =>
    actions.UpdateAsset.get().execute(tag) match {
      case Left(l) => formatResponseData(l)
      case Right(s) => formatResponseData(Api.statusResponse(s))
    }
  }(Permissions.AssetApi.UpdateAsset)

  def updateAssetForMaintenance(tag: String) = UpdateForMaintenanceAction(
    tag, Permissions.AssetApi.UpdateAssetForMaintenance, this
  )

  // DELETE /api/asset/attribute/:attribute/:tag
  def deleteAssetAttribute(tag: String, attribute: String) = DeleteAttributeAction(
    tag, attribute, Permissions.AssetApi.DeleteAssetAttribute, this
  )

  // DELETE /api/asset/:tag
  def deleteAsset(tag: String) = DeleteAction(tag, Permissions.AssetApi.DeleteAsset, this)

}
