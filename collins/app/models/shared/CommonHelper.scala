package models

trait CommonHelper[T] {
  type Reconstruction = Tuple2[T, Seq[MetaWrapper]]
  type FilteredSeq[T1] = Tuple2[Seq[T1], Map[Int, Seq[MetaWrapper]]]

  /**
   * Construct an appropriate AssetMetaValue sequence from the representation
   */
  def construct(asset: Asset, rep: T): Seq[AssetMetaValue]

  /**
   * Given an asset and sequence of meta wrappers, reconstruct its representation
   */
  def reconstruct(asset: Asset, assetMeta: Seq[MetaWrapper]): Reconstruction

  /**
   * Given some representation, update an asset with appropriate values
   */
  def updateAsset(asset: Asset, rep: T): Boolean = {
    // FIXME: Need to delete specific asset meta values before accepting an update
    // FIXME: Came from LshwHelper

    //FIXME: If a new LSHW/LLDP profile is missing meta values set on the old
    //one, those will not be deleted, since at this point we have no way of
    //knowing which tags belonged to the old one versus other unrelated tags
    val mvs = construct(asset, rep)
    val existing = AssetMetaValue.findByAsset(asset)
    val (exists, notExists) = mvs.partition{m => existing.find{m.asset_meta_id == _.asset_meta_id}.isDefined}
    exists.foreach{e => AssetMetaValue.deleteByAssetAndMetaId(asset.id, e.meta_id)}

    mvs.size == AssetMetaValue.create(mvs)
  }

  /**
   * Given some asset, reconstruct its representation from meta values
   */
  def reconstruct(asset: Asset): Reconstruction = {
    val assetMeta = AssetMetaValue.findByAsset(asset)
    reconstruct(asset, assetMeta)
  }
  protected def filterNot(m: Seq[MetaWrapper], s: Set[Long]): Seq[MetaWrapper] = {
    m.filterNot { mw => s.contains(mw.getMetaId) }
  }
  protected def finder[T](m: Seq[MetaWrapper], e: AssetMeta.Enum, c: (String => T), d: T): T = {
    m.find { _.getMetaId == e.id }.map { i => c(i.getValue) }.getOrElse(d)
  }
}
