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

  def compress(uncompressed: ByteVector): ByteVector = {
    val compressed = ByteVector.view(compressor.compress(uncompressed.toArray))
    val size = uncompressed.size.toInt
    if (size >= 0 && compressed.size + 3 < uncompressed.size)
      ByteVector.fromInt(size) ++ compressed
    else
      noCompressionMarker ++ uncompressed
  }

  def decompress(compressed: ByteVector): ByteVector =
    if (compressed.take(1) === noCompressionMarker)
      compressed.drop(1)
    else {
      val size = compressed.take(4).toInt()
      ByteVector.view(decompressor.decompress(compressed.drop(4).toArray, size))
    }

  def compress(data: ByteString): ByteString = compress(data.toByteVector).toByteString

  def decompress(data: ByteString): ByteString = decompress(data.toByteVector).toByteString
}
