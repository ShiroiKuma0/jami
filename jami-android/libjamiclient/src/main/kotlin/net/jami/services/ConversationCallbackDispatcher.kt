package net.jami.services

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.CompletableSource
import io.reactivex.rxjava3.core.Scheduler
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.processors.FlowableProcessor
import io.reactivex.rxjava3.processors.PublishProcessor
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.CompletableSubject
import io.reactivex.rxjava3.subjects.SingleSubject
import net.jami.utils.Log

internal class ConversationCallbackDispatcher(
    scheduler: Scheduler = Schedulers.computation(),
    private val onError: (Throwable) -> Unit = { Log.e(TAG, "Conversation callback failed", it) }
) : Disposable {
    private data class ConversationKey(val accountId: String, val conversationId: String)

    private data class Key(val conversation: ConversationKey, val generation: Long)

    private class Route(val generation: Long, val ready: CompletableSource) {
        var started = false
    }

    private data class Task(
        val key: Key,
        val ready: CompletableSource,
        val closeAfter: Boolean,
        val action: () -> Unit
    )

    private val routingLock = Any()
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var pendingReported = 0
    private val routes = HashMap<ConversationKey, Route>()
    private var nextGeneration = 0L
    private val tasks: FlowableProcessor<Task> = PublishProcessor.create<Task>().toSerialized()
    private val subscription = tasks
        .onBackpressureBuffer()
        .groupBy(Task::key)
        .flatMapCompletable { group ->
            group.takeUntil(Task::closeAfter).concatMapCompletable { task ->
                Completable.wrap(task.ready)
                    .andThen(Completable.fromAction {
                        // SK-OPENDIAG: a callback that holds its conversation's line (and a shared
                        // computation thread) for over a second is what stalls history loading.
                        val t0 = System.currentTimeMillis()
                        pending.decrementAndGet()
                        try { task.action() } finally {
                            val dt = System.currentTimeMillis() - t0
                            if (dt > 1000) net.jami.utils.SkTrace.log { "SK-OPENDIAG slow callback ${dt} ms conv=${task.key.conversation.conversationId.take(8)} pending=${pending.get()}" }
                        }
                    })
                    .subscribeOn(scheduler)
                    .onErrorComplete { error ->
                        onError(error)
                        true
                    }
            }
        }
        .subscribe({}, onError)

    fun dispatch(accountId: String, conversationId: String, action: () -> Unit) {
        synchronized(routingLock) {
            if (isDisposed)
                return
            val conversation = ConversationKey(accountId, conversationId)
            val route = routes.getOrPut(conversation) {
                Route(nextGeneration++, Completable.complete())
            }
            route.started = true
            val n = pending.incrementAndGet()
            if (n >= pendingReported * 2 && n >= 50) {
                pendingReported = n
                net.jami.utils.SkTrace.log { "SK-OPENDIAG callback backlog ${n} (latest conv=${conversationId.take(8)})" }
            } else if (n < 10) pendingReported = 0
            tasks.onNext(Task(Key(conversation, route.generation), route.ready, false, action))
        }
    }

    /** Runs [action] after every callback already dispatched for this conversation. */
    fun <T : Any> submit(accountId: String, conversationId: String, action: () -> T): Single<T> {
        val result = SingleSubject.create<T>()
        synchronized(routingLock) {
            if (isDisposed)
                return Single.error(IllegalStateException("Dispatcher is disposed"))
            dispatch(accountId, conversationId) {
                try {
                    result.onSuccess(action())
                } catch (e: Throwable) {
                    result.onError(e)
                }
            }
        }
        return result
    }

    fun dispatchAndClose(accountId: String, conversationId: String, action: () -> Unit) {
        synchronized(routingLock) {
            if (isDisposed)
                return
            val conversation = ConversationKey(accountId, conversationId)
            val route = routes[conversation] ?: Route(nextGeneration++, Completable.complete())
            val successorReady = CompletableSubject.create()
            val successor = Route(nextGeneration++, successorReady)
            routes[conversation] = successor
            pending.incrementAndGet()   // balances the decrement every task does when it runs
            tasks.onNext(Task(Key(conversation, route.generation), route.ready, true) {
                try {
                    action()
                } finally {
                    synchronized(routingLock) {
                        if (!successor.started && routes[conversation] === successor)
                            routes.remove(conversation)
                    }
                    successorReady.onComplete()
                }
            })
        }
    }

    override fun dispose() {
        synchronized(routingLock) {
            routes.clear()
            tasks.onComplete()
            subscription.dispose()
        }
    }

    override fun isDisposed(): Boolean = subscription.isDisposed

    private companion object {
        val TAG = ConversationCallbackDispatcher::class.simpleName!!
    }
}