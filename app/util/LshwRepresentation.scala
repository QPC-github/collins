package util

import play.api.libs.json._

sealed abstract class LshwAsset {
  val description: String
  val product: String
  val vendor: String
  def forJsonObject(): Seq[(String,JsValue)]
}
object LshwAsset {
  def apply(desc: String, prod: String, vend: String) = new LshwAsset {
    val description = desc
    val product = prod
    val vendor = vend
    override def forJsonObject(): Seq[(String,JsValue)] = throw new Exception("Don't call forJsonObject on stubs")
  }
}

case class Cpu(cores: Int, threads: Int, speedGhz: Double, description: String, product: String, vendor: String) extends LshwAsset
{
  def forJsonObject(): Seq[(String,JsValue)] = Seq(
    "CORES" -> JsNumber(cores),
    "THREADS" -> JsNumber(threads),
    "SPEED_GHZ" -> JsNumber(speedGhz),
    "DESCRIPTION" -> JsString(description)
  );
}
case class Memory(size: ByteStorageUnit, bank: Int, description: String, product: String, vendor: String) extends LshwAsset
{
  def forJsonObject(): Seq[(String,JsValue)] = Seq(
    "SIZE" -> JsNumber(size.inBytes),
    "SIZE_S" -> JsString(size.inBytes.toString),
    "SIZE_HUMAN" -> JsString(size.toHuman),
    "BANK" -> JsNumber(bank),
    "DESCRIPTION" -> JsString(description)
  );
}
case class Nic(speed: BitStorageUnit, macAddress: String, description: String, product: String, vendor: String)
  extends LshwAsset
{
  def forJsonObject(): Seq[(String,JsValue)] = Seq(
    "SPEED" -> JsNumber(speed.inBits),
    "SPEED_S" -> JsString(speed.inBits.toString),
    "SPEED_HUMAN" -> JsString(speed.toHuman),
    "MAC_ADDRESS" -> JsString(macAddress),
    "DESCRIPTION" -> JsString(description)
  )
}

case class Disk(size: ByteStorageUnit, diskType: Disk.Type, description: String, product: String, vendor: String)
  extends LshwAsset
{
  def isFlash(): Boolean = diskType == Disk.Type.Flash
  def isCdRom(): Boolean = diskType == Disk.Type.CdRom
  def forJsonObject(): Seq[(String,JsValue)] = Seq(
    "SIZE" -> JsNumber(size.inBytes),
    "SIZE_S" -> JsString(size.inBytes.toString),
    "SIZE_HUMAN" -> JsString(size.toHuman),
    "TYPE" -> JsString(diskType.toString),
    "DESCRIPTION" -> JsString(description)
  );
}
object Disk {
  type Type = Type.Value
  object Type extends Enumeration {
    val Ide = Value("IDE")
    val Scsi = Value("SCSI")
    val Flash = Value("PCIe")
    val CdRom = Value("CD-ROM")
  }
}

object LshwRepresentation {
  def empty(): LshwRepresentation = {
    new LshwRepresentation(Seq(), Seq(), Seq(), Seq())
  }
}

case class LshwRepresentation(
  cpus: Seq[Cpu],
  memory: Seq[Memory],
  nics: Seq[Nic],
  disks: Seq[Disk]
) {
  def cpuCount: Int = cpus.size
  def hasHyperthreadingEnabled: Boolean = (cpuThreadCount > cpuCoreCount)
  def cpuCoreCount: Int = cpus.foldLeft(0) { case(total,cpu) =>
    total + cpu.cores
  }
  def cpuThreadCount: Int = cpus.foldLeft(0) { case(total,cpu) =>
    total + cpu.threads
  }
  def cpuSpeed: Double = cpus.sortBy(_.speedGhz).lastOption.map(_.speedGhz).getOrElse(0.0)

  def totalMemory: ByteStorageUnit = memory.foldLeft(new ByteStorageUnit(0)) { case(total,mem) =>
    new ByteStorageUnit(total.bytes + mem.size.bytes)
  }
  def memoryBanksUsed: Int = memory.filter { _.size.bytes > 0 }.size
  def memoryBanksUnused: Int = memory.filter { _.size.bytes == 0 }.size
  def memoryBanksTotal: Int = memory.size

  def totalStorage: ByteStorageUnit = disks.filterNot { disk =>
    disk.isFlash
  }.foldLeft(new ByteStorageUnit(0)) { case(total,disk) =>
    new ByteStorageUnit(total.bytes + disk.size.bytes)
  }
  def diskCount: Int = disks.size

  def hasCdRom: Boolean = disks.find { _.isCdRom }.isDefined
  def hasFlashStorage: Boolean = disks.find { _.isFlash }.isDefined
  def totalFlashStorage: ByteStorageUnit = {
    disks.filter { _.isFlash }.foldLeft(ByteStorageUnit(0)) { case (sum,disk) =>
      new ByteStorageUnit(sum.bytes + disk.size.bytes)
    }
  }
  def totalUsableStorage: ByteStorageUnit = disks.foldLeft(ByteStorageUnit(0)) { case (sum,disk) =>
    new ByteStorageUnit(sum.bytes + disk.size.bytes)
  }

  def nicCount: Int = nics.size
  def hasGbNic: Boolean = nics.find { _.speed.inGigabits == 1 }.map { _ => true }.getOrElse(false)
  def has10GbNic: Boolean = nics.find { _.speed.inGigabits == 10 }.map { _ => true }.getOrElse(false)
  def macAddresses: Seq[String] = nics.map { _.macAddress }

  def forJsonObject(): Seq[(String,JsValue)] = Seq(
    "CPU" -> JsArray(cpus.map { cpu =>
      JsObject(cpu.forJsonObject)
    }.toList),
    "MEMORY" -> JsArray(memory.map { mem =>
      JsObject(mem.forJsonObject)
    }.toList),
    "NIC" -> JsArray(nics.map { nic =>
      JsObject(nic.forJsonObject)
    }.toList),
    "DISK" -> JsArray(disks.map { disk =>
      JsObject(disk.forJsonObject)
    }.toList)
  )

  override def equals(that: Any) = that match {
    case other: LshwRepresentation =>
      (macAddresses.sorted == other.macAddresses.sorted) &&
      (cpuCount == other.cpuCount) &&
      (hasHyperthreadingEnabled == other.hasHyperthreadingEnabled) &&
      (cpuCoreCount == other.cpuCoreCount) &&
      (cpuThreadCount == other.cpuThreadCount) &&
      (cpuSpeed == other.cpuSpeed) &&
      (totalMemory.inBytes == other.totalMemory.inBytes) &&
      (memoryBanksUsed == other.memoryBanksUsed) &&
      (memoryBanksUnused == other.memoryBanksUnused) &&
      (memoryBanksTotal == other.memoryBanksTotal) &&
      (totalStorage.inBytes == other.totalStorage.inBytes) &&
      (diskCount == other.diskCount) &&
      (hasFlashStorage == other.hasFlashStorage) &&
      (totalFlashStorage.inBytes == other.totalFlashStorage.inBytes) &&
      (totalUsableStorage.inBytes == other.totalUsableStorage.inBytes) &&
      (nicCount == other.nicCount) &&
      (hasGbNic == other.hasGbNic) &&
      (has10GbNic == other.has10GbNic)
    case _ => false
  }
}
