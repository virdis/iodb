package io.iohk.iodb.bench

import io.iohk.iodb.{ByteArrayWrapper, Store, TestUtils}

import scala.util.{Random, Try}


trait Benchmark {
  type Key = Array[Byte]

  val KeySize = 32
  val ValueSize = 256

  def randomBytes(howMany: Int): Array[Byte] = {
    val res = new Array[Byte](howMany)
    Random.nextBytes(res)
    res
  }

  def randomKV(): (ByteArrayWrapper, ByteArrayWrapper) = {
    val key = new Array[Byte](KeySize)
    Random.nextBytes(key)
    val value = new Array[Byte](ValueSize)
    Random.nextBytes(value)
    ByteArrayWrapper(key) -> ByteArrayWrapper(value)
  }

  /**
    * Imitation of one block processing. We take random keys previously inserted
    * (for now, just a range of them), read them, remove, and append new objects.
    *
    * @return updated keys cache
    */
  def processBlock(version: Long,
                   store: Store,
                   inputs: Int,
                   outputs: Int,
                   keysCache: Seq[ByteArrayWrapper]): Try[Seq[ByteArrayWrapper]] = {
    val cacheSize = keysCache.size

    val (toRemove, cacheRem) = if (cacheSize > inputs) {
      val sliceStart = Random.nextInt(cacheSize - inputs)
      keysCache.slice(sliceStart, sliceStart + inputs) ->
        (keysCache.take(sliceStart) ++ keysCache.drop(sliceStart + inputs))
    } else (keysCache, Seq())

    val toAppend = (1 to outputs).map(_ => randomKV())

    toRemove.foreach(store.get)

    Try(store.update(TestUtils.fromLong(version), toRemove, toAppend)).map(_ =>
      cacheRem ++ toAppend.map(_._1)
    )
  }
}