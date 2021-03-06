package io.iohk.iodb.prop

import java.security.MessageDigest

import io.iohk.iodb._
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import org.scalatest.{BeforeAndAfterAll, Matchers, PropSpec}

import scala.annotation.tailrec
import scala.util.Random

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class IODBSpecification extends PropSpec
  with PropertyChecks
  with GeneratorDrivenPropertyChecks
  with Matchers
  with BeforeAndAfterAll {

  val iFile = TestUtils.tempDir()
  iFile.mkdirs()


  property("rollback test LSM") {
    rollbackTest(blockStorage = new LSMStore(iFile))
  }

  property("writeKey test LSM") {
    writeKeyTest(blockStorage = new LSMStore(iFile))
  }

  property("doubleRollbackTest test LSM") {
    doubleRollbackTest(blockStorage = new LSMStore(iFile))
  }

  property("rollback test quick") {
    rollbackTest(blockStorage = new QuickStore(iFile))
  }

  property("writeKey test quick") {
    writeKeyTest(blockStorage = new QuickStore(iFile))
  }

  property("doubleRollbackTest test quick") {
    doubleRollbackTest(blockStorage = new QuickStore(iFile))
  }

  /**
    * double rollback to the same id
    */
  def doubleRollbackTest(blockStorage: Store): Unit = {
    val data = generateBytes(100)
    val block1 = BlockChanges(data.head._1, Seq(), data.take(50))
    val block2 = BlockChanges(data(51)._1, data.map(_._1).take(20), data.slice(51, 61))
    val block3 = BlockChanges(data(61)._1, data.map(_._1).slice(20, 30), data.slice(61, 71))
    blockStorage.update(block1.id, block1.toRemove, block1.toInsert)
    blockStorage.update(block2.id, block2.toRemove, block2.toInsert)
    blockStorage.rollback(block1.id)
    blockStorage.update(block3.id, block3.toRemove, block3.toInsert)
    blockStorage.rollback(block1.id)
  }


  def rollbackTest(blockStorage: Store) {
    //initialize test
    val NumberOfBlocks = 100
    val NumberOfRollbacks = 100

    val (blockchain: IndexedSeq[BlockChanges], existingKeys: Seq[ByteArrayWrapper]) = {
      @tailrec
      def loop(acc: IndexedSeq[BlockChanges], existingKeys: Seq[ByteArrayWrapper]): (IndexedSeq[BlockChanges], Seq[ByteArrayWrapper]) = {
        if (acc.length < NumberOfBlocks) {
          val toInsert = generateBytes(Random.nextInt(100))
          val toRemove: Seq[ByteArrayWrapper] = existingKeys.filter(k => Random.nextBoolean())
          val newExistingKeys = existingKeys.filter(ek => !toRemove.contains(ek)) ++ toInsert.map(_._1)
          val newBlock = BlockChanges(randomBytes(), toRemove, toInsert)
          loop(newBlock +: acc, newExistingKeys)
        } else {
          (acc, existingKeys)
        }
      }
      loop(IndexedSeq.empty, Seq.empty)
    }
    val allBlockchainKeys: Seq[ByteArrayWrapper] = blockchain.flatMap(_.toInsert.map(_._1))

    def storageHash(storage: Store): Array[Byte] = {
      val valuesBytes = allBlockchainKeys.map(k => storage.get(k).map(_.toString).getOrElse("")).mkString.getBytes
      hash(valuesBytes)
    }

    //initialize blockchain
    blockchain.foreach(b => blockStorage.update(b.id, b.toRemove, b.toInsert))

    val finalHash = storageHash(blockStorage)
    val finalVersion = blockStorage.lastVersionID.get

    (0 until NumberOfRollbacks) foreach { _ =>
      val idToRollback: ByteArrayWrapper = blockchain.map(_.id).apply(Random.nextInt(blockchain.length))
      val index: Int = blockchain.indexWhere(_.id == idToRollback)
      val removedBlocks = blockchain.takeRight(NumberOfBlocks - index - 1)
      blockStorage.rollback(idToRollback)

      removedBlocks.foreach(b => blockStorage.update(b.id, b.toRemove, b.toInsert))
      blockStorage.lastVersionID.get shouldEqual finalVersion
      storageHash(blockStorage) shouldEqual finalHash
    }

    existingKeys.foreach(ek => blockStorage.get(ek).isDefined shouldBe true)
  }


  def writeKeyTest(blockStorage: Store) {
    var ids: Seq[ByteArrayWrapper] = Seq()
    var removed: Seq[ByteArrayWrapper] = Seq()
    var i = 0

    forAll { (key: String, value: Array[Byte], removing: Boolean) =>
      val toRemove = if (removing && ids.nonEmpty) Seq(ids(Random.nextInt(ids.length))) else Seq()
      toRemove.foreach { tr =>
        ids = ids.filter(i => i != tr)
        removed = tr +: removed
      }

      val id: ByteArrayWrapper = hash(i + key)
      val fValue: ByteArrayWrapper = ByteArrayWrapper(value)
      ids = id +: ids
      i = i + 1

      blockStorage.update(
        id,
        toRemove,
        Seq(id -> fValue))
    }

    //old keys are defined
    ids.foreach { id =>
      blockStorage.get(id) match {
        case None => throw new Error(s"Id $id} not found")
        case Some(v) =>
      }
    }

    //removed keys are defined not defined
    removed.foreach { id =>
      blockStorage.get(id) match {
        case None =>
        case Some(v) => throw new Error(s"Id $id} is defined after delete")
      }
    }
    ids.foreach(id => blockStorage.rollbackVersions().exists(_ == id) shouldBe true)

  }


  case class BlockChanges(id: ByteArrayWrapper,
                          toRemove: Seq[ByteArrayWrapper],
                          toInsert: Seq[(ByteArrayWrapper, ByteArrayWrapper)])

  def hash(b: Array[Byte]): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(b)

  def randomBytes(): ByteArrayWrapper = ByteArrayWrapper(hash(Random.nextString(16).getBytes))

  def generateBytes(howMany: Int): Seq[(ByteArrayWrapper, ByteArrayWrapper)] = {
    (0 until howMany).map(i => (randomBytes(), randomBytes()))
  }


  override protected def afterAll(): Unit = TestUtils.deleteRecur(iFile)


  def hash(s: String) = ByteArrayWrapper(MessageDigest.getInstance("SHA-256").digest(s.getBytes))
}