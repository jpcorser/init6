package com.vilenet.coders.binary

import java.nio.ByteOrder

import akka.util.ByteString

/**
 * Created by filip on 10/25/15.
 */
trait BinaryPacket {

  implicit val byteOrder = ByteOrder.LITTLE_ENDIAN

  val ID_SID_ENTER_CHAT = 0x0A.toByte
  val ID_SID_CHAT_EVENT = 0x0F.toByte
  val ID_SID_LOGON_RESPONSE2 = 0x3A.toByte
  val ID_SID_AUTH_INFO = 0x50.toByte
  val ID_SID_AUTH_CHECK = 0x51.toByte

  val PACKET_HEADER = 0xFF.toByte
  val PACKET_HEADER_LENGTH = 4.toShort

  implicit def stringToNTBytes(string: String): Array[Byte] = {
    Array.newBuilder[Byte]
      .++=(string.getBytes)
      .+=(0)
      .result()
  }

  def build(packetId: Byte, data: ByteString) = {
    ByteString.newBuilder
      .putByte(PACKET_HEADER)
      .putByte(packetId)
      .putShort(data.length + PACKET_HEADER_LENGTH)
      .append(data)
      .result()
  }
}
