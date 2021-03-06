package com.hazelcast.Scala

import java.util.Collections
import java.util.Map.Entry

import scala.collection.JavaConverters._
import scala.collection.mutable.{ Map => mMap }
import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.language.existentials

import com.hazelcast.Scala.dds.{ DDS, MapDDS }
import com.hazelcast.client.spi.ClientProxy
import com.hazelcast.core
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map
import com.hazelcast.map.{ AbstractEntryProcessor, MapPartitionLostEvent }
import com.hazelcast.map.listener.MapPartitionLostListener
import com.hazelcast.query.{ Predicate, PredicateBuilder, TruePredicate }
import com.hazelcast.spi.AbstractDistributedObject

final class HzMap[K, V](private val imap: core.IMap[K, V]) extends AnyVal {

  // Sorta naughty:
  private[Scala] def getHZ: HazelcastInstance = imap match {
    case ado: AbstractDistributedObject[_] => ado.getNodeEngine.getHazelcastInstance
    case cp: ClientProxy =>
      val getClient = classOf[ClientProxy].getDeclaredMethod("getClient")
      getClient.setAccessible(true)
      getClient.invoke(cp).asInstanceOf[HazelcastInstance]
    case _ => sys.error(s"Cannot get HazelcastInstance from ${imap.getClass}")
  }

  def async: AsyncMap[K, V] = new AsyncMap(imap)

  /**
    * NOTE: This method is generally slower than `get`
    * and also will not be part of `get` statistics.
    * It is meant for very large objects where only a
    * subset of the data is needed, thus limiting
    * unnecessary network traffic.
    */
  def getAs[R](key: K, map: V => R): Option[R] = async.getAs(key, map).await(DefaultFutureTimeout)

  def getAllAs[R](keys: Set[K], mf: V => R): mMap[K, R] = {
    val ep = new AbstractEntryProcessor[K, V](false) {
      def process(entry: Entry[K, V]): Object = {
        entry.value match {
          case null => null
          case value => mf(value).asInstanceOf[Object]
        }
      }
    }
    imap.executeOnKeys(keys.asJava, ep).asScala.asInstanceOf[mMap[K, R]]
  }

  private def updateValues(predicate: Option[Predicate[_, _]], update: V => V, returnValue: V => Object): mMap[K, V] = {
    val ep = new AbstractEntryProcessor[K, V] {
      def process(entry: Entry[K, V]): Object = {
        entry.value = update(entry.value)
        returnValue(entry.value)
      }
    }
    val map = predicate match {
      case Some(predicate) => imap.executeOnEntries(ep, predicate)
      case None => imap.executeOnEntries(ep)
    }
    map.asScala.asInstanceOf[mMap[K, V]]
  }

  def update(predicate: Predicate[_, _] = null)(updateIfPresent: V => V): Unit = {
    updateValues(Option(predicate), updateIfPresent, _ => null)
  }
  def updateAndGet(predicate: Predicate[_, _])(updateIfPresent: V => V): mMap[K, V] = {
    updateValues(Option(predicate), updateIfPresent, _.asInstanceOf[Object])
  }

  def upsertAndGet(key: K, insertIfMissing: V)(updateIfPresent: V => V): V =
    async.upsertAndGet(key, insertIfMissing)(updateIfPresent).await
  def updateAndGet(key: K)(updateIfPresent: V => V): Option[V] =
    async.updateAndGet(key)(updateIfPresent).await
  def upsert(key: K, insertIfMissing: V)(updateIfPresent: V => V): UpsertResult =
    async.upsert(key, insertIfMissing)(updateIfPresent).await
  def update(key: K)(updateIfPresent: V => V): Boolean =
    async.update(key)(updateIfPresent).await
  def set(key: K, value: V, ttl: Duration) {
    if (ttl.isFinite && ttl.length > 0) {
      imap.set(key, value, ttl.length, ttl.unit)
    } else {
      imap.set(key, value)
    }
  }

  def put(key: K, value: V, ttl: Duration): Option[V] = {
    if (ttl.isFinite && ttl.length > 0) {
      Option(imap.put(key, value, ttl.length, ttl.unit))
    } else {
      Option(imap.put(key, value))
    }
  }
  def setTransient(key: K, value: V, ttl: Duration): Unit = {
    ttl match {
      case _: FiniteDuration => imap.putTransient(key, value, ttl.length, ttl.unit)
      case _ => imap.putTransient(key, value, 0, null)
    }
  }
  def putIfAbsent(key: K, value: V, ttl: Duration): Option[V] = {
    if (ttl.isFinite && ttl.length > 0) {
      Option(imap.putIfAbsent(key, value, ttl.length, ttl.unit))
    } else {
      Option(imap.putIfAbsent(key, value))
    }
  }

  def execute[R](filter: EntryFilter[K, V])(thunk: Entry[K, V] => R): mMap[K, R] = {
      def ep = new AbstractEntryProcessor[K, V] {
        def process(entry: Entry[K, V]): Object = thunk(entry) match {
          case Unit | None | null => null
          case r: Object => r
        }
      }
    val jMap: java.util.Map[K, Object] = filter match {
      case OnValues(include) =>
        imap.executeOnEntries(ep, include)
      case OnEntries(null) =>
        imap.executeOnEntries(ep)
      case OnEntries(predicate) =>
        imap.executeOnEntries(ep, predicate)
      case OnKeys(key) =>
        val value = imap.executeOnKey(key, ep)
        Collections.singletonMap(key, value)
      case OnKeys(keys @ _*) =>
        imap.executeOnKeys(keys.toSet.asJava, ep)
    }
    jMap.asInstanceOf[java.util.Map[K, R]].asScala
  }

  def onMapEvents(localOnly: Boolean = false)(pf: PartialFunction[MapEvent, Unit]): ListenerRegistration = {
    val listener: map.listener.MapListener = new MapListener(pf)
    val regId =
      if (localOnly) imap.addLocalEntryListener(listener)
      else imap.addEntryListener(listener, /* includeValue = */ false)
    new ListenerRegistration {
      def cancel(): Unit = imap.removeEntryListener(regId)
    }
  }

  def onKeyEvents(filter: Predicate[_, _] = null, key: K = null.asInstanceOf[K], localOnly: Boolean = false)(pf: PartialFunction[KeyEvent[K], Unit]): ListenerRegistration =
    subscribeEntries(new KeyListener(pf), localOnly, includeValue = false, Option(key), Option(filter))
  def onEntryEvents(filter: Predicate[_, _] = null, key: K = null.asInstanceOf[K], localOnly: Boolean = false)(pf: PartialFunction[EntryEvent[K, V], Unit]): ListenerRegistration =
    subscribeEntries(new EntryListener(pf), localOnly, includeValue = true, Option(key), Option(filter))
  def onPartitionLost(listener: PartialFunction[MapPartitionLostEvent, Unit]): ListenerRegistration = {
    val regId = imap addPartitionLostListener new MapPartitionLostListener {
      def partitionLost(evt: MapPartitionLostEvent) =
        if (listener isDefinedAt evt) listener(evt)
    }
    new ListenerRegistration {
      def cancel = imap removePartitionLostListener regId
    }
  }
  private def subscribeEntries(
    listener: map.listener.MapListener,
    localOnly: Boolean,
    includeValue: Boolean,
    key: Option[K],
    filter: Option[Predicate[_, _]]): ListenerRegistration = {
    val predicate = filter.getOrElse(TruePredicate.INSTANCE).asInstanceOf[Predicate[K, V]]
    val regId = key match {
      case Some(key) if localOnly => imap.addLocalEntryListener(listener, predicate, key, includeValue)
      case None if localOnly => imap.addLocalEntryListener(listener, predicate, includeValue)
      case Some(key) => imap.addEntryListener(listener, predicate, key, includeValue)
      case None => imap.addEntryListener(listener, predicate, includeValue)
    }
    new ListenerRegistration {
      def cancel(): Unit = imap.removeEntryListener(regId)
    }
  }

  def filter(pred: PredicateBuilder): DDS[Entry[K, V]] = new MapDDS(imap, pred)
  def filter(pred: Predicate[_, _]): DDS[Entry[K, V]] = new MapDDS(imap, pred)

  // TODO: Perhaps a macro could turn this into an IndexAwarePredicate?
  def filter(f: Entry[K, V] => Boolean): DDS[Entry[K, V]] = new MapDDS(imap, new ScalaEntryPredicate(f))

}
