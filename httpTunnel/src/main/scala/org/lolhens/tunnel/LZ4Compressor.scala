package org.lolhens.tunnel

import akka.util.ByteString
import net.jpountz.lz4.LZ4Factory
import scodec.bits._
import scodec.interop.akka._

object LZ4Compressor {
  private val factory: LZ4Factory = LZ4Factory.fastestInstance()

  private lazy val compressor = factory.fastCompressor()
  private lazy val decompressor = factory.fastDecompressor()

  private val noCompressionMarker = bin"10000000".toByteVector

  def compress(data: ByteVector): ByteVector = {
    val compressed = ByteVector.view(compressor.compress(data.toArray))
    val size = data.size.toInt
    if (compressed.size < data.size && size >= 0)
      ByteVector.fromInt(size) ++ compressed
    else
      noCompressionMarker ++ data
  }

  def decompress(data: ByteVector): ByteVector =
    if (data.take(1) === noCompressionMarker)
      data.drop(1)
    else {
      val size = data.take(4).toInt()
      ByteVector.view(decompressor.decompress(data.drop(4).toArray, size))
    }

  def compress(data: ByteString): ByteString = compress(data.toByteVector).toByteString

  def decompress(data: ByteString): ByteString = decompress(data.toByteVector).toByteString
}
