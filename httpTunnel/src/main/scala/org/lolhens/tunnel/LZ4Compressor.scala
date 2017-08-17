package org.lolhens.tunnel

import akka.util.ByteString
import net.jpountz.lz4.LZ4Factory
import scodec.bits._
import scodec.interop.akka._

object LZ4Compressor {
  private val factory: LZ4Factory = LZ4Factory.fastestInstance()

  private lazy val compressor = factory.fastCompressor()
  private lazy val decompressor = factory.fastDecompressor()

  def compress(data: ByteVector): ByteVector = {
    val compressed = ByteVector.view(compressor.compress(data.toArray))
    if (compressed.size < data.size)
      ByteVector.fromInt(data.size.toInt) ++ compressed
    else
      ByteVector.fromInt(-1) ++ data
  }

  def decompress(data: ByteVector): ByteVector = {
    val (sizeBytes, compressed) = data.splitAt(4)
    val size = sizeBytes.toInt()
    if (size == -1)
      compressed
    else
      ByteVector.view(decompressor.decompress(compressed.toArray, size))
  }

  def compress(data: ByteString): ByteString = compress(data.toByteVector).toByteString

  def decompress(data: ByteString): ByteString = decompress(data.toByteVector).toByteString
}
