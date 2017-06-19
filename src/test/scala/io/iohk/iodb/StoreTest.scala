package io.iohk.iodb

import io.iohk.iodb.Store.{K, V}
import io.iohk.iodb.TestUtils._
import org.junit.Assert.assertEquals
import org.junit.Test

import scala.collection.mutable
import scala.util.Random

abstract class StoreTest extends TestWithTempDir{

  def open(keySize: Int = 32): Store


  def testReopen(): Unit = {
    var store = open(keySize = 8)
    store.update(fromLong(1L), toUpdate = (1L to 100L).map(i=>(fromLong(i), fromLong(i))), toRemove=Nil)
    store.update(fromLong(2L), toUpdate = Nil, toRemove = (90L to 100L).map(fromLong))

    def check(): Unit = {
      (1L to 89L).foreach(i=> assert(Some(fromLong(i))== store.get(fromLong(i))))
    }

    check()
    store.close()
    store = open(keySize = 8)

    check()

    store.close()
  }


  @Test def get() {
    val store = open(keySize = 32)

    //random testing
    val r = new Random()
    val data = (0 until 1000).map { i =>
      val key = randomA(32)
      val valSize = 10 + r.nextInt(100)
      val value = randomA(valSize)
      (key, value)
    }.sortBy(_._1)

    //put
    store.update(versionID = fromLong(1L), toUpdate = data, toRemove = Nil)

    //try to read all values
    for ((key, value) <- data) {
      assertEquals(Some(value), store.get(key))
    }
    //try non existent
    val nonExistentKey = randomA(32)
    assertEquals(None, store.get(nonExistentKey))
    store.close()
  }

  @Test def get_getAll(): Unit = {
    val store = open(keySize = 8)

    val updated = mutable.HashMap[K, V]()
    val removed = mutable.HashSet[K]()
    for (i <- 0 until 10) {
      //generate random data
      var toUpdate = (0 until 10).map(a => (randomA(8), randomA(40)))
      var toRemove: List[K] = if (updated.isEmpty) Nil else updated.keys.take(2).toList
      toRemove.foreach(updated.remove(_))

      //modify
      store.update(fromLong(i), toUpdate = toUpdate, toRemove = toRemove)

      removed ++= toRemove
      updated ++= toUpdate

      removed.foreach { k =>
        assertEquals(store.get(k), None)
      }
      for ((k, v) <- updated) {
        assertEquals(store.get(k), Some(v))
      }
    }

    //try to iterate over all items in store
    val updated2 = mutable.HashMap[K, V]()

    for ((key, value) <- store.getAll()) {
      updated2.put(key, value)
    }
    assertEquals(updated, updated2)

    store.close()
  }


  @Test def getVersions(): Unit = {
    val store = open(keySize = 8)

    val versions = (0L until 100).map(fromLong).toBuffer
    val updates = List((fromLong(1L), fromLong(1)))

    for (version <- versions) {
      store.update(versionID = version, toUpdate = updates, toRemove = Nil)
    }
    assertEquals(Some(fromLong(1L)), store.get(fromLong(1L)))
    val versions2 = store.rollbackVersions().toBuffer
    assertEquals(versions.reverse, versions2)
    store.close()
  }
}


class QuickStoreRefTest extends StoreTest {
  override def open(keySize: Int): Store = new QuickStore(dir)
}

