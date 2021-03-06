/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.serialization

import scala.collection.immutable
import com.google.protobuf.ByteString
import akka.actor.ActorSelectionMessage
import akka.actor.ExtendedActorSystem
import akka.actor.SelectChildName
import akka.actor.SelectChildPattern
import akka.actor.SelectParent
import akka.actor.SelectionPathElement
import akka.remote.ContainerFormats
import akka.serialization.SerializationExtension
import akka.serialization.BaseSerializer

class MessageContainerSerializer(val system: ExtendedActorSystem) extends BaseSerializer {

  def includeManifest: Boolean = false

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case sel: ActorSelectionMessage ⇒ serializeSelection(sel)
    case _                          ⇒ throw new IllegalArgumentException(s"Cannot serialize object of type [${obj.getClass.getName}]")
  }

  import ContainerFormats.PatternType._

  private def serializeSelection(sel: ActorSelectionMessage): Array[Byte] = {
    val builder = ContainerFormats.SelectionEnvelope.newBuilder()
    val message = sel.msg.asInstanceOf[AnyRef]
    val serializer = SerializationExtension(system).findSerializerFor(message)
    builder.
      setEnclosedMessage(ByteString.copyFrom(serializer.toBinary(message))).
      setSerializerId(serializer.identifier).
      setWildcardFanOut(sel.wildcardFanOut)

    if (serializer.includeManifest)
      builder.setMessageManifest(ByteString.copyFromUtf8(message.getClass.getName))

    sel.elements.foreach {
      case SelectChildName(name) ⇒
        builder.addPattern(buildPattern(Some(name), CHILD_NAME))
      case SelectChildPattern(patternStr) ⇒
        builder.addPattern(buildPattern(Some(patternStr), CHILD_PATTERN))
      case SelectParent ⇒
        builder.addPattern(buildPattern(None, PARENT))
    }

    builder.build().toByteArray
  }

  private def buildPattern(matcher: Option[String], tpe: ContainerFormats.PatternType): ContainerFormats.Selection.Builder = {
    val builder = ContainerFormats.Selection.newBuilder().setType(tpe)
    matcher foreach builder.setMatcher
    builder
  }

  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = {
    val selectionEnvelope = ContainerFormats.SelectionEnvelope.parseFrom(bytes)
    val msg = SerializationExtension(system).deserialize(
      selectionEnvelope.getEnclosedMessage.toByteArray,
      selectionEnvelope.getSerializerId,
      if (selectionEnvelope.hasMessageManifest)
        Some(system.dynamicAccess.getClassFor[AnyRef](selectionEnvelope.getMessageManifest.toStringUtf8).get) else None).get

    import scala.collection.JavaConverters._
    val elements: immutable.Iterable[SelectionPathElement] = selectionEnvelope.getPatternList.asScala.map { x ⇒
      x.getType match {
        case CHILD_NAME    ⇒ SelectChildName(x.getMatcher)
        case CHILD_PATTERN ⇒ SelectChildPattern(x.getMatcher)
        case PARENT        ⇒ SelectParent
      }

    }(collection.breakOut)
    val wildcardFanOut = if (selectionEnvelope.hasWildcardFanOut) selectionEnvelope.getWildcardFanOut else false
    ActorSelectionMessage(msg, elements, wildcardFanOut)
  }
}
