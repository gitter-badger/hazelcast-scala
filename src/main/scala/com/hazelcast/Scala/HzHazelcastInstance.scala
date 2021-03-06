package com.hazelcast.Scala

import java.util.concurrent.TimeUnit
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import scala.reflect.{ ClassTag, classTag }
import com.hazelcast.cache.ICache
import com.hazelcast.cache.impl.HazelcastServerCachingProvider
import com.hazelcast.client.cache.impl.HazelcastClientCachingProvider
import com.hazelcast.core.{ DistributedObjectEvent, DistributedObjectListener, HazelcastInstance, IExecutorService, LifecycleEvent }
import com.hazelcast.core.{ LifecycleListener, Member, MigrationEvent, MigrationListener, Partition }
import com.hazelcast.core.LifecycleEvent.LifecycleState
import com.hazelcast.partition.{ PartitionLostEvent, PartitionLostListener }
import com.hazelcast.transaction.{ TransactionalTask, TransactionalTaskContext }
import com.hazelcast.transaction.TransactionOptions
import com.hazelcast.transaction.TransactionOptions.TransactionType
import javax.cache.CacheManager
import javax.transaction.TransactionManager
import javax.transaction.xa.XAResource
import com.hazelcast.config.CacheConfig

private object HzHazelcastInstance {
  private[this] val DefaultTxnOpts = TransactionOptions.getDefault
  private val DefaultTxnType = DefaultTxnOpts.getTransactionType match {
    case TransactionType.ONE_PHASE | TransactionType.LOCAL => OnePhase
    case TransactionType.TWO_PHASE => TwoPhase(DefaultTxnOpts.getDurability)
  }
  private val DefaultTxnTimeout = FiniteDuration(TransactionOptions.getDefault.getTimeoutMillis, TimeUnit.MILLISECONDS)

  private val CacheManagers = new TrieMap[HazelcastInstance, CacheManager]
}

final class HzHazelcastInstance(private val hz: HazelcastInstance) extends AnyVal {
  import HzHazelcastInstance._

  private[Scala] def groupByPartition[K](keys: collection.Set[K]): Map[Partition, collection.Set[K]] = {
    val ps = hz.getPartitionService
    keys.groupBy(ps.getPartition)
  }
  private[Scala] def groupByMember[K](keys: collection.Set[K]): Map[Member, collection.Set[K]] = {
    val ps = hz.getPartitionService
    keys.groupBy(ps.getPartition(_).getOwner)
  }

  private[Scala] def queryPool(): IExecutorService = hz.getExecutorService("hz:query")

  def onDistributedObjectEvent(listener: PartialFunction[DistributedObjectEvent, Unit]): ListenerRegistration = {
    val regId = hz addDistributedObjectListener new DistributedObjectListener {
      def distributedObjectCreated(evt: DistributedObjectEvent) = hear(evt)
      def distributedObjectDestroyed(evt: DistributedObjectEvent) = hear(evt)
      @inline def hear(evt: DistributedObjectEvent) = if (listener isDefinedAt evt) listener(evt)
    }
    new ListenerRegistration {
      def cancel() = hz removeDistributedObjectListener regId
    }
  }

  def onLifecycleStateChange(listener: PartialFunction[LifecycleState, Unit]): ListenerRegistration = {
    val service = hz.getLifecycleService
    val regId = service addLifecycleListener new LifecycleListener {
      def stateChanged(evt: LifecycleEvent): Unit =
        if (listener isDefinedAt evt.getState) listener(evt.getState)
    }
    new ListenerRegistration {
      def cancel() = service removeLifecycleListener regId
    }
  }

  def onPartitionLost(listener: PartitionLostEvent => Unit): ListenerRegistration = {
    val service = hz.getPartitionService
    val regId = service addPartitionLostListener new PartitionLostListener {
      def partitionLost(evt: PartitionLostEvent): Unit = listener(evt)
    }
    new ListenerRegistration {
      def cancel(): Unit = service removePartitionLostListener regId
    }
  }
  def onMigration(listener: PartialFunction[MigrationEvent, Unit]): ListenerRegistration = {
    val service = hz.getPartitionService
    val regId = service addMigrationListener new MigrationListener {
      def migrationCompleted(evt: MigrationEvent) = hear(evt)
      def migrationFailed(evt: MigrationEvent) = hear(evt)
      def migrationStarted(evt: MigrationEvent) = hear(evt)
      @inline def hear(evt: MigrationEvent): Unit =
        if (listener isDefinedAt evt) listener(evt)
    }
    new ListenerRegistration {
      def cancel(): Unit = service removeMigrationListener regId
    }
  }

  /**
    * Execute transaction.
    * @param durability Number of backups
    * @param transactionType Type of transaction
    * @param timeout Transaction timeout
    */
  def transaction[T](
    txnType: TxnType = DefaultTxnType,
    timeout: FiniteDuration = DefaultTxnTimeout)(thunk: TransactionalTaskContext => T): T = {
    val opts = new TransactionOptions().setTimeout(timeout.length, timeout.unit)
    txnType match {
      case OnePhase =>
        opts.setTransactionType(TransactionType.ONE_PHASE)
      case TwoPhase(durability) =>
        opts.setTransactionType(TransactionType.TWO_PHASE).setDurability(durability)
    }
    transaction(opts)(thunk)
  }
  def transaction[T](opts: TransactionOptions)(thunk: TransactionalTaskContext => T): T = {
    val task = new TransactionalTask[T] {
      def execute(ctx: TransactionalTaskContext) = thunk(ctx)
    }
    if (opts == null) {
      hz.executeTransaction(task)
    } else {
      hz.executeTransaction(opts, task)
    }
  }

  def transaction[T](txnMgr: TransactionManager, resources: XAResource*)(thunk: TransactionalTaskContext => T): T = {
    txnMgr.begin()
    val txn = txnMgr.getTransaction
    val hzResource = hz.getXAResource()
    try {
      (hzResource +: resources).foreach(txn.enlistResource)
      val result = thunk(hzResource.getTransactionContext)
      (hzResource +: resources).foreach(txn.delistResource(_, XAResource.TMSUCCESS))
      txnMgr.commit()
      result
    } catch {
      case t: Throwable =>
        txnMgr.rollback()
        throw t
    }
  }

  def isClient: Boolean = {
    val cluster = hz.getCluster
    try {
      cluster.getLocalMember == null
    } catch {
      case _: UnsupportedOperationException => true
    }
  }

  private def jCacheProviderType: Option[String] = {
    val configProp =
      if (isClient) None
      else Option(hz.getConfig.getProperty("hazelcast.jcache.provider.type"))
    configProp.orElse(Option(System.getProperty("hazelcast.jcache.provider.type")))
  }

  private def getObjectType[T: ClassTag]: Class[T] = classTag[T].runtimeClass match {
    case cls if cls.isPrimitive => Types.PrimitiveWrappers(cls).asInstanceOf[Class[T]]
    case cls => cls.asInstanceOf[Class[T]]
  }

  def userCtx: UserContext = new UserContext(hz.getUserContext)

  private def getCacheProvider[K, V](cacheName: String, entryTypes: Option[(Class[K], Class[V])]) = {
      def setClassType(classType: Class[_], getType: () => String, setType: String => Unit) {
        val typeName = classType.getName
        getType() match {
          case null =>
            setType(typeName)
          case configured if configured != typeName =>
            sys.error(s"""Type $typeName, for cache "$cacheName", does not match configured type $configured""")
          case _ => // Already set and matching
        }
      }
    val isClient = jCacheProviderType match {
      case Some("client") => true
      case Some("server") => false
      case Some(other) => sys.error(s"Unknown provider type: $other")
      case None => this.isClient
    }
    if (isClient) {
      HazelcastClientCachingProvider.createCachingProvider(hz)
    } else {
      entryTypes.foreach {
        case (keyType, valueType) =>
          val conf = hz.getConfig.getCacheConfig(cacheName)
          setClassType(keyType, conf.getKeyType, conf.setKeyType)
          setClassType(valueType, conf.getValueType, conf.setValueType)
      }
      HazelcastServerCachingProvider.createCachingProvider(hz)
    }
  }

  def getCache[K: ClassTag, V: ClassTag](name: String, typesafe: Boolean = true): ICache[K, V] = {
    val entryType = if (typesafe) {
      Some(getObjectType[K] -> getObjectType[V])
    } else None
    val mgr = CacheManagers.get(hz) getOrElse {
      val mgr = getCacheProvider(name, entryType).getCacheManager
      CacheManagers.putIfAbsent(hz, mgr) getOrElse {
        onLifecycleStateChange {
          case LifecycleState.SHUTDOWN => CacheManagers.remove(hz)
        }
        mgr
      }
    }
    val cache = entryType.map {
      case (keyType, valueType) => mgr.getCache[K, V](name, keyType, valueType)
    }.getOrElse(mgr.getCache[K, V](name)) match {
      case null =>
        val cc = new javax.cache.configuration.Configuration[K, V] {
          def getKeyType() = entryType.map(_._1) getOrElse classOf[Object].asInstanceOf[Class[K]]
          def getValueType() = entryType.map(_._2) getOrElse classOf[Object].asInstanceOf[Class[V]]
          def isStoreByValue() = true
        }
        mgr.createCache[K, V, cc.type](name, cc)
      case cache => cache
    }
    cache.unwrap(classOf[ICache[K, V]])
  }
}
